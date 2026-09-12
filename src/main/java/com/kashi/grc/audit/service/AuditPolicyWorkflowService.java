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
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    // ── Public hooks ────────────────────────────────────────────────────────

    /** Content written — fired when contentBody goes from blank to non-blank. */
    public boolean onDrafted(AuditPolicy policy, Long userId) {
        return fire(policy, EV_DRAFTED, userId);
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
    public boolean onReviewed(AuditPolicy policy, boolean changesRequested, Long userId, String remarks) {
        if (changesRequested) {
            log.info("[POLICY-WF] Changes requested, step left open | policyId={} remarks={}",
                    policy.getId(), remarks);
            // Changes requested deliberately leaves the step open, and the status
            // goes back to DRAFT — that is a valid outcome, not a failure.
            return true;
        }
        return fire(policy, EV_REVIEWED, userId);
    }

    /** Approved — closes the final step and with it the workflow. */
    public boolean onApproved(AuditPolicy policy, Long userId) {
        return fire(policy, EV_APPROVED, userId);
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
            // Pass userId so this asks "does THIS user hold a live task?" rather
            // than "is this user the holder of whichever task we happened to pick?".
            // On a step with two reviewers the old form returned the first task and
            // told the second reviewer they were not the actor.
            return liveTaskFor(policy.getWorkflowInstanceId(), userId)
                    .map(t -> userId.equals(t.getAssignedUserId()))
                    .orElse(false);
        } catch (Exception ex) {
            log.warn("[POLICY-WF] isCurrentActor failed | policyId={} | {}", policy.getId(), ex.getMessage());
            return false;
        }
    }

    // ── Internals ───────────────────────────────────────────────────────────

    @Transactional
    /**
     * @return true when the caller may advance the policy status — either the step
     *         completed, or there is no workflow to complete. False ONLY when a
     *         workflow exists and its step refused to move.
     *
     * This return value is the fix for status and workflow drifting apart. Failures
     * here were logged "non-fatal" and swallowed, so the controller advanced the
     * policy regardless and the buttons followed a status the workflow had not
     * reached. The step stayed open while the header offered the NEXT step's action.
     */
    protected boolean fire(AuditPolicy policy, String completionEvent, Long userId) {
        if (policy == null || policy.getWorkflowInstanceId() == null) return true;   // no workflow — nothing gates the status
        try {
            Optional<TaskInstance> task = liveTaskFor(policy.getWorkflowInstanceId(), userId);
            if (task.isEmpty()) {
                log.warn("[POLICY-WF] No live task | event={} policyId={} wfInstance={}",
                        completionEvent, policy.getId(), policy.getWorkflowInstanceId());
                return false;
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
                return false;
            }
            // Already done — a success, not a failure. Returning false here would
            // block the status on a retry or a double-click, which is exactly the
            // kind of false refusal the new gating must not introduce.
            if (section.isCompleted()) return true;

            // Complete the SECTION, not an item inside it.
            //
            // completeItemByRef looks up a TaskSectionItem and returns silently
            // when there is none — it logs "[CASE3-BY-REF] No item found … skipping".
            // Policy sections track no items (there is nothing to enumerate: one
            // policy, one body), so that call could never complete anything and the
            // step sat at 0/3 with no error anywhere.
            //
            // TaskSectionEvent is the item-free path: the listener matches on
            // snap_completion_event and marks the section itself done, which is
            // exactly what a section with tracks_items = 0 needs.
            eventPublisher.publishEvent(com.kashi.grc.workflow.event.TaskSectionEvent.sectionDone(
                    completionEvent, taskId, userId, "AUDIT_POLICY", policy.getId()));

            log.info("[POLICY-WF] Section completed | event={} taskId={} policyId={}",
                    completionEvent, taskId, policy.getId());
            return true;

        } catch (Exception ex) {
            // No longer swallowed. The caller decides what to do, and for policies
            // that means refusing to advance the status.
            log.warn("[POLICY-WF] Completion failed | event={} policyId={} | {}",
                    completionEvent, policy.getId(), ex.getMessage());
            return false;
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
    private Optional<TaskInstance> liveTaskFor(Long workflowInstanceId, Long userId) {
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
            // Consider IN_PROGRESS and PENDING TOGETHER, not one list then the other.
            //
            // Searching IN_PROGRESS first let task STATUS outrank OWNERSHIP: on a
            // two-reviewer step where the other reviewer's task had been reset to
            // IN_PROGRESS and the caller's was still PENDING, the owner check found
            // nothing in the first list and the "any actor" fallback returned the
            // other person's task — without ever looking at the list holding the
            // caller's own. The engine then refused it as assigned to someone else.
            //
            // One combined list means preferActor can apply its real priority:
            // the caller's own task first, whatever state it is in.
            List<TaskInstance> live = new java.util.ArrayList<>(taskInstanceRepository
                    .findByStepInstanceIdAndStatus(si.getId(), TaskStatus.IN_PROGRESS));
            live.addAll(taskInstanceRepository
                    .findByStepInstanceIdAndStatus(si.getId(), TaskStatus.PENDING));

            var actor = preferActor(live, userId);
            if (actor.isPresent()) return actor;
        }
        return Optional.empty();
    }

    /**
     * The ACTOR task, never the ASSIGNER one.
     *
     * A step can carry both: the actor does the work, the assigner (shown as
     * "coordinator") only routes it. This used to take list.get(0) and take
     * whichever the database returned first — on a step with both, that was the
     * coordinator, and the engine answered:
     *
     *   [WORKFLOW-ACTION] ASSIGNER task approved — step not advanced
     *
     * The section was marked complete and the policy status moved on, while the
     * step stayed IN_PROGRESS with the real reviewer task still PENDING. Half
     * the system advanced and half did not.
     *
     * Falls back to any task when no ACTOR exists, so a step configured with
     * only an assigner still completes rather than silently doing nothing.
     */
    private Optional<TaskInstance> preferActor(List<TaskInstance> tasks, Long userId) {
        if (tasks == null || tasks.isEmpty()) return Optional.empty();

        java.util.function.Predicate<TaskInstance> isActor =
                t -> t.getTaskRole() == com.kashi.grc.workflow.enums.TaskRole.ACTOR;

        // 1. The ACTING USER's own actor task.
        //
        // A ROLE_BASED step creates one task per resolved role, so step 2 has two:
        // one for each reviewer. Taking the first ACTOR gave the other person's
        // task, and the engine refused it —
        //   "Task action denied — taskId=1574 is assigned to 72 but was actioned by 6"
        // The section was marked complete, the auto-approve failed, and the step
        // stayed put while the policy status moved on.
        var own = tasks.stream()
                .filter(isActor)
                .filter(t -> userId != null && userId.equals(t.getAssignedUserId()))
                .findFirst();
        if (own.isPresent()) return own;

        // 2. Any actor task — a step with a single actor who is not the caller
        //    (an override, or a system-driven completion).
        var anyActor = tasks.stream().filter(isActor).findFirst();
        if (anyActor.isPresent()) return anyActor;

        // 3. Anything at all, so a step configured with only an assigner still
        //    completes rather than silently doing nothing.
        return Optional.of(tasks.get(0));
    }
}