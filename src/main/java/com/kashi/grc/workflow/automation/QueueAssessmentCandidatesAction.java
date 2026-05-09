package com.kashi.grc.workflow.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashi.grc.assessment.repository.AssessmentTemplateRepository;
import com.kashi.grc.vendor.domain.RiskTemplateMapping;
import com.kashi.grc.vendor.domain.Vendor;
import com.kashi.grc.vendor.domain.VendorTemplateSelection;
import com.kashi.grc.vendor.repository.RiskTemplateMappingRepository;
import com.kashi.grc.vendor.repository.VendorRepository;
import com.kashi.grc.vendor.repository.VendorTemplateSelectionRepository;
import com.kashi.grc.workflow.domain.WorkflowInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * AutomatedActionHandler for key "QUEUE_ASSESSMENT_CANDIDATES".
 *
 * PURPOSE:
 *   This is the first of two SYSTEM steps in the assessment selection flow.
 *   It looks up all templates mapped to the vendor's risk score and saves them
 *   to vendor_template_selection so the next manual step can present them to
 *   the ORG_ADMIN / ORG_OWNER for selection.
 *
 * BLUEPRINT USAGE:
 *   Add this as a SYSTEM step (automatedAction = "QUEUE_ASSESSMENT_CANDIDATES")
 *   immediately BEFORE a manual ORGANIZATION step with
 *   navKey = "vendor_assessment_select_template" and actorRoles = [ORG_ADMIN, ORG_OWNER].
 *   EXECUTE_ASSESSMENT comes after both.
 *
 *   Step order example:
 *     1. SYSTEM  — QUEUE_ASSESSMENT_CANDIDATES  (this action — finds candidates, always auto-approves)
 *     2. ORG     — Select Assessment Template    (navKey=vendor_assessment_select_template,
 *                                                 stepAction=FILL, actorRoles=[ORG_ADMIN, ORG_OWNER])
 *     3. SYSTEM  — EXECUTE_ASSESSMENT            (reads selectedTemplateId from vendor_template_selection)
 *
 * BEHAVIOUR:
 *   - Finds all RiskTemplateMapping rows whose range covers the vendor's current risk score.
 *   - Saves a VendorTemplateSelection row with all candidate templateIds.
 *   - If only 1 candidate exists, pre-fills selectedTemplateId so the SELECT step
 *     can auto-approve without requiring human interaction.
 *   - Single candidate: returns true (auto-approves). Multiple: returns false — step stays IN_PROGRESS,
 *     to the manual selection step.
 *
 * BACKWARD COMPATIBILITY:
 *   If the blueprint does NOT include this step, EXECUTE_ASSESSMENT falls back to
 *   its original findByScore() single-template logic — no change for existing blueprints.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueueAssessmentCandidatesAction implements AutomatedActionHandler {

    private final VendorRepository                   vendorRepository;
    private final RiskTemplateMappingRepository      mappingRepository;
    private final AssessmentTemplateRepository       templateRepository;
    private final VendorTemplateSelectionRepository  selectionRepository;
    private final ObjectMapper                       objectMapper;

    @Override
    public String actionKey() {
        return "QUEUE_ASSESSMENT_CANDIDATES";
    }

    @Override
    @Transactional
    public boolean execute(AutomatedActionContext ctx) {
        WorkflowInstance wi       = ctx.getWorkflowInstance();
        Long             tenantId = ctx.getTenantId();

        log.info("[QUEUE_CANDIDATES] Starting | workflowInstanceId={} | entityId={}",
                wi.getId(), wi.getEntityId());

        // ── Load vendor ───────────────────────────────────────────────────────
        Vendor vendor = vendorRepository.findById(wi.getEntityId()).orElse(null);
        if (vendor == null) {
            log.error("[QUEUE_CANDIDATES] Vendor not found | entityId={}", wi.getEntityId());
            return false;
        }

        // ── Guard: don't create a duplicate selection row ─────────────────────
        if (selectionRepository.findByWorkflowInstanceId(wi.getId()).isPresent()) {
            log.warn("[QUEUE_CANDIDATES] Selection row already exists for workflowInstanceId={} — skipping",
                    wi.getId());
            return true;
        }

        // ── Find all templates mapped for this vendor's risk score ────────────
        List<RiskTemplateMapping> candidates =
                mappingRepository.findAllByScore(vendor.getCurrentRiskScore());

        if (candidates.isEmpty()) {
            log.error("[QUEUE_CANDIDATES] No templates mapped for score={} | vendorId={}",
                    vendor.getCurrentRiskScore(), vendor.getId());
            return false;
        }

        List<Long> templateIds = candidates.stream()
                .map(RiskTemplateMapping::getTemplateId)
                .toList();

        String tierLabel = candidates.get(0).getTierLabel();

        // ── Serialize candidate IDs to JSON ───────────────────────────────────
        String candidateJson;
        try {
            candidateJson = objectMapper.writeValueAsString(templateIds);
        } catch (Exception e) {
            log.error("[QUEUE_CANDIDATES] Failed to serialize candidate templateIds | {}", e.getMessage());
            return false;
        }

        // ── Build selection row ───────────────────────────────────────────────
        VendorTemplateSelection selection = VendorTemplateSelection.builder()
                .workflowInstanceId(wi.getId())
                .stepInstanceId(ctx.getStepInstance().getId())
                .vendorId(vendor.getId())
                .tenantId(tenantId)
                .riskTierLabel(tierLabel)
                .candidateTemplateIds(candidateJson)
                // Pre-fill if only 1 candidate — no human step needed, step auto-approves
                .selectedTemplateId(candidates.size() == 1 ? templateIds.get(0) : null)
                .build();

        selectionRepository.save(selection);

        if (candidates.size() == 1) {
            log.info("[QUEUE_CANDIDATES] Single candidate — pre-selected templateId={} | workflowInstanceId={}",
                    templateIds.get(0), wi.getId());
            // Auto-approve: workflow advances straight to EXECUTE_ASSESSMENT
            return true;
        }

        log.info("[QUEUE_CANDIDATES] {} candidates saved — step stays IN_PROGRESS | " +
                        "tier={} | workflowInstanceId={} | awaiting ORG_ADMIN/ORG_OWNER selection in setup panel",
                candidates.size(), tierLabel, wi.getId());

        // Multiple candidates: step stays IN_PROGRESS.
        // VendorSetupBanner detects this and shows the template picker to ORG_ADMIN/ORG_OWNER.
        // Once they select, POST /v1/assessments/template-selection/select completes this step
        // and EXECUTE_ASSESSMENT fires on the next step.
        return false;
    }
}