package com.kashi.grc.audit.controller;

import com.kashi.grc.audit.domain.*;
import com.kashi.grc.audit.repository.*;
import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.workflow.dto.request.StartWorkflowRequest;
import com.kashi.grc.workflow.dto.response.WorkflowInstanceResponse;
import com.kashi.grc.workflow.repository.WorkflowRepository;
import com.kashi.grc.workflow.service.WorkflowEngineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "Audit Policies", description = "Audit policy library and runtime policy instance management")
public class AuditPolicyController {

    private final AuditPolicyRepository                       policyRepository;
    private final AuditPolicyControlMappingRepository         policyControlMappingRepository;
    private final com.kashi.grc.audit.repository.AuditControlRepository controlRepository;
    private final AuditPolicyInstanceRepository               policyInstanceRepository;
    private final AuditPolicyInstanceControlMappingRepository policyInstanceControlMappingRepository;
    private final UtilityService                              utilityService;
    private final WorkflowEngineService                       workflowEngineService;
    private final WorkflowRepository                          workflowRepository;
    private final com.kashi.grc.workflow.service.WorkflowAccessService workflowAccessService;

    // ── Library — Policies CRUD ───────────────────────────────────────────────

    @GetMapping("/v1/audit/library/policies")
    @Operation(summary = "List all policies in the library")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listPolicies(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();

        List<AuditPolicy> policies;
        if (search != null && !search.isBlank()) {
            policies = policyRepository.searchByTitle(tenantId, search);
        } else if (status != null && !status.isBlank()) {
            policies = policyRepository.findByTenantIdAndStatus(tenantId,
                    AuditPolicy.PolicyStatus.valueOf(status));
        } else {
            policies = policyRepository.findByTenantIdOrderByTitleAsc(tenantId);
        }

        return ResponseEntity.ok(ApiResponse.success(
                policies.stream().map(this::toPolicySummaryMap).collect(Collectors.toList())));
    }

