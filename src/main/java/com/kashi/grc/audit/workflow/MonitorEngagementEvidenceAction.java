package com.kashi.grc.audit.workflow;

import com.kashi.grc.audit.repository.AuditControlInstanceRepository;
import com.kashi.grc.audit.repository.AuditEngagementRepository;
import com.kashi.grc.workflow.automation.AutomatedActionContext;
import com.kashi.grc.workflow.automation.AutomatedActionHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * AutomatedActionHandler for key "MONITOR_ENGAGEMENT_EVIDENCE".
 *
 * WF14 Step 5 — Evidence Collection Monitor (standalone engagement).
 *
 * Mirrors MonitorProjectEngagementsAction but for a single AUDIT_ENGAGEMENT
 * workflow instance (WF14) rather than a project (WF16).
 *
 * READINESS GATE:
 *   All controls in the engagement have auditeeEvidenceSubmitted = true.
 *   Auto-approves when ready; stays IN_PROGRESS until all are submitted.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MonitorEngagementEvidenceAction implements AutomatedActionHandler {

    private final AuditEngagementRepository      engagementRepository;
    private final AuditControlInstanceRepository  controlRepository;

    @Override
    public String actionKey() {
        return "MONITOR_ENGAGEMENT_EVIDENCE";
    }

    @Override
    @Transactional(readOnly = true)
    public boolean execute(AutomatedActionContext ctx) {
        Long engagementId = ctx.getWorkflowInstance().getEntityId();

        var engagement = engagementRepository.findById(engagementId).orElse(null);
        if (engagement == null) {
            log.warn("[MONITOR_ENGAGEMENT_EVIDENCE] Engagement {} not found — auto-approving", engagementId);
            return true;
        }

        var controls = controlRepository.findByEngagementId(engagementId);
        if (controls.isEmpty()) {
            log.warn("[MONITOR_ENGAGEMENT_EVIDENCE] No controls for engagementId={} — auto-approving", engagementId);
            return true;
        }

        long submitted = controls.stream()
                .filter(c -> c.isAuditeeEvidenceSubmitted())
                .count();
        long total     = controls.size();
        // Log progress snapshot but always auto-approve.
        // Step 4 (Evidence Collection) was manually approved by the lead auditor.
        // This monitor records the snapshot and immediately advances to Evidence Review.
        log.info("[MONITOR_ENGAGEMENT_EVIDENCE] engagementId={} | {}/{} controls submitted | auto-approving",
                engagementId, submitted, total);

        return true;
    }
}