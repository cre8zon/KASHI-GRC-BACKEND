package com.kashi.grc.assessment.workflow;

import com.kashi.grc.workflow.domain.*;
import com.kashi.grc.workflow.enums.*;
import com.kashi.grc.workflow.event.WorkflowEvent;
import com.kashi.grc.workflow.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Auto-approves ASSIGNER (coordinator) tasks that have no meaningful work to do
 * on the current step — so they don't sit as PENDING in the coordinator's inbox
 * and confuse reviewers about the workflow state.
 *
 * ── WHICH STEPS NEED THIS ────────────────────────────────────────────────────
 *
 * FILL steps (StepAction.FILL):
 *   The VRM coordinator already completed their work in the prior ASSIGN step.
 *   Their ASSIGNER task on the FILL step is purely informational — auto-approve it.
 *
 * CISO REVIEW/SUBMIT steps (steps whose name contains "CISO" or "Final Review"):
 *   The VRM coordinator has no work to do at the CISO review step either.
 *   The CISO (ACTOR task) does the work; the coordinator task should not appear
 *   in VRM's inbox as a live PENDING task.
 *
 * ── WHY NOT JUST DELETE THE COORDINATOR TASK ─────────────────────────────────
 *   The coordinator task is required for isStepApprovalSatisfied() to work correctly —
 *   the engine counts approvals including ASSIGNER tasks. Auto-approving it immediately
 *   satisfies the count without forcing the VRM to manually approve a task they
 *   don't need to act on.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VendorAssessmentStepListener {

    private final StepInstanceRepository     stepInstanceRepository;
    private final TaskInstanceRepository     taskInstanceRepository;
    private final WorkflowInstanceRepository workflowInstanceRepository;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onStepAdvanced(WorkflowEvent.StepAdvanced event) {
        StepInstance si = stepInstanceRepository.findById(event.stepInstanceId()).orElse(null);
        if (si == null) return;

        WorkflowInstance wi = workflowInstanceRepository.findById(event.workflowInstanceId()).orElse(null);
        if (wi == null || !"VENDOR".equals(wi.getEntityType())) return;

        // Determine whether this step has coordinator tasks that should be auto-approved.
        // Criteria:
        //   1. FILL steps — coordinator already done from prior ASSIGN step
        //   2. Steps whose name contains "CISO" or "Final" — vendor coordinator has no
        //      work to do during the CISO's review and submission step
        boolean shouldAutoApproveCoordinator = isFillStep(si) || isCisoReviewStep(si);
        if (!shouldAutoApproveCoordinator) return;

        List<TaskInstance> assignerTasks = taskInstanceRepository
                .findByStepInstanceId(si.getId())
                .stream()
                .filter(t -> t.getTaskRole() == TaskRole.ASSIGNER
                        && t.getStatus() == TaskStatus.PENDING)
                .toList();

        if (assignerTasks.isEmpty()) return;

        String reason = isCisoReviewStep(si)
                ? "Auto-approved — coordinator has no work at CISO review step"
                : "Auto-approved — coordinator role completed in assignment step";

        assignerTasks.forEach(t -> {
            t.setStatus(TaskStatus.APPROVED);
            t.setActedAt(LocalDateTime.now());
            t.setRemarks(reason);
        });
        taskInstanceRepository.saveAll(assignerTasks);

        log.info("[VENDOR-STEP] Auto-approved {} ASSIGNER task(s) on step '{}' (action={}) | instanceId={}",
                assignerTasks.size(), si.getSnapName(), si.getSnapStepAction(), wi.getId());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isFillStep(StepInstance si) {
        return si.getSnapStepAction() == StepAction.FILL;
    }

    /**
     * Detects the CISO review/submit step by name.
     * Matches any step whose name contains "ciso" or "final" (case-insensitive).
     * This avoids hardcoding a step order number — workflow blueprints may vary.
     */
    private boolean isCisoReviewStep(StepInstance si) {
        if (si.getSnapName() == null) return false;
        String nameLower = si.getSnapName().toLowerCase();
        return nameLower.contains("ciso") || nameLower.contains("final");
    }
}