    @GetMapping("/v1/audit/library/policies/{id}")
    @Operation(summary = "Get a policy with full content and control mappings")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPolicy(@PathVariable Long id) {
        AuditPolicy policy = policyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AuditPolicy", id));

        Map<String, Object> result = toPolicyDetailMap(policy);

        List<AuditPolicyControlMapping> mappings = policyControlMappingRepository.findByPolicyId(id);
        result.put("linkedControlCount", mappings.size());
        result.put("linkedControlMappings", mappings.stream().map(m -> {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("mappingId",   m.getId());
            mm.put("controlId",   m.getControlId());
            mm.put("mappingType", m.getMappingType());
            mm.put("mappingNote", m.getMappingNote());
            return mm;
        }).collect(Collectors.toList()));

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/v1/audit/library/policies")
    @Operation(summary = "Create a new policy (starts as DRAFT)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createPolicy(
            @RequestBody AuditPolicyRequest req) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        Long userId   = utilityService.getLoggedInDataContext().getId();

        String resolvedRef = req.getPolicyRef();
        if (resolvedRef == null || resolvedRef.isBlank()) {
            long base = policyRepository.countForTenant(tenantId) + 1;
            String candidate;
            do {
                candidate = String.format("POL-%03d", base++);
            } while (policyRepository.existsByPolicyRefAndTenantId(candidate, tenantId));
            resolvedRef = candidate;
        }

        AuditPolicy policy = AuditPolicy.builder()
                .title(req.getTitle())
                .policyRef(resolvedRef)
                .description(req.getDescription())
                .version(1)
                .contentType(req.getContentType() != null
                        ? AuditPolicy.ContentType.valueOf(req.getContentType())
                        : AuditPolicy.ContentType.RICH_TEXT)
                .contentBody(req.getContentBody())
                .externalUrl(req.getExternalUrl())
                .evidenceRecordId(req.getEvidenceRecordId())
                .status(AuditPolicy.PolicyStatus.DRAFT)
                .ownerId(req.getOwnerId() != null ? req.getOwnerId() : userId)
                .ownerTeam(req.getOwnerTeam())
                .reviewFrequencyMonths(req.getReviewFrequencyMonths() != null
                        ? req.getReviewFrequencyMonths() : 12)
                .nextReviewDate(req.getNextReviewDate() != null
                        ? LocalDate.parse(req.getNextReviewDate()) : null)
                .controlTags(req.getControlTags())
                .frameworkRefs(req.getFrameworkRefs())
                .createdBy(userId)
                .tenantId(tenantId)
                .build();

        policyRepository.save(policy);
        log.info("[AUDIT-POLICY] Created | id={} title={}", policy.getId(), policy.getTitle());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(Map.of("id", policy.getId(), "title", policy.getTitle())));
    }

    @PutMapping("/v1/audit/library/policies/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updatePolicy(
            @PathVariable Long id, @RequestBody AuditPolicyRequest req) {
        AuditPolicy policy = policyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AuditPolicy", id));

        if (req.getTitle() != null)        policy.setTitle(req.getTitle());
        if (req.getDescription() != null)  policy.setDescription(req.getDescription());
        if (req.getContentBody() != null)  policy.setContentBody(req.getContentBody());
        if (req.getExternalUrl() != null)  policy.setExternalUrl(req.getExternalUrl());
        if (req.getEvidenceRecordId() != null) policy.setEvidenceRecordId(req.getEvidenceRecordId());
        if (req.getOwnerId() != null)      policy.setOwnerId(req.getOwnerId());
        if (req.getOwnerTeam() != null)    policy.setOwnerTeam(req.getOwnerTeam());
        if (req.getControlTags() != null)  policy.setControlTags(req.getControlTags());
        if (req.getFrameworkRefs() != null) policy.setFrameworkRefs(req.getFrameworkRefs());
        if (req.getNextReviewDate() != null)
            policy.setNextReviewDate(LocalDate.parse(req.getNextReviewDate()));
        if (req.getReviewFrequencyMonths() != null)
            policy.setReviewFrequencyMonths(req.getReviewFrequencyMonths());

        policyRepository.save(policy);
        return ResponseEntity.ok(ApiResponse.success(toPolicySummaryMap(policy)));
    }

    @PostMapping("/v1/audit/library/policies/{id}/approve")
    @Operation(summary = "Approve a policy — DRAFT/UNDER_REVIEW → APPROVED, auto-starts workflow")
    public ResponseEntity<ApiResponse<Map<String, Object>>> approvePolicy(@PathVariable Long id) {
        var ctx     = utilityService.getLoggedInDataContext();
        Long userId   = ctx.getId();
        Long tenantId = ctx.getTenantId();

        AuditPolicy policy = policyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AuditPolicy", id));

        policy.setStatus(AuditPolicy.PolicyStatus.APPROVED);
        policy.setApprovedById(userId);
        policy.setApprovedAt(LocalDateTime.now());

        if (policy.getNextReviewDate() == null) {
            policy.setNextReviewDate(LocalDate.now().plusMonths(
                    policy.getReviewFrequencyMonths() != null
                            ? policy.getReviewFrequencyMonths() : 12));
        }

        if (policy.getWorkflowInstanceId() == null) {
            startPolicyWorkflowIfConfigured(policy, tenantId, userId);
        }

        policyRepository.save(policy);
        log.info("[AUDIT-POLICY] Approved | id={} | workflowInstanceId={}",
                id, policy.getWorkflowInstanceId());
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("id", id, "status", "APPROVED",
                        "workflowInstanceId", policy.getWorkflowInstanceId())));
    }

    @PostMapping("/v1/audit/library/policies/{id}/send-for-review")
    @Operation(summary = "Send for review — DRAFT → UNDER_REVIEW, auto-starts workflow")
    public ResponseEntity<ApiResponse<Map<String, Object>>> sendForReview(@PathVariable Long id) {
        var ctx     = utilityService.getLoggedInDataContext();
        Long userId   = ctx.getId();
        Long tenantId = ctx.getTenantId();

        AuditPolicy policy = policyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AuditPolicy", id));

        if (policy.getStatus() != AuditPolicy.PolicyStatus.DRAFT) {
            throw new BusinessException("INVALID_STATUS",
                    "Only DRAFT policies can be sent for review",
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
        }

        policy.setStatus(AuditPolicy.PolicyStatus.UNDER_REVIEW);

        if (policy.getWorkflowInstanceId() == null) {
            startPolicyWorkflowIfConfigured(policy, tenantId, userId);
        }

        policyRepository.save(policy);
        log.info("[AUDIT-POLICY] Sent for review | id={} | workflowInstanceId={}",
                id, policy.getWorkflowInstanceId());
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("id", id, "status", policy.getStatus(),
                        "workflowInstanceId", policy.getWorkflowInstanceId())));
    }

    @PostMapping("/v1/audit/library/policies/{id}/deprecate")
    @Operation(summary = "Deprecate a policy — APPROVED → DEPRECATED")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deprecatePolicy(@PathVariable Long id) {
        AuditPolicy policy = policyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AuditPolicy", id));
        policy.setStatus(AuditPolicy.PolicyStatus.DEPRECATED);
        policyRepository.save(policy);
        return ResponseEntity.ok(ApiResponse.success(Map.of("id", id, "status", "DEPRECATED")));
    }

    @PostMapping("/v1/audit/library/policies/{id}/new-version")
    @Operation(summary = "Create a new draft version of an APPROVED policy — copies all content, increments version, links previousVersionId")
    public ResponseEntity<ApiResponse<Map<String, Object>>> newVersion(@PathVariable Long id) {
        Long userId   = utilityService.getLoggedInDataContext().getId();
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();

        AuditPolicy source = policyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AuditPolicy", id));

        if (source.getStatus() != AuditPolicy.PolicyStatus.APPROVED) {
            throw new BusinessException("INVALID_STATUS",
                    "Only APPROVED policies can be versioned. Use Save Draft for DRAFT policies.",
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
        }

        AuditPolicy newVersion = AuditPolicy.builder()
                .title(source.getTitle())
                .policyRef(source.getPolicyRef())
                .description(source.getDescription())
                .version(source.getVersion() + 1)
                .previousVersionId(source.getId())
                .contentType(source.getContentType())
                .contentBody(source.getContentBody())
                .externalUrl(source.getExternalUrl())
                .evidenceRecordId(source.getEvidenceRecordId())
                .status(AuditPolicy.PolicyStatus.DRAFT)
                .ownerId(source.getOwnerId())
                .ownerTeam(source.getOwnerTeam())
                .reviewFrequencyMonths(source.getReviewFrequencyMonths())
                .controlTags(source.getControlTags())
                .frameworkRefs(source.getFrameworkRefs())
                .createdBy(userId)
                .tenantId(tenantId)
                .build();

        policyRepository.save(newVersion);
        log.info("[AUDIT-POLICY] New version created | sourceId={} newId={} version={}",
                source.getId(), newVersion.getId(), newVersion.getVersion());
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
                .body(ApiResponse.success(Map.of(
                        "id",      newVersion.getId(),
                        "version", newVersion.getVersion(),
                        "status",  "DRAFT")));
    }

    @DeleteMapping("/v1/audit/library/policies")
    @Transactional
    @Operation(summary = "Bulk delete policies by ID list")
    public ResponseEntity<ApiResponse<Map<String, Object>>> bulkDeletePolicies(
            @RequestParam List<Long> ids) {
        int deleted = 0;
        for (Long id : ids) {
            if (policyRepository.existsById(id)) {
                policyControlMappingRepository.findByPolicyId(id)
                        .forEach(policyControlMappingRepository::delete);
                policyRepository.deleteById(id);
                deleted++;
            }
        }
        log.info("[AUDIT-LIBRARY] Bulk deleted {} policies", deleted);
        return ResponseEntity.ok(ApiResponse.success(Map.of("deleted", deleted)));
    }

    @DeleteMapping("/v1/audit/library/policies/{id}")
    public ResponseEntity<ApiResponse<Void>> deletePolicy(@PathVariable Long id) {
        policyControlMappingRepository.deleteByPolicyId(id);
        policyRepository.deleteById(id);
        log.info("[AUDIT-POLICY] Deleted id={}", id);
        return ResponseEntity.ok(ApiResponse.success());
    }

    // ── Library — Control ↔ Policy mappings ───────────────────────────────────

    @GetMapping("/v1/audit/library/controls/{controlId}/policies")
    @Operation(summary = "List policies mapped to a library control")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listControlPolicies(
            @PathVariable Long controlId) {
        List<AuditPolicyControlMapping> mappings =
                policyControlMappingRepository.findByControlId(controlId);

        List<Map<String, Object>> result = mappings.stream().map(m -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("mappingId",   m.getId());
            row.put("policyId",    m.getPolicyId());
            row.put("controlId",   m.getControlId());
            row.put("mappingType", m.getMappingType());
            row.put("mappingNote", m.getMappingNote());
            policyRepository.findById(m.getPolicyId()).ifPresent(p -> {
                row.put("policyTitle",    p.getTitle());
                row.put("policyRef",      p.getPolicyRef());
                row.put("policyVersion",  p.getVersion());
                row.put("policyStatus",   p.getStatus());
                row.put("nextReviewDate", p.getNextReviewDate());
            });
            return row;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/v1/audit/library/policies/{policyId}/controls")
    @Operation(summary = "List controls mapped to a library policy")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listPolicyControls(
            @PathVariable Long policyId) {
        List<AuditPolicyControlMapping> mappings =
                policyControlMappingRepository.findByPolicyId(policyId);

        List<Map<String, Object>> result = mappings.stream().map(m -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("mappingId",   m.getId());
            row.put("policyId",    m.getPolicyId());
            row.put("controlId",   m.getControlId());
            row.put("mappingType", m.getMappingType());
            row.put("mappingNote", m.getMappingNote());
            controlRepository.findById(m.getControlId()).ifPresent(c -> {
                row.put("controlName", c.getName());
                row.put("controlCode", c.getControlCode());
                row.put("controlTag",  c.getControlTag());
                row.put("frameworkRef",c.getFrameworkRef());
            });
            return row;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/v1/audit/library/controls/{controlId}/policies/{policyId}")
    @Operation(summary = "Link a policy to a library control")
    public ResponseEntity<ApiResponse<Map<String, Object>>> linkControlPolicy(
            @PathVariable Long controlId, @PathVariable Long policyId,
            @RequestParam(defaultValue = "DIRECT") String mappingType,
            @RequestParam(required = false) String note) {

        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        Long userId   = utilityService.getLoggedInDataContext().getId();

        AuditPolicyControlMapping mapping = policyControlMappingRepository
                .findByPolicyIdAndControlId(policyId, controlId)
                .orElseGet(() -> AuditPolicyControlMapping.builder()
                        .policyId(policyId).controlId(controlId)
                        .tenantId(tenantId).createdBy(userId).build());

        mapping.setMappingType(AuditPolicyControlMapping.MappingType.valueOf(mappingType));
        if (note != null) mapping.setMappingNote(note);
        policyControlMappingRepository.save(mapping);

        return ResponseEntity.ok(ApiResponse.success(
                Map.of("mappingId", mapping.getId(), "policyId", policyId, "controlId", controlId)));
    }

    @DeleteMapping("/v1/audit/library/controls/{controlId}/policies/{policyId}")
    public ResponseEntity<ApiResponse<Void>> unlinkControlPolicy(
            @PathVariable Long controlId, @PathVariable Long policyId) {
        policyControlMappingRepository.findByPolicyIdAndControlId(policyId, controlId)
                .ifPresent(policyControlMappingRepository::delete);
        return ResponseEntity.ok(ApiResponse.success());
    }

    // ── Runtime — Policy instances (per engagement) ───────────────────────────

    @GetMapping("/v1/audit/engagements/{engagementId}/policies")
    @Operation(summary = "List policy instances for an engagement")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listPolicyInstances(
            @PathVariable Long engagementId) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        List<AuditPolicyInstance> instances =
                policyInstanceRepository.findByEngagementIdAndTenantId(engagementId, tenantId);
        return ResponseEntity.ok(ApiResponse.success(
                instances.stream().map(this::toPolicyInstanceMap).collect(Collectors.toList())));
    }

    @GetMapping("/v1/audit/engagements/{engagementId}/policies/{policyInstanceId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPolicyInstance(
            @PathVariable Long engagementId, @PathVariable Long policyInstanceId) {
        AuditPolicyInstance instance = policyInstanceRepository.findById(policyInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditPolicyInstance", policyInstanceId));
        return ResponseEntity.ok(ApiResponse.success(toPolicyInstanceMap(instance)));
    }

    @PutMapping("/v1/audit/engagements/{engagementId}/policies/{policyInstanceId}/review")
    @Operation(summary = "Auditor reviews a policy instance — records adequacy conclusion")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reviewPolicyInstance(
            @PathVariable Long engagementId,
            @PathVariable Long policyInstanceId,
            @RequestBody PolicyReviewRequest req) {

        Long userId = utilityService.getLoggedInDataContext().getId();

        // SECURITY: recording an adequacy conclusion is an AUDITOR action. Gate it
        // on audit:policy:review resolved for this engagement's workflow — an
        // auditee (or anyone lacking the permission) must not be able to review.
        var reviewer = utilityService.getLoggedInUserWithRolesAndPermissions();
        var access   = workflowAccessService.resolveForModule(reviewer, "AUDIT_ENGAGEMENT", engagementId);
        java.util.List<String> perms = access != null ? access.getPermissions() : java.util.List.of();
        if (!perms.contains("audit:policy:review")) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("FORBIDDEN",
                            "You do not have permission to review this policy."));
        }

        AuditPolicyInstance instance = policyInstanceRepository.findById(policyInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditPolicyInstance", policyInstanceId));

        instance.setReviewResult(AuditPolicyInstance.ReviewResult.valueOf(req.getReviewResult()));
        instance.setAuditorNotes(req.getAuditorNotes());
        instance.setReviewedById(userId);
        instance.setReviewedAt(LocalDateTime.now());
        policyInstanceRepository.save(instance);

        log.info("[AUDIT-POLICY] Review recorded | instanceId={} result={}",
                policyInstanceId, req.getReviewResult());
        return ResponseEntity.ok(ApiResponse.success(toPolicyInstanceMap(instance)));
    }

    @GetMapping("/v1/audit/engagements/{engagementId}/controls/{controlInstanceId}/policies")
    @Operation(summary = "List policy instances covering a specific control instance")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listControlInstancePolicies(
            @PathVariable Long engagementId, @PathVariable Long controlInstanceId) {

        List<Long> policyInstanceIds = policyInstanceControlMappingRepository
                .findPolicyInstanceIdsByControlInstanceId(controlInstanceId);

        List<Map<String, Object>> result = policyInstanceRepository.findAllById(policyInstanceIds)
                .stream()
                .map(pi -> {
                    Map<String, Object> row = toPolicyInstanceMap(pi);
                    policyInstanceControlMappingRepository
                            .findByPolicyInstanceIdAndControlInstanceId(pi.getId(), controlInstanceId)
                            .ifPresent(m -> {
                                row.put("mappingType",        m.getMappingTypeSnapshot());
                                row.put("mappingNote",        m.getMappingNoteSnapshot());
                                row.put("reviewContribution", m.getReviewContribution());
                            });
                    return row;
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // ── Private: workflow auto-start ──────────────────────────────────────────

    /**
     * Attempts to auto-start the AUDIT_POLICY workflow blueprint for this policy.
     * Uses WorkflowRepository.findByTenantIdIsNullAndEntityTypeAndIsActiveTrue()
     * to find a global (tenant-agnostic) AUDIT_POLICY blueprint.
     * If no blueprint is configured, silently skips — policy still saves.
     */
    private void startPolicyWorkflowIfConfigured(AuditPolicy policy, Long tenantId, Long startedBy) {
        try {
            var blueprints = workflowRepository
                    .findByTenantIdIsNullAndEntityTypeAndIsActiveTrue("AUDIT_POLICY");

            if (blueprints.isEmpty()) {
                log.debug("[AUDIT-POLICY] No active AUDIT_POLICY blueprint found — skipping auto-start");
                return;
            }

            Long workflowId = blueprints.get(0).getId();

            StartWorkflowRequest req = new StartWorkflowRequest();
            req.setWorkflowId(workflowId);
            req.setEntityType("AUDIT_POLICY");
            req.setEntityId(policy.getId());
            // tenantId and startedBy are passed as separate params to startWorkflow()
            // StartWorkflowRequest has no setInitiatorId/setTenantId fields

            WorkflowInstanceResponse wf = workflowEngineService.startWorkflow(req, tenantId, startedBy);
            policy.setWorkflowInstanceId(wf.getId());
            log.info("[AUDIT-POLICY] Workflow started | policyId={} | instanceId={}",
                    policy.getId(), wf.getId());
        } catch (Exception ex) {
            log.warn("[AUDIT-POLICY] Workflow auto-start failed (non-fatal) | policyId={} | {}",
                    policy.getId(), ex.getMessage());
        }
    }

    // ── Serializers ───────────────────────────────────────────────────────────

    private Map<String, Object> toPolicySummaryMap(AuditPolicy p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",                    p.getId());
        m.put("title",                 p.getTitle());
        m.put("policyRef",             p.getPolicyRef());
        m.put("description",           p.getDescription());
        m.put("version",               p.getVersion());
        m.put("status",                p.getStatus() != null ? p.getStatus().name() : null);
        m.put("contentType",           p.getContentType() != null ? p.getContentType().name() : null);
        m.put("ownerId",               p.getOwnerId());
        m.put("ownerTeam",             p.getOwnerTeam());
        m.put("approvedAt",            p.getApprovedAt());
        m.put("effectiveDate",         p.getEffectiveDate());
        m.put("nextReviewDate",        p.getNextReviewDate());
        m.put("reviewFrequencyMonths", p.getReviewFrequencyMonths());
        m.put("controlTags",           p.getControlTags());
        m.put("frameworkRefs",         p.getFrameworkRefs());
        m.put("tenantId",              p.getTenantId());
        m.put("createdAt",             p.getCreatedAt());
        return m;
    }

    private Map<String, Object> toPolicyDetailMap(AuditPolicy p) {
        Map<String, Object> m = toPolicySummaryMap(p);
        m.put("contentBody",        p.getContentBody());
        m.put("externalUrl",        p.getExternalUrl());
        m.put("evidenceRecordId",   p.getEvidenceRecordId());
        m.put("approvedById",       p.getApprovedById());
        m.put("workflowInstanceId", p.getWorkflowInstanceId());
        m.put("previousVersionId",  p.getPreviousVersionId());
        return m;
    }

    private Map<String, Object> toPolicyInstanceMap(AuditPolicyInstance p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",                       p.getId());
        m.put("originalPolicyId",         p.getOriginalPolicyId());
        m.put("engagementId",             p.getEngagementId());
        m.put("titleSnapshot",            p.getTitleSnapshot());
        m.put("policyRefSnapshot",        p.getPolicyRefSnapshot());
        m.put("versionSnapshot",          p.getVersionSnapshot());
        m.put("descriptionSnapshot",      p.getDescriptionSnapshot());
        m.put("contentTypeSnapshot",      p.getContentTypeSnapshot());
        m.put("externalUrlSnapshot",      p.getExternalUrlSnapshot());
        m.put("evidenceRecordIdSnapshot", p.getEvidenceRecordIdSnapshot());
        m.put("ownerIdSnapshot",          p.getOwnerIdSnapshot());
        m.put("policyStatusSnapshot",     p.getPolicyStatusSnapshot());
        m.put("approvedAtSnapshot",       p.getApprovedAtSnapshot());
        m.put("effectiveDateSnapshot",    p.getEffectiveDateSnapshot());
        m.put("nextReviewDateSnapshot",   p.getNextReviewDateSnapshot());
        m.put("controlTagsSnapshot",      p.getControlTagsSnapshot());
        m.put("reviewResult",             p.getReviewResult());
        m.put("reviewedById",             p.getReviewedById());
        m.put("reviewedAt",               p.getReviewedAt());
        m.put("auditorNotes",             p.getAuditorNotes());
        m.put("snapshottedAt",            p.getSnapshottedAt());
        return m;
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    @Data
    public static class AuditPolicyRequest {
        @NotBlank private String title;
        private String  policyRef;
        private String  description;
        private String  contentType;
        private String  contentBody;
        private String  externalUrl;
        private Long    evidenceRecordId;
        private Long    ownerId;
        private String  ownerTeam;
        private Integer reviewFrequencyMonths;
        private String  nextReviewDate;
        private String  controlTags;
        private String  frameworkRefs;
    }

    @Data
    public static class PolicyReviewRequest {
        @NotBlank private String reviewResult;
        private String auditorNotes;
    }
}