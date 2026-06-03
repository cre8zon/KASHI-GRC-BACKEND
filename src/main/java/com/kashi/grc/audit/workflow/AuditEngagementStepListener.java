package com.kashi.grc.audit.workflow;

import com.kashi.grc.audit.domain.AuditEngagement;
import com.kashi.grc.audit.repository.AuditEngagementRepository;
import com.kashi.grc.workflow.event.WorkflowEvent;
import com.kashi.grc.workflow.service.WorkflowEngineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * AuditEngagementStepListener — domain side-effects for AUDIT_ENGAGEMENT workflow events.
 *
 * ── Part A: Auto-approve ASSIGNER tasks on FILL steps ─────────────────────────
 * When a FILL step activates (e.g. Evidence Collection), the lead auditor (ASSIGNER)
 * has no work to do — auditees do the uploading. Without auto-approval, the
 * lead auditor sees a permanent PENDING task in their inbox for 30 days.
 *
 * Only fires when the blueprint step has autoApproveAssignerOnFill=true.
 * Mirrors VendorAssessmentStepListener exactly.
 *
 * ── Part B: Auto-close engagement on workflow completion ──────────────────────
 * When the 9-step blueprint completes (Step 9 APPROVE approved), the workflow
 * engine fires WorkflowCompleted. This listener catches it, finds the engagement,
 * and sets status=CLOSED + completedAt=now().
 *
 * Without this, the workflow completes but the engagement stays on DRAFT_REPORT
 * forever — the Screen Designer action button on Step 9 cannot close it because
 * the APPROVE button calls the workflow engine directly, not the engagement endpoint.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditEngagementStepListener {

    private final WorkflowEngineService      workflowEngineService;
    private final AuditEngagementRepository  engagementRepository;

    // ── Part A: auto-approve assigner tasks on FILL steps ────────────────────

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onStepAdvanced(WorkflowEvent.StepAdvanced event) {
        // Handled generically by WorkflowEngineService.autoApproveAssignerTasks()
        // if the step has autoApproveAssignerOnFill = true.
        // This listener only needs to call it for AUDIT_ENGAGEMENT entity types.
        // The autoApproveAssignerOnFill flag is read from the snapshotted step config.
        // Nothing to do here — autoApproveAssignerTasks() is called from engine directly
        // when the flag is set. Left as hook point for future engagement-specific logic.
        log.debug("[AUDIT-ENG-LISTENER] StepAdvanced | instanceId={} | step='{}'",
                event.workflowInstanceId(), event.stepName());
    }

    // ── Part B: auto-close engagement on workflow completion ──────────────────

    /**
     * When the engagement workflow completes (all steps approved), automatically
     * close the engagement. Uses AFTER_COMMIT so the workflow instance is
     * definitely in COMPLETED state before we read it.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onWorkflowCompleted(WorkflowEvent.WorkflowCompleted event) {
        if (!"AUDIT_ENGAGEMENT".equals(event.entityType())) return;

        Long engagementId = event.entityId();

        engagementRepository.findById(engagementId).ifPresentOrElse(engagement -> {
            if (engagement.getStatus() == AuditEngagement.Status.CLOSED
                    || engagement.getStatus() == AuditEngagement.Status.CANCELLED) {
                log.info("[AUDIT-ENG-LISTENER] Engagement already {} — skipping auto-close | id={}",
                        engagement.getStatus(), engagementId);
                return;
            }

            engagement.setStatus(AuditEngagement.Status.CLOSED);
            engagement.setCompletedAt(java.time.LocalDateTime.now());
            engagementRepository.save(engagement);

            log.info("[AUDIT-ENG-LISTENER] Auto-closed engagement on workflow completion | " +
                            "engagementId={} | workflowInstanceId={}",
                    engagementId, event.workflowInstanceId());
        }, () -> log.warn("[AUDIT-ENG-LISTENER] Engagement not found for auto-close | id={}",
                engagementId));
    }
}