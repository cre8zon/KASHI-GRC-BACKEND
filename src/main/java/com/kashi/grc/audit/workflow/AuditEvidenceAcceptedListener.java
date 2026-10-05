package com.kashi.grc.audit.workflow;

import com.kashi.grc.audit.service.AuditEngagementService;
import com.kashi.grc.evidence.event.EvidenceLinkAcceptedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Accepted (or integration-verified) linked evidence on an audit control counts
 * as that control's evidence being submitted — see
 * AuditEngagementService.recordAcceptedEvidence. Never fails the acceptance.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditEvidenceAcceptedListener {

    private final AuditEngagementService engagementService;

    // After the acceptance commits, in a transaction of its own: a problem here
    // can never roll back the reviewer's decision. fallbackExecution covers a
    // publisher that runs without a transaction.
    /**
     * A document reused onto a control (REFERENCE link) submits that control's
     * evidence, as the person who reused it — through the normal submit, so its
     * access check, notifications, delegation closing and checklist apply. A
     * person who may not submit this control's evidence just leaves the
     * reference attached, as before.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onReused(com.kashi.grc.document.event.DocumentReusedEvent event) {
        if (!"AUDIT_CONTROL_INSTANCE".equals(event.entityType()) || event.entityId() == null || event.reusedBy() == null) return;
        try {
            engagementService.submitReusedEvidence(event.entityId(), event.reusedBy(), event.tenantId());
        } catch (RuntimeException e) {
            log.info("[AUDIT-EVIDENCE] Reused document left as a reference on control {} (not submitted): {}",
                    event.entityId(), e.getMessage());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAccepted(EvidenceLinkAcceptedEvent event) {
        if (!"AUDIT_CONTROL_INSTANCE".equals(event.targetEntityType()) || event.targetEntityId() == null) return;
        try {
            engagementService.recordAcceptedEvidence(event.targetEntityId(), event.acceptedBy());
        } catch (RuntimeException e) {
            log.warn("[AUDIT-EVIDENCE] Could not record accepted evidence on control {} (non-fatal): {}",
                    event.targetEntityId(), e.getMessage());
        }
    }
}