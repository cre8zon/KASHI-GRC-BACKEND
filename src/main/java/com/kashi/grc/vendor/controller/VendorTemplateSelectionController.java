package com.kashi.grc.vendor.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashi.grc.assessment.domain.AssessmentTemplate;
import com.kashi.grc.assessment.repository.AssessmentTemplateRepository;
import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.vendor.domain.VendorTemplateSelection;
import com.kashi.grc.vendor.repository.VendorTemplateSelectionRepository;
import com.kashi.grc.workflow.domain.StepInstance;
import com.kashi.grc.workflow.enums.StepStatus;
import com.kashi.grc.workflow.repository.StepInstanceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import com.kashi.grc.workflow.service.WorkflowEngineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * VendorTemplateSelectionController
 *
 * Exposes the two endpoints the frontend's usePendingTemplateSelection hook needs:
 *
 *   GET  /v1/workflows/instances/{instanceId}/template-selection
 *     Called by VendorDetailPage on every render (React Query, staleTime=0).
 *     Returns the pending selection row so VendorSetupBanner can show the picker.
 *     Returns 404 when no pending row exists (normal — no multiple-template mapping).
 *
 *   POST /v1/workflows/instances/{instanceId}/template-selection
 *     Called when ORG_ADMIN / ORG_OWNER confirms their template choice.
 *     Sets selectedTemplateId on the row, then resumes the paused
 *     QUEUE_ASSESSMENT_CANDIDATES step so the workflow can advance to
 *     EXECUTE_ASSESSMENT.
 *
 * FLOW:
 *   1. QUEUE_ASSESSMENT_CANDIDATES fires, finds >1 candidates for the vendor's
 *      risk score, saves a VendorTemplateSelection row with selectedTemplateId=null,
 *      and returns false (step stays IN_PROGRESS — workflow paused).
 *   2. Frontend polls GET → receives candidates → VendorSetupBanner shows picker.
 *   3. Admin picks a template → frontend calls POST with { templateId }.
 *   4. This controller sets selectedTemplateId + calls completeSystemStepAndAdvance()
 *      → QUEUE step transitions to APPROVED → engine advances to EXECUTE_ASSESSMENT.
 *   5. EXECUTE_ASSESSMENT reads selectedTemplateId from the row and instantiates it.
 */
@Slf4j
@RestController
@RequestMapping("/v1/workflows/instances/{instanceId}/template-selection")
@Tag(name = "Vendor Template Selection",
        description = "Pending template choice for QUEUE_ASSESSMENT_CANDIDATES step")
@RequiredArgsConstructor
public class VendorTemplateSelectionController {

    private final VendorTemplateSelectionRepository selectionRepository;
    private final AssessmentTemplateRepository      templateRepository;
    private final com.kashi.grc.vendor.repository.RiskTemplateMappingRepository riskTemplateMappingRepository;
    private final WorkflowEngineService             workflowEngineService;
    private final StepInstanceRepository            stepInstanceRepository;
    private final UtilityService                    utilityService;
    private final ObjectMapper                      objectMapper;
    // Used to get a self-proxy so saveSelection() runs in its own committed
    // transaction before the workflow advance. If EXECUTE_ASSESSMENT fails,
    // the selection is still persisted and the admin doesn't need to re-pick.
    @Autowired
    private ApplicationContext applicationContext;

    // ── GET ───────────────────────────────────────────────────────────────────

    /**
     * Persists the template choice in its own committed transaction.
     * Called by confirm() before the workflow advance so that even if
     * EXECUTE_ASSESSMENT fails (e.g. template snapshotting error) the
     * selection row is not rolled back — the admin won't need to re-pick.
     */
    @Transactional
    public void saveSelection(VendorTemplateSelection sel, Long templateId, Long userId) {
        sel.setSelectedTemplateId(templateId);
        sel.setSelectedByUserId(userId);
        sel.setSelectedAt(java.time.LocalDateTime.now());
        selectionRepository.save(sel);
    }

