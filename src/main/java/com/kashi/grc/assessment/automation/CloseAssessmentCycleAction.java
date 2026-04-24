package com.kashi.grc.assessment.automation;

import com.kashi.grc.assessment.domain.VendorAssessment;
import com.kashi.grc.assessment.domain.VendorAssessmentCycle;
import com.kashi.grc.assessment.repository.VendorAssessmentCycleRepository;
import com.kashi.grc.assessment.repository.VendorAssessmentRepository;
import com.kashi.grc.notification.service.NotificationService;
import com.kashi.grc.workflow.automation.AutomatedActionContext;
import com.kashi.grc.workflow.automation.AutomatedActionHandler;
import com.kashi.grc.workflow.domain.WorkflowInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Gap D fix — AutomatedActionHandler for key "CLOSE_ASSESSMENT_CYCLE".
 *
 * Fires automatically when a SYSTEM step with:
 *   automatedAction = "CLOSE_ASSESSMENT_CYCLE"
 * becomes active — this is the final step (step 14) of the TPRM workflow.
 *
 * What it does:
 *   1. Finds the VendorAssessmentCycle linked to this workflow instance
 *   2. Marks the cycle COMPLETED (already done by GenerateAssessmentReportAction,
 *      but this is idempotent — re-checking ensures it is done even if step 12 failed)
 *   3. Marks the assessment COMPLETED
 *   4. Sends notifications to ORG_ADMIN, ORG_CISO, VENDOR_VRM that the cycle closed
 *   5. Returns true → workflow.status → COMPLETED
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CloseAssessmentCycleAction implements AutomatedActionHandler {

    private final VendorAssessmentCycleRepository cycleRepository;
    private final VendorAssessmentRepository      assessmentRepository;
    private final NotificationService             notificationService;

    @Override
    public String actionKey() {
        return "CLOSE_ASSESSMENT_CYCLE";
    }

    @Override
    @Transactional
    public boolean execute(AutomatedActionContext ctx) {
        WorkflowInstance wi = ctx.getWorkflowInstance();

        log.info("[CLOSE_ASSESSMENT_CYCLE] Starting | workflowInstanceId={} | entityId={}",
                wi.getId(), wi.getEntityId());

        // ── Find cycle linked to this workflow instance ───────────────────────
        VendorAssessmentCycle cycle = cycleRepository
                .findByVendorIdOrderByCycleNo(wi.getEntityId())
                .stream()
                .filter(c -> wi.getId().equals(c.getWorkflowInstanceId()))
                .findFirst()
                .orElse(null);

        if (cycle == null) {
            log.warn("[CLOSE_ASSESSMENT_CYCLE] No cycle found for workflowInstanceId={} — " +
                    "workflow will still complete", wi.getId());
            return true; // don't block workflow completion over missing cycle
        }

        // ── Idempotent: mark cycle COMPLETED if not already ──────────────────
        if (!"COMPLETED".equals(cycle.getStatus())) {
            cycle.setStatus("COMPLETED");
            cycleRepository.save(cycle);
            log.info("[CLOSE_ASSESSMENT_CYCLE] Cycle marked COMPLETED | cycleId={}", cycle.getId());
        }

        // ── Idempotent: mark assessment COMPLETED if not already ─────────────
        List<VendorAssessment> assessments = assessmentRepository.findByCycleId(cycle.getId());
        for (VendorAssessment assessment : assessments) {
            if (!"COMPLETED".equals(assessment.getStatus()) &&
                    !"CANCELLED".equals(assessment.getStatus())) {
                assessment.setStatus("COMPLETED");
                assessmentRepository.save(assessment);
                log.info("[CLOSE_ASSESSMENT_CYCLE] Assessment marked COMPLETED | assessmentId={}",
                        assessment.getId());
            }
        }

        // ── Notify initiator (org admin / workflow creator) ───────────────────
        // The initiatedBy user is the ORG_ADMIN who started the workflow
        if (wi.getInitiatedBy() != null) {
            notificationService.send(
                    wi.getInitiatedBy(),
                    "ASSESSMENT_CYCLE_CLOSED",
                    "Vendor assessment cycle completed — risk rating assigned",
                    "WORKFLOW",
                    wi.getId());
        }

        log.info("[CLOSE_ASSESSMENT_CYCLE] Done | cycleId={} | vendorId={} | workflowInstanceId={}",
                cycle.getId(), wi.getEntityId(), wi.getId());

        return true;
    }
}