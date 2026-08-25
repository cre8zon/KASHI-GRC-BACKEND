package com.kashi.grc.audit.service;

import com.kashi.grc.audit.domain.AuditPolicy;
import com.kashi.grc.workflow.domain.StepInstance;
import com.kashi.grc.workflow.domain.TaskInstance;
import com.kashi.grc.workflow.enums.StepStatus;
import com.kashi.grc.workflow.enums.TaskStatus;
import com.kashi.grc.workflow.repository.StepInstanceRepository;
import com.kashi.grc.workflow.repository.TaskInstanceRepository;
import com.kashi.grc.workflow.repository.TaskSectionCompletionRepository;
import com.kashi.grc.workflow.service.TaskSectionCompletionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Bridges policy lifecycle actions to the Policy Approval workflow.
 *
 * ── WHY THIS EXISTS ─────────────────────────────────────────────────────────
 * A workflow step completes when the sections snapshotted onto its task are
 * marked done. Nothing was marking them for policies, so the Policy Approval
 * workflow started and then sat at "0/3 steps" with no way forward — the step
 * had no sections and no domain code firing their completion events.
 *
 * This is the same shape AuditEngagementService already uses:
 *   find the live task → find the section whose snapCompletionEvent matches →
 *   complete it → the engine advances the step.
 *
 * ── WHY EVERY METHOD SWALLOWS ITS EXCEPTIONS ────────────────────────────────
 * Workflow progression is a SIDE EFFECT of the policy action, never its point.
 * A tenant with no Policy Approval blueprint configured must still be able to
 * draft, review and approve policies — the workflow is optional infrastructure.
 * Letting a missing section roll back an approval would make the feature's
 * absence break the feature it decorates.
 *
 * The trade-off is real: a genuinely broken workflow fails quietly. That is why
 * each path logs at WARN with the event name, so "policy approved but step 3
 * never closed" is greppable rather than invisible.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditPolicyWorkflowService {

    /** Section completion events. Must match workflow_step_sections.completion_event. */
    public static final String EV_DRAFTED         = "POLICY_DRAFTED";
    public static final String EV_CONTROLS_LINKED = "POLICY_CONTROLS_LINKED";
    public static final String EV_REVIEWED        = "POLICY_REVIEWED";
    public static final String EV_APPROVED        = "POLICY_APPROVED";

    private final StepInstanceRepository           stepInstanceRepository;
    private final TaskInstanceRepository           taskInstanceRepository;
    private final TaskSectionCompletionRepository  sectionCompletionRepository;
    private final TaskSectionCompletionService     sectionCompletionService;

    // ── Public hooks ────────────────────────────────────────────────────────

    /** Content written — fired when contentBody goes from blank to non-blank. */
    public void onDrafted(AuditPolicy policy, Long userId) {
        fire(policy, EV_DRAFTED, userId);
    }

    /** At least one control linked. Optional section — never blocks approval. */
    public void onControlLinked(AuditPolicy policy, Long userId) {
        fire(policy, EV_CONTROLS_LINKED, userId);
    }

    /**
     * Owner review recorded.
     *
     * On CHANGES_REQUESTED the section is deliberately NOT completed: the step
     * must stay open so the reviewer sees it again after the drafter resubmits.
     * Completing it would advance to approval with the changes unmade.
     */
    public void onReviewed(AuditPolicy policy, boolean changesRequested, Long userId, String remarks) {
        if (changesRequested) {
            log.info("[POLICY-WF] Changes requested, step left open | policyId={} remarks={}",
                    policy.getId(), remarks);
            return;
        }
        fire(policy, EV_REVIEWED, userId);
    }

    /** Approved — closes the final step and with it the workflow. */
    public void onApproved(AuditPolicy policy, Long userId) {
        fire(policy, EV_APPROVED, userId);
    }

    /**
     * Is this user the person the live workflow task is assigned to?
     *
     * Drives ui_actions.requires_assignment, which gates an action on
     * entity.isAssignedToCurrentUser. The policy endpoint never emitted that
     * field, so the gate silently passed for everyone — a flag that reads as a
     * restriction while restricting nothing is worse than no flag at all.
     *
     * This is what lets "Edit under review" belong to the reviewer holding the
     * task rather than to anyone who can see the policy.
     *
     * False when no workflow is running: with no task there is no assignee, so
     * an assignment-scoped action has no one to belong to.
     */
    public boolean isCurrentActor(AuditPolicy policy, Long userId) {
        if (policy == null || userId == null || policy.getWorkflowInstanceId() == null) return false;
        try {
            return liveTaskFor(policy.getWorkflowInstanceId())
                    .map(t -> userId.equals(t.getAssignedUserId()))
                    .orElse(false);
        } catch (Exception ex) {
            log.warn("[POLICY-WF] isCurrentActor failed | policyId={} | {}", policy.getId(), ex.getMessage());
            return false;
        }
    }

    // ── Internals ───────────────────────────────────────────────────────────

    @Transactional
    protected void fire(AuditPolicy policy, String completionEvent, Long userId) {
        if (policy == null || policy.getWorkflowInstanceId() == null) return;   // no workflow, nothing to do
        try {
            Optional<TaskInstance> task = liveTaskFor(policy.getWorkflowInstanceId());
            if (task.isEmpty()) {
                log.warn("[POLICY-WF] No live task | event={} policyId={} wfInstance={}",
                        completionEvent, policy.getId(), policy.getWorkflowInstanceId());
                return;
            }
            Long taskId = task.get().getId();

            var section = sectionCompletionRepository
                    .findByTaskInstanceIdAndSnapCompletionEvent(taskId, completionEvent)
                    .orElse(null);
            if (section == null) {
                // The step exists but carries no section for this event — the
                // blueprint is incomplete rather than the action being wrong.
                log.warn("[POLICY-WF] No section for event | event={} taskId={} policyId={}",
                        completionEvent, taskId, policy.getId());
                return;
            }
            if (section.isCompleted()) return;   // idempotent — completed is a primitive boolean

            sectionCompletionService.completeItemByRef(
                    taskId, section.getSnapSectionKey(), policy.getId(), userId);

            log.info("[POLICY-WF] Section completed | event={} taskId={} policyId={}",
                    completionEvent, taskId, policy.getId());

        } catch (Exception ex) {
            log.warn("[POLICY-WF] Completion failed (non-fatal) | event={} policyId={} | {}",
                    completionEvent, policy.getId(), ex.getMessage());
        }
    }

    /**
     * Tasks hang off STEP instances, not off the workflow instance — there is no
     * findByWorkflowInstanceId on TaskInstanceRepository, so the live task is
     * reached via the in-progress step.
     *
     * IN_PROGRESS first, then PENDING, matching AuditEngagementService. A step
     * opened but not yet picked up is still the right target; without the
     * PENDING fallback the very first action after a workflow starts finds
     * nothing, which is precisely when the drafter acts.
     */
    private Optional<TaskInstance> liveTaskFor(Long workflowInstanceId) {
        // StepStatus has no PENDING — the pre-work states are UNASSIGNED and
        // AWAITING_ASSIGNMENT. A step sitting in either is still the live step
        // and its task is the one to complete.
        List<StepInstance> steps = new java.util.ArrayList<>(stepInstanceRepository
                .findByWorkflowInstanceIdAndStatus(workflowInstanceId, StepStatus.IN_PROGRESS));
        if (steps.isEmpty()) {
            steps.addAll(stepInstanceRepository
                    .findByWorkflowInstanceIdAndStatus(workflowInstanceId, StepStatus.UNASSIGNED));
            steps.addAll(stepInstanceRepository
                    .findByWorkflowInstanceIdAndStatus(workflowInstanceId, StepStatus.AWAITING_ASSIGNMENT));
        }
        for (StepInstance si : steps) {
            var inProgress = taskInstanceRepository
                    .findByStepInstanceIdAndStatus(si.getId(), TaskStatus.IN_PROGRESS);
            if (!inProgress.isEmpty()) return Optional.of(inProgress.get(0));

            var pending = taskInstanceRepository
                    .findByStepInstanceIdAndStatus(si.getId(), TaskStatus.PENDING);
            if (!pending.isEmpty()) return Optional.of(pending.get(0));
        }
        return Optional.empty();
    }
}