    @GetMapping
    @Operation(summary = "Get pending template selection for a workflow instance — 404 if none")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPending(
            @PathVariable Long instanceId) {

        VendorTemplateSelection sel = selectionRepository
                .findByWorkflowInstanceId(instanceId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "VendorTemplateSelection (workflowInstanceId)", instanceId));

        // Parse candidate IDs from JSON column
        List<Long> candidateIds = parseCandidateIds(sel.getCandidateTemplateIds());

        // Hydrate candidate names + versions for the UI (batched — was one
        // templateRepository.findById() per candidate id in a loop).
        Map<Long, AssessmentTemplate> templatesById = candidateIds.isEmpty() ? Map.of()
                : templateRepository.findAllById(candidateIds).stream()
                  .collect(java.util.stream.Collectors.toMap(AssessmentTemplate::getId, t -> t));
        List<Map<String, Object>> candidates = candidateIds.stream()
                .map(templatesById::get)
                .filter(java.util.Objects::nonNull)
                .map(t -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("templateId", t.getId());
                    m.put("name",       t.getName());
                    m.put("version",    t.getVersion() != null ? t.getVersion() : 1);
                    return m;
                })
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

        boolean alreadySelected = sel.getSelectedTemplateId() != null;

        // ── EVERY BAND, FOR THE OVERRIDE ─────────────────────────────────────
        //
        // `candidates` above is what the vendor's SCORE maps to, and that is
        // the right default — the mapping exists so nobody has to judge it by
        // hand. But it is a default, not a rule: a vendor whose score lands in
        // LOW may still be handling regulated data, and the assessor who knows
        // that needs to be able to reach the CRITICAL questionnaire without an
        // admin editing the score bands underneath a live workflow.
        //
        // So the whole map is returned alongside, grouped by band, with the
        // mapped band marked. The UI shows candidates by default and this list
        // behind an explicit override control; the server enforces the same
        // split — see the permission check on confirm().
        List<com.kashi.grc.vendor.domain.RiskTemplateMapping> allMappings =
                riskTemplateMappingRepository.findByTenantIdIsNull();

        Set<Long> allTemplateIds = allMappings.stream()
                .map(com.kashi.grc.vendor.domain.RiskTemplateMapping::getTemplateId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        Map<Long, AssessmentTemplate> allTemplatesById = allTemplateIds.isEmpty() ? Map.of()
                : templateRepository.findAllById(allTemplateIds).stream()
                  .collect(java.util.stream.Collectors.toMap(AssessmentTemplate::getId, t -> t));

        // Ordered by score so the bands read LOW → CRITICAL rather than by id.
        List<Map<String, Object>> bands = allMappings.stream()
                .sorted(java.util.Comparator.comparing(
                        m -> m.getMinScore() == null ? java.math.BigDecimal.ZERO : m.getMinScore()))
                .map(m -> {
                    AssessmentTemplate t = allTemplatesById.get(m.getTemplateId());
                    if (t == null) return null;
                    Map<String, Object> bm = new java.util.LinkedHashMap<>();
                    bm.put("templateId", t.getId());
                    bm.put("name",       t.getName());
                    bm.put("version",    t.getVersion() != null ? t.getVersion() : 1);
                    bm.put("tierLabel",  m.getTierLabel());
                    bm.put("minScore",   m.getMinScore());
                    bm.put("maxScore",   m.getMaxScore());
                    // True for the templates the vendor's own score selected —
                    // the ones already in `candidates`. Lets the UI show the
                    // override list without losing which entries are the
                    // recommendation.
                    bm.put("mapped",     candidateIds.contains(t.getId()));
                    return bm;
                })
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("workflowInstanceId",  instanceId);
        body.put("riskTierLabel",       sel.getRiskTierLabel());
        body.put("candidates",          candidates);
        body.put("allBands",            bands);
        body.put("alreadySelected",     alreadySelected);
        if (alreadySelected) {
            body.put("selectedTemplateId", sel.getSelectedTemplateId());
        }

        log.debug("[TEMPLATE-SELECTION] GET | instanceId={} | candidates={} | alreadySelected={}",
                instanceId, candidates.size(), alreadySelected);

        return ResponseEntity.ok(ApiResponse.success(body));
    }

    // ── POST ──────────────────────────────────────────────────────────────────

    @PostMapping
    @Operation(summary = "Confirm template selection — resumes QUEUE_ASSESSMENT_CANDIDATES step")
    public ResponseEntity<ApiResponse<Map<String, Object>>> confirm(
            @PathVariable Long instanceId,
            @RequestBody SelectionRequest req) {

        Long userId = utilityService.getLoggedInDataContext().getId();

        if (req.getTemplateId() == null) {
            throw new BusinessException("TEMPLATE_ID_REQUIRED", "templateId is required");
        }

        // Load the pending selection row
        VendorTemplateSelection sel = selectionRepository
                .findByWorkflowInstanceId(instanceId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "VendorTemplateSelection (workflowInstanceId)", instanceId));

        // Guard: already selected — idempotent, just return success
        if (sel.getSelectedTemplateId() != null) {
            log.warn("[TEMPLATE-SELECTION] Already selected templateId={} for instanceId={} — skipping",
                    sel.getSelectedTemplateId(), instanceId);
            return ResponseEntity.ok(ApiResponse.success(Map.of(
                    "workflowInstanceId", instanceId,
                    "selectedTemplateId", sel.getSelectedTemplateId(),
                    "alreadySelected",    true
            )));
        }

        // ── THE CANDIDATE GUARD, AND THE WAY PAST IT ────────────────────────
        //
        // By default the chosen template must be one the vendor's score maps
        // to. That guard stays exactly as it was.
        //
        // An OVERRIDE steps outside it — picking a template from another band —
        // and is a different act with different consequences, so it needs three
        // things the default path does not: an explicit flag (nobody overrides
        // by accident), a permission (vendor.template_override, seeded by
        // sql/75), and a reason recorded in the step remarks, where it stays
        // attached to the workflow rather than in a log line.
        //
        // The template still has to EXIST and be mapped to some band. An
        // override is "use the CRITICAL questionnaire on this LOW vendor", not
        // "use any row in the templates table".
        List<Long> candidateIds = parseCandidateIds(sel.getCandidateTemplateIds());
        boolean isOverride = Boolean.TRUE.equals(req.getOverride())
                && !candidateIds.contains(req.getTemplateId());

        if (!candidateIds.contains(req.getTemplateId())) {
            if (!Boolean.TRUE.equals(req.getOverride())) {
                throw new BusinessException("INVALID_TEMPLATE_CHOICE",
                        "templateId=" + req.getTemplateId() +
                                " is not in the candidate list for this risk tier. " +
                                "Send override=true with a reason to select outside the mapped band.");
            }
            if (!hasOverridePermission()) {
                throw new BusinessException("OVERRIDE_NOT_PERMITTED",
                        "You do not have permission to select a template outside the mapped risk band.");
            }
            if (req.getOverrideReason() == null || req.getOverrideReason().isBlank()) {
                throw new BusinessException("OVERRIDE_REASON_REQUIRED",
                        "A reason is required when overriding the mapped risk band.");
            }
            boolean mappedSomewhere = riskTemplateMappingRepository.findByTenantIdIsNull().stream()
                    .anyMatch(m -> req.getTemplateId().equals(m.getTemplateId()));
            if (!mappedSomewhere) {
                throw new BusinessException("TEMPLATE_NOT_MAPPED",
                        "templateId=" + req.getTemplateId() + " is not mapped to any risk band.");
            }
        }

        // Verify the template still exists
        AssessmentTemplate template = templateRepository.findById(req.getTemplateId())
                .orElseThrow(() -> new ResourceNotFoundException("AssessmentTemplate", req.getTemplateId()));

        // Persist the choice in its own committed transaction so it survives
        // even if the subsequent workflow advance throws and rolls back.
        applicationContext.getBean(VendorTemplateSelectionController.class)
                .saveSelection(sel, req.getTemplateId(), userId);

        log.info("[TEMPLATE-SELECTION] Confirmed | instanceId={} | templateId={} | templateName='{}' | by={}",
                instanceId, req.getTemplateId(), template.getName(), userId);

        // Step 1: complete the paused QUEUE_ASSESSMENT_CANDIDATES step.
        // This auto-activates step 2 (Select Assessment Template).
        if (sel.getStepInstanceId() != null) {
            log.info("[TEMPLATE-SELECTION] Completing QUEUE step | stepInstanceId={} | instanceId={}",
                    sel.getStepInstanceId(), instanceId);
            workflowEngineService.completeSystemStepAndAdvance(
                    sel.getStepInstanceId(),
                    userId,
                    "Template selected by admin: " + template.getName() + " (id=" + req.getTemplateId() + ")"
            );
        } else {
            log.warn("[TEMPLATE-SELECTION] stepInstanceId is null on selection row id={} — " +
                    "cannot auto-advance step 1; advancing step 2 directly", sel.getId());
        }

        // Step 2: the 'Select Assessment Template' step is now IN_PROGRESS but has no
        // actor tasks (the selection was made via the banner UI, not a workflow task).
        // Find it and complete it immediately so EXECUTE_ASSESSMENT can fire.
        String selectionRemarks = isOverride
                ? "BAND OVERRIDE — admin selected " + template.getName()
                  + " (id=" + req.getTemplateId() + ") outside the mapped "
                  + sel.getRiskTierLabel() + " band. Reason: " + req.getOverrideReason()
                : "Admin selected template via setup panel: "
                  + template.getName() + " (id=" + req.getTemplateId() + ")";
        // Search both IN_PROGRESS and AWAITING_ASSIGNMENT — the engine sets
        // AWAITING_ASSIGNMENT on creation and transitions to IN_PROGRESS after
        // assignTasksForStep runs. For role-based steps with no roles the transition
        // may not happen, so we check both to be safe.
        List<StepInstance> activeSteps = new java.util.ArrayList<>();
        activeSteps.addAll(stepInstanceRepository.findByWorkflowInstanceIdAndStatus(instanceId, StepStatus.IN_PROGRESS));
        activeSteps.addAll(stepInstanceRepository.findByWorkflowInstanceIdAndStatus(instanceId, StepStatus.AWAITING_ASSIGNMENT));
        activeSteps.stream()
                .filter(si -> {
                    String name = si.getSnapName();
                    return name != null && name.toLowerCase().contains("select assessment template");
                })
                .findFirst()
                .ifPresentOrElse(
                        si -> {
                            log.info("[TEMPLATE-SELECTION] Completing SELECT step | stepInstanceId={} | instanceId={}",
                                    si.getId(), instanceId);
                            workflowEngineService.completeSystemStepAndAdvance(
                                    si.getId(), userId, selectionRemarks);
                        },
                        () -> log.warn("[TEMPLATE-SELECTION] No IN_PROGRESS 'Select Assessment Template' " +
                                "step found for instanceId={} — EXECUTE_ASSESSMENT may not fire", instanceId)
                );

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "workflowInstanceId", instanceId,
                "selectedTemplateId", req.getTemplateId(),
                "templateName",       template.getName(),
                "alreadySelected",    false
        )));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<Long> parseCandidateIds(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<Long>>() {});
        } catch (Exception e) {
            log.error("[TEMPLATE-SELECTION] Failed to parse candidateTemplateIds JSON: {}", json);
            return List.of();
        }
    }

    @Data
    public static class SelectionRequest {
        private Long templateId;
        /** Explicit opt-in to selecting outside the mapped band. */
        private Boolean override;
        /** Required when override is true — recorded in the step remarks. */
        private String overrideReason;
    }

    /**
     * Permission-based, not role-based: a renamed or newly added role that
     * holds vendor.template_override works without a code change here.
     */
    private boolean hasOverridePermission() {
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder
                        .getContext().getAuthentication();
        if (auth == null) return false;
        return auth.getAuthorities().stream()
                .anyMatch(a -> "vendor.template_override".equals(a.getAuthority()));
    }
}