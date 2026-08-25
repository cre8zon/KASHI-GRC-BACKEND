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
    private final com.kashi.grc.tenant.repository.TenantRepository tenantRepository;
    private final com.kashi.grc.audit.service.AuditLibraryCacheService libraryCache;
    private final com.kashi.grc.audit.csv.AuditCsvImportExtension csvImportExtension;
    private final com.kashi.grc.audit.service.AuditPolicyWorkflowService policyWorkflow;
    private final com.kashi.grc.audit.service.AuditPolicyBulkAdoptService bulkAdoptService;
    private final com.kashi.grc.audit.service.PolicyVariableResolver policyVariables;
    /** Optional: KafkaEventPublisher is @ConditionalOnProperty. */
    private final org.springframework.beans.factory.ObjectProvider<com.kashi.grc.common.kafka.KafkaEventPublisher> kafkaPublisher;
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
            @RequestParam(required = false) String status,
            /** GLOBAL | ORG — omit for both. Lets the list separate the platform
             *  library from the tenant's own copies, which otherwise interleave
             *  alphabetically and are hard to tell apart at 40+ rows. */
            @RequestParam(required = false) String origin) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();

        // Projection, not entities. This endpoint used to load full AuditPolicy
        // rows — content_body included, the whole policy document as HTML — for
        // every policy, then map them down to a summary that discards the content.
        // 39 policies took 8-10 seconds for a screen with eight short columns, and
        // the perf log showed only 3 queries: the cost was the payload, not the
        // round trips. findSummariesForTenant selects the columns explicitly so the
        // document never leaves MySQL.
        // Cached per tenant + filter combination. Status parsing and the
        // projection both live in the cache service so a hit does neither.
        var cached = libraryCache.policyList(tenantId, search, status, origin);

        // `editable` is added HERE, not in the cache. It derives from
        // isSystemUser(), a property of the USER, while the cache key is per
        // TENANT — caching it would serve a platform admin's "true" to an org
        // user in the same tenant. Copied rather than mutated: the cached maps
        // are shared, and writing into them would poison the entry for the next
        // caller (and, on a local cache, permanently).
        boolean sysUser = utilityService.isSystemUser();
        List<Map<String, Object>> out = cached.stream().map(m -> {
            Map<String, Object> copy = new java.util.LinkedHashMap<>(m);
            copy.put("editable", !"GLOBAL".equals(m.get("origin")) || sysUser);
            return copy;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(out));
    }

    @GetMapping("/v1/audit/library/policies/{id}")
    @Operation(summary = "Get a policy with full content and control mappings")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPolicy(@PathVariable Long id) {
        AuditPolicy policy = policyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AuditPolicy", id));
        requireReadablePolicy(policy);   // global policies stay visible to every tenant

        Map<String, Object> result = toPolicyDetailMap(policy);

        // Same leak as listControlPolicies: a GLOBAL policy carries the mappings of
        // every tenant that linked it to one of their controls, so returning them all
        // showed each client which controls the others had mapped it to.
        boolean sysUser  = utilityService.isSystemUser();
        Long callerTid   = utilityService.getLoggedInDataContext().getTenantId();
        List<AuditPolicyControlMapping> mappings = policyControlMappingRepository.findByPolicyId(id).stream()
                .filter(m -> sysUser
                        || m.getTenantId() == null
                        || java.util.Objects.equals(m.getTenantId(), callerTid))
                .toList();
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
        // Library lists are cached; every mutation invalidates them. Placed at
        // the return rather than the top so a handler that throws (an ownership
        // refusal, a validation failure) does not clear a cache it never changed.
        libraryCache.evictLibraryLists();

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(Map.of("id", policy.getId(), "title", policy.getTitle())));
    }

    @PutMapping("/v1/audit/library/policies/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updatePolicy(
            @PathVariable Long id, @RequestBody AuditPolicyRequest req) {
        AuditPolicy policy = policyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AuditPolicy", id));
        requireOwnedPolicy(policy);

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
        // Library lists are cached; every mutation invalidates them. Placed at
        // the return rather than the top so a handler that throws (an ownership
        // refusal, a validation failure) does not clear a cache it never changed.
        libraryCache.evictLibraryLists();

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
        requireOwnedPolicy(policy);

        // ── Separation of duties ─────────────────────────────────────────────
        //
        // The Policy Approval workflow puts a HARD SoD rule on step 3: the person
        // who drafted a policy cannot approve it. This endpoint bypassed that
        // entirely — one call and the drafter approves their own document,
        // whatever the workflow says.
        //
        // Enforced against createdBy rather than the workflow actor because that
        // holds even when no workflow is running, which is the case this bypass
        // was reachable in.
        //
        // Platform admins are exempt: they approve platform library content that
        // they authored, and there is no second party in that model. The exemption
        // is deliberate and narrow — it does NOT apply to a tenant policy.
        if (!utilityService.isSystemUser()
                && java.util.Objects.equals(policy.getCreatedBy(), userId)) {
            throw new BusinessException("POLICY_SOD_VIOLATION",
                    "You drafted this policy, so you cannot approve it. "
                            + "Ask a second approver to review and approve.",
                    HttpStatus.FORBIDDEN);
        }

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
        // Completes the approval step, closing the workflow. Non-fatal when no
        // Policy Approval blueprint is configured for the tenant.
        policyWorkflow.onApproved(policy, utilityService.getLoggedInDataContext().getId());

        log.info("[AUDIT-POLICY] Approved | id={} | workflowInstanceId={}",
                id, policy.getWorkflowInstanceId());
        // Library lists are cached; every mutation invalidates them. Placed at
        // the return rather than the top so a handler that throws (an ownership
        // refusal, a validation failure) does not clear a cache it never changed.
        libraryCache.evictLibraryLists();

        return ResponseEntity.ok(ApiResponse.success(
                responseMap("id", id, "status", "APPROVED",
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
        requireOwnedPolicy(policy);

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
        // Library lists are cached; every mutation invalidates them. Placed at
        // the return rather than the top so a handler that throws (an ownership
        // refusal, a validation failure) does not clear a cache it never changed.
        libraryCache.evictLibraryLists();

        return ResponseEntity.ok(ApiResponse.success(
                responseMap("id", id, "status", policy.getStatus(),
                        "workflowInstanceId", policy.getWorkflowInstanceId())));
    }

    /**
     * Owner review — the missing middle of the lifecycle.
     *
     * Until now the only path out of UNDER_REVIEW was approvePolicy, so a
     * three-role workflow had nothing for the reviewer to DO: step 2 could only
     * ever complete by override. This is the action that closes it.
     *
     * Status does NOT change. Review is an act, not a state — the policy stays
     * UNDER_REVIEW until someone approves or sends it back. What changes is the
     * workflow: the section carrying POLICY_REVIEWED completes and the engine
     * advances to the approval step.
     *
     * @param outcome REVIEWED (ready for approval) or CHANGES_REQUESTED (back to
     *                the drafter). Recorded either way — "the owner asked for
     *                changes on 3 March" is exactly what an auditor wants to see.
     */
    @PostMapping("/v1/audit/library/policies/{id}/review")
    @Operation(summary = "Record owner review of a policy under review")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> reviewPolicy(
            @PathVariable Long id,
            @RequestParam(defaultValue = "REVIEWED") String outcome,
            @RequestParam(required = false) String remarks) {

        AuditPolicy policy = policyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AuditPolicy", id));
        requireOwnedPolicy(policy);

        if (policy.getStatus() != AuditPolicy.PolicyStatus.UNDER_REVIEW) {
            throw new BusinessException("INVALID_STATUS",
                    "Only policies that are under review can be reviewed",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }

        boolean changesRequested = "CHANGES_REQUESTED".equalsIgnoreCase(outcome);
        if (changesRequested) {
            // Back to the drafter. DRAFT is the honest state — the document is
            // being written again, and leaving it UNDER_REVIEW would show a
            // reviewer a queue item nobody is acting on.
            policy.setStatus(AuditPolicy.PolicyStatus.DRAFT);
        }
        policyRepository.save(policy);

        policyWorkflow.onReviewed(policy, changesRequested,
                utilityService.getLoggedInDataContext().getId(), remarks);

        log.info("[AUDIT-POLICY] Review recorded | id={} outcome={}", id, outcome);
        libraryCache.evictLibraryLists();

        return ResponseEntity.ok(ApiResponse.success(responseMap(
                "id", id, "outcome", changesRequested ? "CHANGES_REQUESTED" : "REVIEWED",
                "status", policy.getStatus().name())));
    }

    /**
     * Unpublish — APPROVED back to DRAFT. Platform side only.
     *
     * WHY THIS IS NOT "deprecate"
     *   Deprecate means "this was in force and is now withdrawn" — it stays in
     *   the register as history, and engagements that snapshotted it keep their
     *   copy. Unpublish means "this should not have gone out": a typo in a
     *   platform template, a clause published early. The document goes back to
     *   being worked on.
     *
     * WHY PLATFORM ONLY
     *   The platform team maintains the shared library the way it maintains the
     *   control and test libraries — a tenant unpublishing their own approved
     *   policy would be rewriting their own compliance history, which is what
     *   deprecate plus a new version exists for.
     *
     * DELIBERATELY REFUSED on a policy that has already been adopted or
     * snapshotted: tenants hold copies and engagements hold instances, and
     * silently un-approving the source underneath them would leave those copies
     * descended from a document that is no longer published.
     */
    @PostMapping("/v1/audit/library/policies/{id}/unpublish")
    @Operation(summary = "Return an approved platform policy to draft (platform admins only)")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> unpublishPolicy(@PathVariable Long id) {
        if (!utilityService.isSystemUser()) {
            throw new BusinessException("PLATFORM_ADMIN_ONLY",
                    "Only platform administrators can unpublish a policy",
                    HttpStatus.FORBIDDEN);
        }

        AuditPolicy policy = policyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AuditPolicy", id));

        if (policy.getTenantId() != null) {
            throw new BusinessException("POLICY_NOT_GLOBAL",
                    "Only platform policies can be unpublished. Use Deprecate for an organisation policy.",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (policy.getStatus() != AuditPolicy.PolicyStatus.APPROVED) {
            throw new BusinessException("INVALID_STATUS",
                    "Only an approved policy can be unpublished",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }

        long adopted = policyRepository.countByPreviousVersionId(id);
        if (adopted > 0) {
            throw new BusinessException("POLICY_ALREADY_ADOPTED",
                    adopted + " organisation" + (adopted == 1 ? " has" : "s have")
                            + " already adopted this policy. Deprecate it instead —"
                            + " unpublishing would orphan their copies.",
                    HttpStatus.CONFLICT,
                    java.util.Map.of("adoptedCopies", adopted));
        }

        policy.setStatus(AuditPolicy.PolicyStatus.DRAFT);
        policy.setApprovedById(null);
        policy.setApprovedAt(null);
        policyRepository.save(policy);

        log.info("[AUDIT-POLICY] Unpublished | id={} by={}", id,
                utilityService.getLoggedInDataContext().getId());
        libraryCache.evictLibraryLists();

        return ResponseEntity.ok(ApiResponse.success(responseMap(
                "id", id, "status", "DRAFT")));
    }

    @PostMapping("/v1/audit/library/policies/{id}/deprecate")
    @Operation(summary = "Deprecate a policy — APPROVED → DEPRECATED")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deprecatePolicy(@PathVariable Long id) {
        AuditPolicy policy = policyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AuditPolicy", id));
        requireOwnedPolicy(policy);
        policy.setStatus(AuditPolicy.PolicyStatus.DEPRECATED);
        policyRepository.save(policy);
        // Library lists are cached; every mutation invalidates them. Placed at
        // the return rather than the top so a handler that throws (an ownership
        // refusal, a validation failure) does not clear a cache it never changed.
        libraryCache.evictLibraryLists();

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
        // Library lists are cached; every mutation invalidates them. Placed at
        // the return rather than the top so a handler that throws (an ownership
        // refusal, a validation failure) does not clear a cache it never changed.
        libraryCache.evictLibraryLists();

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
            // Body, not @RequestParam. handleBulkAction posts { ids: [...] } as
            // JSON, so query-param binding received nothing and the call failed
            // before reaching the loop. Same mismatch as customise-all.
            // @RequestParam kept as a fallback so any existing caller using
            // ?ids=1,2,3 still works.
            @RequestBody(required = false) Map<String, Object> body,
            @RequestParam(required = false) List<Long> ids) {

        if ((ids == null || ids.isEmpty()) && body != null && body.get("ids") instanceof List<?> raw) {
            ids = raw.stream()
                    .map(o -> o instanceof Number n ? n.longValue() : Long.valueOf(String.valueOf(o)))
                    .toList();
        }
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException("NO_IDS", "Select at least one policy to delete",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        boolean isSystem  = utilityService.isSystemUser();
        Long callerTenant = utilityService.getLoggedInDataContext().getTenantId();

        int deleted = 0, skipped = 0;
        for (Long id : ids) {
            var found = policyRepository.findById(id);
            if (found.isEmpty()) continue;

            // Silently skipped rather than throwing, matching bulkDeleteControls:
            // a bulk action must not abort halfway and leave the caller guessing
            // which half went through. Previously existsById was the only test, so
            // a list of ids deleted other tenants' policies and the global library.
            AuditPolicy pol = found.get();
            if (!isSystem && !java.util.Objects.equals(pol.getTenantId(), callerTenant)) {
                skipped++;
                continue;
            }

            policyControlMappingRepository.findByPolicyId(id)
                    .forEach(policyControlMappingRepository::delete);
            policyRepository.deleteById(id);
            deleted++;
        }
        if (skipped > 0)
            log.warn("[AUDIT-LIBRARY] Bulk delete skipped {} policies not owned by tenant {}", skipped, callerTenant);
        log.info("[AUDIT-LIBRARY] Bulk deleted {} policies", deleted);
        // Library lists are cached; every mutation invalidates them. Placed at
        // the return rather than the top so a handler that throws (an ownership
        // refusal, a validation failure) does not clear a cache it never changed.
        libraryCache.evictLibraryLists();

        return ResponseEntity.ok(ApiResponse.success(Map.of("deleted", deleted, "skipped", skipped)));
    }

    @DeleteMapping("/v1/audit/library/policies/{id}")
    public ResponseEntity<ApiResponse<Void>> deletePolicy(@PathVariable Long id) {
        // Loaded first purely so ownership can be checked — deleteById(id) took the
        // path variable straight to the database with nothing in between.
        AuditPolicy policy = policyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AuditPolicy", id));
        requireOwnedPolicy(policy);

        // ── The guards the UI implied but the server never enforced ──────────
        //
        // allowed_statuses_json on DELETE_POLICY restricts this to DRAFT and
        // DEPRECATED, but that is a rendering rule: a direct API call bypassed it
        // entirely. Same shape as the approvePolicy bypass — a restriction that
        // exists only in the button.
        boolean platformAdmin = utilityService.isSystemUser();

        if (!platformAdmin && policy.getStatus() == AuditPolicy.PolicyStatus.APPROVED) {
            // Deleting your own approved policy destroys the register entry: the
            // approval record, the version history, the workflow trail. Deprecate
            // keeps all of it and reaches the same place.
            throw new BusinessException("POLICY_APPROVED_CANNOT_DELETE",
                    "An approved policy cannot be deleted — deprecate it instead, "
                            + "which keeps its approval and version history.",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }

        // Engagement instances snapshot everything they need, so deleting the
        // library policy does NOT break them — original_policy_id is an indexed
        // column, not a foreign key. What breaks is the trail back: that column
        // becomes a number pointing at nothing, and "which library policy did
        // this come from?" stops answering.
        //
        // Refused rather than warned, because the damage is silent and only
        // surfaces when someone tries to trace an instance months later. A
        // platform admin may still do it — they own the library and may be
        // clearing out a mistake.
        long instances = policyInstanceRepository.countByOriginalPolicyId(id);
        if (instances > 0 && !platformAdmin) {
            throw new BusinessException("POLICY_IN_USE",
                    instances + " engagement" + (instances == 1 ? "" : "s")
                            + " already include this policy. Deprecate it instead — deleting"
                            + " it would leave those instances pointing at nothing.",
                    HttpStatus.CONFLICT,
                    java.util.Map.of("engagementInstances", instances));
        }
        if (instances > 0) {
            log.warn("[AUDIT-POLICY] Platform admin deleting policy {} with {} engagement instances",
                    id, instances);
        }

        policyControlMappingRepository.deleteByPolicyId(id);
        policyRepository.deleteById(id);
        log.info("[AUDIT-POLICY] Deleted id={}", id);
        // Library lists are cached; every mutation invalidates them. Placed at
        // the return rather than the top so a handler that throws (an ownership
        // refusal, a validation failure) does not clear a cache it never changed.
        libraryCache.evictLibraryLists();

        return ResponseEntity.ok(ApiResponse.success());
    }

    // ── Library — Control ↔ Policy mappings ───────────────────────────────────

    @GetMapping("/v1/audit/library/controls/{controlId}/policies")
    @Operation(summary = "List policies mapped to a library control")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listControlPolicies(
            @PathVariable Long controlId) {
        // Controls are shared across every tenant, so the mappings hanging off one
        // control belong to many different companies. Returning them all meant a
        // client opening a global control saw which policies OTHER clients had
        // mapped to it, titles included. Keep this tenant's rows plus the global
        // ones the platform ships.
        Long callerTenant = utilityService.getLoggedInDataContext().getTenantId();
        boolean isSystem  = utilityService.isSystemUser();
        // findAllVisibleIncludingExclusions, not findVisibleByControlId: this is the
        // one screen that must SHOW exclusions, so a tenant can see what they
        // excluded and restore it. Everywhere else — including engagement
        // snapshotting — uses the resolving variant.
        List<AuditPolicyControlMapping> raw = isSystem
                ? policyControlMappingRepository.findByControlId(controlId)
                : policyControlMappingRepository
                  .findAllVisibleIncludingExclusions(controlId, callerTenant);

        // A platform row whose policy this tenant has excluded must not be listed
        // as active — the exclusion row stands in for it.
        java.util.Set<Long> excludedIds = raw.stream()
                .filter(m -> m.getMappingType() == AuditPolicyControlMapping.MappingType.EXCLUDED)
                .map(AuditPolicyControlMapping::getPolicyId)
                .collect(java.util.stream.Collectors.toSet());

        List<AuditPolicyControlMapping> mappings = raw.stream()
                .filter(m -> m.getMappingType() == AuditPolicyControlMapping.MappingType.EXCLUDED
                        || !(m.getTenantId() == null && excludedIds.contains(m.getPolicyId())))
                .toList();

        // BATCHED — was one policyRepository.findById() per row.
        List<Long> policyIds = mappings.stream().map(AuditPolicyControlMapping::getPolicyId).toList();
        Map<Long, AuditPolicy> policiesById = policyIds.isEmpty() ? Map.of()
                : policyRepository.findAllById(policyIds).stream()
                  .collect(Collectors.toMap(AuditPolicy::getId, p -> p));

        List<Map<String, Object>> result = mappings.stream().map(m -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("mappingId",   m.getId());
            row.put("policyId",    m.getPolicyId());
            row.put("controlId",   m.getControlId());
            row.put("mappingType", m.getMappingType());
            // Drives the collapsed "excluded" section and the Restore action.
            row.put("excluded",
                    m.getMappingType() == AuditPolicyControlMapping.MappingType.EXCLUDED);
            row.put("mappingNote", m.getMappingNote());
            AuditPolicy p = policiesById.get(m.getPolicyId());
            if (p != null) {
                row.put("policyTitle",    p.getTitle());
                row.put("policyRef",      p.getPolicyRef());
                row.put("policyVersion",  p.getVersion());
                row.put("policyStatus",   p.getStatus());
                row.put("nextReviewDate", p.getNextReviewDate());

                // Ownership, so the control screen can tell a platform policy from
                // one of the tenant's own. Without it every mapped policy looked
                // identical, and the tenant could not see which of them they were
                // allowed to unlink — the server refuses unlink on a global policy,
                // so the UI was offering a button that always 403s.
                boolean globalPol = p.getTenantId() == null;
                row.put("policyOrigin",      globalPol ? "GLOBAL" : "ORG");
                row.put("policyUnlinkable",  !globalPol || utilityService.isSystemUser());
                // Non-null when this policy is a tenant copy of a platform one, which
                // is what makes it SUPERSEDE that platform policy at snapshot time.
                row.put("supersedesPolicyId", p.getPreviousVersionId());
            }
            return row;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/v1/audit/library/policies/{policyId}/controls")
    @Operation(summary = "List controls mapped to a library policy")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listPolicyControls(
            @PathVariable Long policyId) {
        requireReadablePolicy(policyRepository.findById(policyId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditPolicy", policyId)));

        List<AuditPolicyControlMapping> mappings =
                policyControlMappingRepository.findByPolicyId(policyId);

        // BATCHED — was one controlRepository.findById() per row.
        List<Long> controlIds = mappings.stream().map(AuditPolicyControlMapping::getControlId).toList();
        Map<Long, AuditControl> controlsById = controlIds.isEmpty() ? Map.of()
                : controlRepository.findAllById(controlIds).stream()
                  .collect(Collectors.toMap(AuditControl::getId, c -> c));

        List<Map<String, Object>> result = mappings.stream().map(m -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("mappingId",   m.getId());
            row.put("policyId",    m.getPolicyId());
            row.put("controlId",   m.getControlId());
            row.put("mappingType", m.getMappingType());
            row.put("mappingNote", m.getMappingNote());
            AuditControl c = controlsById.get(m.getControlId());
            if (c != null) {
                row.put("controlName", c.getName());
                row.put("controlCode", c.getControlCode());
                row.put("controlTag",  c.getControlTag());
                row.put("frameworkRef",c.getFrameworkRef());
            }
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
        // Optional workflow section — "controls linked". Never required, so a
        // policy can still be approved before anyone maps it.
        policyRepository.findById(policyId)
                .ifPresent(p -> policyWorkflow.onControlLinked(p, userId));


        // Library lists are cached; every mutation invalidates them. Placed at
        // the return rather than the top so a handler that throws (an ownership
        // refusal, a validation failure) does not clear a cache it never changed.
        libraryCache.evictLibraryLists();

        return ResponseEntity.ok(ApiResponse.success(
                Map.of("mappingId", mapping.getId(), "policyId", policyId, "controlId", controlId)));
    }

    /**
     * Restore a platform policy this tenant had excluded.
     *
     * Deletes the EXCLUSION row only. The global mapping was never touched — a
     * tenant cannot delete platform data — so removing the exclusion is all that
     * is needed for it to resolve back into scope.
     */
    @DeleteMapping("/v1/audit/library/controls/{controlId}/policies/{policyId}/exclusion")
    @Operation(summary = "Restore a platform policy previously excluded by this organisation")
    @Transactional
    public ResponseEntity<ApiResponse<Void>> restorePolicyExclusion(
            @PathVariable Long controlId, @PathVariable Long policyId) {
        var ctx = utilityService.getLoggedInDataContext();
        if (ctx.getTenantId() == null) {
            throw new BusinessException("POLICY_NO_TENANT",
                    "Platform administrators edit global mappings directly",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }

        policyControlMappingRepository.findByControlIdAndTenantId(controlId, ctx.getTenantId()).stream()
                .filter(m -> java.util.Objects.equals(m.getPolicyId(), policyId)
                        && m.getMappingType() == AuditPolicyControlMapping.MappingType.EXCLUDED)
                .forEach(policyControlMappingRepository::delete);

        log.info("[AUDIT-POLICY] Exclusion restored | policyId={} controlId={} tenantId={}",
                policyId, controlId, ctx.getTenantId());
        libraryCache.evictLibraryLists();
        return ResponseEntity.ok(ApiResponse.success());
    }

    @DeleteMapping("/v1/audit/library/controls/{controlId}/policies/{policyId}")
    public ResponseEntity<ApiResponse<Void>> unlinkControlPolicy(
            @PathVariable Long controlId, @PathVariable Long policyId) {
        var ctx = utilityService.getLoggedInDataContext();
        AuditPolicy policy = policyRepository.findById(policyId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditPolicy", policyId));

        // ── Two different operations behind one gesture ─────────────────────
        //
        // Removing YOUR policy from a control deletes the mapping — it is yours.
        //
        // Removing a PLATFORM policy cannot delete anything: the row is global,
        // shared by every tenant, and deleting it would remove the mapping for
        // everyone. It used to be refused outright, which left a tenant with a
        // platform policy on their control and no way to say "not applicable to
        // us". So it now records an EXCLUSION — a tenant-owned row that resolves
        // the global one away for this tenant only, and is reversible.
        //
        // Same click for the user; the system records the correct thing.
        if (policy.getTenantId() == null && !utilityService.isSystemUser()) {
            if (ctx.getTenantId() == null) {
                throw new BusinessException("POLICY_NO_TENANT",
                        "Platform administrators edit global mappings directly",
                        HttpStatus.UNPROCESSABLE_ENTITY);
            }
            // Idempotent: excluding twice is the same as excluding once.
            boolean already = policyControlMappingRepository
                    .findByControlIdAndTenantId(controlId, ctx.getTenantId()).stream()
                    .anyMatch(m -> java.util.Objects.equals(m.getPolicyId(), policyId)
                            && m.getMappingType() == AuditPolicyControlMapping.MappingType.EXCLUDED);

            if (!already) {
                AuditPolicyControlMapping exclusion = new AuditPolicyControlMapping();
                exclusion.setPolicyId(policyId);
                exclusion.setControlId(controlId);
                exclusion.setMappingType(AuditPolicyControlMapping.MappingType.EXCLUDED);
                exclusion.setMappingNote("Excluded by " + ctx.getFullName());
                exclusion.setTenantId(ctx.getTenantId());
                exclusion.setCreatedBy(ctx.getId());
                policyControlMappingRepository.save(exclusion);
                log.info("[AUDIT-POLICY] Platform policy excluded | policyId={} controlId={} tenantId={}",
                        policyId, controlId, ctx.getTenantId());
            }
            libraryCache.evictLibraryLists();
            return ResponseEntity.ok(ApiResponse.success());
        }

        // Their own policy (or Platform Admin on a global row) — a real unlink.
        requireOwnedPolicy(policy);
        policyControlMappingRepository.findByPolicyIdAndControlId(policyId, controlId)
                .ifPresent(policyControlMappingRepository::delete);
        // Library lists are cached; every mutation invalidates them. Placed at
        // the return rather than the top so a handler that throws (an ownership
        // refusal, a validation failure) does not clear a cache it never changed.
        libraryCache.evictLibraryLists();

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

        // Loaded by id alone, and the engagementId in the path was never used —
        // so any authenticated user could read any tenant's policy instance by
        // guessing an id, with a path prefix that looked scoped but was not.
        Long caller = utilityService.getLoggedInDataContext().getTenantId();
        if (!utilityService.isSystemUser()
                && !java.util.Objects.equals(instance.getTenantId(), caller)) {
            log.warn("[AUDIT-POLICY] Refused cross-tenant policy-instance read | id={} caller={}",
                    policyInstanceId, caller);
            throw new BusinessException("POLICY_ACCESS_DENIED",
                    "You can only view policy instances belonging to your organisation",
                    HttpStatus.FORBIDDEN);
        }
        if (!java.util.Objects.equals(instance.getEngagementId(), engagementId)) {
            throw new ResourceNotFoundException("AuditPolicyInstance", policyInstanceId);
        }

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

    /**
     * Adopt a global policy as this organisation's own.
     *
     * WHY THIS EXISTS
     *   Global policies are platform-maintained and refused to tenant edits, which
     *   left a client looking at a policy they wanted to use with no way forward —
     *   Deprecate and New version both 403, and nothing offered them a path. This
     *   is that path: it copies the global policy into the caller's tenant as a
     *   DRAFT they own outright and may edit freely.
     *
     * WHY A COPY AND NOT AN OVERLAY
     *   A policy is the organisation's own document — its wording, owner and review
     *   cycle diverge from the platform template the moment it is adopted, and it is
     *   the artefact an auditor asks to see. That is the one library entity where a
     *   real copy is the honest model. Controls, tests and templates stay shared,
     *   because those are framework structure rather than the client's own text.
     *
     * previousVersionId points back at the global source so the lineage is
     * recoverable — "this started life as the platform's POL-03".
     */
    // Absolute path: this controller has NO class-level @RequestMapping — every
    // sibling endpoint spells out /v1/audit/library/... in full. A relative path
    // here registered at /policies/{id}/customise, so the real URL matched no
    // handler and Spring fell through to static-resource lookup:
    //   NoResourceFoundException: No static resource v1/audit/library/policies/27/customise
    /**
     * Policy-only CSV import, for the tenant policy list.
     *
     * Separate from /v1/audit/library/tests-policies/import, which also writes
     * the shared TEST library and is a platform-admin tool. This one refuses any
     * row type other than POLICY / POLICY_CONTROL_MAPPING and reports them as
     * errors rather than applying them silently.
     *
     * Imported policies land as DRAFT — a bulk upload must not publish approved
     * policies, and with the approval workflow live each one goes through review.
     */
    @PostMapping("/v1/audit/library/policies/import")
    @Operation(summary = "Bulk import policies from CSV (policy rows only)")
    public ResponseEntity<ApiResponse<com.kashi.grc.common.dto.CsvImportResult>> importPoliciesCsv(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        var ctx = utilityService.getLoggedInDataContext();
        var result = csvImportExtension.importPoliciesOnly(file, ctx.getTenantId(), ctx.getId());
        libraryCache.evictLibraryLists();
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * Adopt every platform policy this organisation has not already adopted.
     *
     * ASYNC. ~39 policies, each writing a copy, its mappings and an exclusion,
     * at the ~250ms round-trip this deployment actually has, is minutes of work —
     * past any request timeout. Returns 202 and does the work on the Kafka path,
     * the same shape as engagement provisioning.
     *
     * Falls back to running inline when Kafka is disabled, so the feature still
     * works in a local setup rather than silently accepting a request that never
     * happens. ObjectProvider because KafkaEventPublisher is
     * @ConditionalOnProperty — injecting it directly would fail startup with
     * kashi.kafka.enabled=false.
     *
     * BODY (from the adopt dialog, all optional):
     *   approve   — true: copies land APPROVED, stamped with this caller and now.
     *   ownerTeam — stamped on every copy.
     *
     * Documented as body fields rather than javadoc parameter tags: they
     * stopped being method parameters when this moved to @RequestBody, and the
     * stale tags referenced identifiers that no longer existed.
     */
    @PostMapping("/v1/audit/library/policies/customise-all")
    @Operation(summary = "Adopt every platform policy not already customised by this organisation")
    public ResponseEntity<ApiResponse<Map<String, Object>>> customiseAllPolicies(
            // Body, not @RequestParam. The list-action form posts a JSON body
            // (api({ data: payload })), so query-param binding silently received
            // nothing and every option fell back to its default — which is why
            // "adopt all" ran with approve=false however the dialog was answered.
            @RequestBody(required = false) Map<String, Object> body) {

        boolean approve   = body != null && Boolean.TRUE.equals(body.get("approve"));
        String  ownerTeam = body == null ? null : (String) body.get("ownerTeam");

        var ctx = utilityService.getLoggedInDataContext();
        Long tenantId = ctx.getTenantId();
        if (tenantId == null) {
            throw new BusinessException("POLICY_NO_TENANT",
                    "Platform administrators maintain the global library directly",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }

        // ownerId, a real user, not just a team string.
        //
        // PolicyVariableResolver prefers ownerTeam over ownerId when both are
        // set, so a stray team string would mask a correctly assigned owner.
        // Accountability for a policy attaches to a PERSON; the team is context.
        //
        // Defaults to the caller when the dialog leaves it blank — someone is
        // adopting these into their own register, and an unowned policy is a gap
        // nobody is looking at.
        Long ownerId = ctx.getId();
        if (body != null && body.get("ownerId") != null) {
            try {
                ownerId = Long.valueOf(String.valueOf(body.get("ownerId")));
            } catch (NumberFormatException ignored) { /* keep the caller */ }
        }


        var publisher = kafkaPublisher.getIfAvailable();
        if (publisher != null) {
            publisher.publish(
                    com.kashi.grc.common.kafka.KafkaTopics.AUDIT_POLICY_BULK_ADOPT_REQUESTED,
                    "AUDIT_POLICY_BULK_ADOPT_REQUESTED",
                    String.valueOf(tenantId),
                    responseMap("tenantId", tenantId, "actorUserId", ctx.getId(),
                            "approve", approve, "ownerTeam", ownerTeam,
                            "ownerId", ownerId),
                    tenantId, ctx.getId());

            log.info("[AUDIT-POLICY] Bulk adopt queued | tenantId={} approve={}", tenantId, approve);
            return ResponseEntity.accepted().body(ApiResponse.success(responseMap(
                    "queued", true,
                    "message", "Adopting the platform policy library. This runs in the "
                            + "background — refresh the list in a moment.")));
        }

        // Kafka off — run inline. Same service, so the per-policy transaction
        // isolation is identical; only the thread differs.
        var result = bulkAdoptService.adoptAll(tenantId, ctx.getId(), approve, ownerTeam, ownerId);
        return ResponseEntity.ok(ApiResponse.success(responseMap(
                "queued",   false,
                "created",  result.created(),
                "skipped",  result.skipped(),
                "failed",   result.failed(),
                "problems", result.problems())));
    }

    @PostMapping("/v1/audit/library/policies/{id}/customise")
    @Operation(summary = "Copy a global policy into the caller's organisation as an editable draft")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> customisePolicy(@PathVariable Long id) {
        var ctx = utilityService.getLoggedInDataContext();
        Long userId   = ctx.getId();
        Long tenantId = ctx.getTenantId();

        AuditPolicy source = policyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AuditPolicy", id));

        // Only global policies need adopting. Copying one you already own would
        // silently create a duplicate — that is what New version is for.
        if (source.getTenantId() != null) {
            throw new BusinessException("POLICY_ALREADY_OWNED",
                    "This policy already belongs to your organisation — use New version to revise it",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (tenantId == null) {
            throw new BusinessException("POLICY_NO_TENANT",
                    "Platform administrators edit global policies directly",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }

        // Multiple copies are ALLOWED, deliberately.
        //
        // I blocked a second customise at first, on two grounds. One was wrong:
        // the exclusion rows no longer collide, because the insert below skips a
        // pair this tenant has already excluded. The other was a judgement about
        // policies being governing documents — real, but not the platform's call
        // to enforce at copy time. Every template-copy feature people expect
        // (Asana, Notion) copies freely, and there are legitimate reasons for two:
        // separate legal entities, a regional variant, or simply starting over
        // after mangling the first draft.
        //
        // Copying is cheap and reversible — the copy lands as DRAFT and reaches no
        // engagement until it is approved. The real risk is two APPROVED copies
        // mapped to the SAME control, which duplicates policy instances at
        // snapshot time. That is a mapping problem and belongs where mappings are
        // made, not here: blocking the copy would not prevent it anyway, since two
        // separately authored policies can collide the same way.
        //
        // The ref stays unique automatically — uniqueRefForTenant appends a
        // numeric suffix, so a second copy is POL-03-META-2.
        long existingCopies = policyRepository
                .countByPreviousVersionIdAndTenantId(source.getId(), tenantId);

        AuditPolicy copy = AuditPolicy.builder()
                .title(source.getTitle())
                .policyRef(uniqueRefForTenant(source.getPolicyRef(), tenantId))
                .description(source.getDescription())
                .version(1)
                .previousVersionId(source.getId())   // lineage back to the global source
                .contentType(source.getContentType())
                .contentBody(source.getContentBody())
                .externalUrl(source.getExternalUrl())
                .evidenceRecordId(source.getEvidenceRecordId())
                .status(AuditPolicy.PolicyStatus.DRAFT)
                .ownerId(userId)                     // the adopting org owns it, not the platform
                .ownerTeam(source.getOwnerTeam())
                .reviewFrequencyMonths(source.getReviewFrequencyMonths())
                .controlTags(source.getControlTags())
                .frameworkRefs(source.getFrameworkRefs())
                .createdBy(userId)
                .tenantId(tenantId)
                .build();
        policyRepository.save(copy);

        // Carry the global control mappings over as tenant-owned rows, so the copy
        // arrives already linked to the same controls. Without this the client
        // adopts a policy and silently loses every mapping the platform had set up.
        int carried = 0;
        for (AuditPolicyControlMapping m : policyControlMappingRepository.findByPolicyId(source.getId())) {
            if (m.getTenantId() != null) continue;      // another tenant's mapping — not ours to copy
            AuditPolicyControlMapping mc = new AuditPolicyControlMapping();
            mc.setPolicyId(copy.getId());
            mc.setControlId(m.getControlId());
            mc.setMappingType(m.getMappingType());
            mc.setMappingNote(m.getMappingNote());
            mc.setTenantId(tenantId);
            mc.setCreatedBy(userId);
            policyControlMappingRepository.save(mc);
            carried++;

            // AUTO-EXCLUDE the original on the same control.
            //
            // Without this the control carries both the platform policy and the
            // copy, and the engagement snapshots the same document twice —
            // identical titles, differing only by id, one of them reviewed and
            // one left NOT_REVIEWED. Adopting a policy plainly means "ours
            // instead of theirs", so the system records that rather than making
            // the user click it on every control the policy touches.
            //
            // The note is written for the auditor who asks why the platform
            // policy is not on this control.
            // Skip if this tenant already excluded this pair — the widened unique
            // key now permits the row, but a duplicate would still throw.
            boolean alreadyExcluded = policyControlMappingRepository
                    .findByControlIdAndTenantId(m.getControlId(), tenantId).stream()
                    .anyMatch(x -> java.util.Objects.equals(x.getPolicyId(), source.getId())
                            && x.getMappingType() == AuditPolicyControlMapping.MappingType.EXCLUDED);
            if (alreadyExcluded) continue;

            AuditPolicyControlMapping ex = new AuditPolicyControlMapping();
            ex.setPolicyId(source.getId());
            ex.setControlId(m.getControlId());
            ex.setMappingType(AuditPolicyControlMapping.MappingType.EXCLUDED);
            ex.setMappingNote("Superseded by " + copy.getPolicyRef());
            ex.setTenantId(tenantId);
            ex.setCreatedBy(userId);
            policyControlMappingRepository.save(ex);
        }

        log.info("[AUDIT-POLICY] Global policy adopted | sourceId={} newId={} tenantId={} mappingsCopied={}",
                source.getId(), copy.getId(), tenantId, carried);

        // Library lists are cached; every mutation invalidates them. Placed at
        // the return rather than the top so a handler that throws (an ownership
        // refusal, a validation failure) does not clear a cache it never changed.
        libraryCache.evictLibraryLists();

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(Map.of(
                        "id",              copy.getId(),
                        "sourcePolicyId",  source.getId(),
                        "mappingsCopied",  carried,
                        "policyRef",       copy.getPolicyRef(),
                        // How many copies of this platform policy the tenant had
                        // BEFORE this one. Lets the client say "this is your 2nd
                        // copy" rather than silently making another.
                        "existingCopies",  existingCopies,
                        "status",          copy.getStatus().name())));
    }

    // ══════════════════════════════════════════════════════════════════════
    // TENANT OWNERSHIP
    //
    // Policies are the one library entity a client genuinely owns and edits —
    // controls, tests, sections and templates are framework structure and stay
    // system-authored. That makes this the surface where a missing ownership
    // check is most damaging, and most of these handlers had none: updatePolicy,
    // deletePolicy, bulkDeletePolicies, deprecatePolicy and getPolicy all loaded
    // by id alone, so any authenticated user of any tenant could read, rewrite or
    // delete another company's policy, and could edit the global ones too.
    // ══════════════════════════════════════════════════════════════════════

    /**
     * A copy must not reuse the platform policy's reference.
     *
     * WHY THIS MATTERS BEYOND TIDINESS
     *   policyRef is not decoration — CSV import upserts on it.
     *   AuditCsvImportService resolves an existing policy via
     *   findByPolicyRefForTenant, which is global-aware and returns BOTH the
     *   platform row and the tenant copy when they share a ref. A re-import can
     *   then update the wrong one, silently and long after the copy was made.
     *
     *   It plays no part in evidence reuse (tag-based, see EvidenceReuseEngine)
     *   or in automation (AuditTest.automationKey), so changing it breaks neither.
     *
     * NOT applied to new-version: a new version of a policy is the same policy
     * and must keep its reference. Only an adopted COPY needs a distinct one.
     *
     * SHAPE: POL-03 → POL-03-META, using the tenant code so the origin stays
     * legible. Falls back to the tenant id when a tenant has no code, and adds a
     * numeric suffix if that is taken too — customising the same policy twice
     * must not collide.
     */
    private String uniqueRefForTenant(String sourceRef, Long tenantId) {
        if (sourceRef == null || sourceRef.isBlank()) return sourceRef;

        String suffix = tenantRepository.findById(tenantId)
                .map(t -> t.getCode() != null && !t.getCode().isBlank()
                        ? t.getCode().toUpperCase().trim()
                        : String.valueOf(tenantId))
                .orElse(String.valueOf(tenantId));

        String candidate = sourceRef + "-" + suffix;
        int n = 2;
        while (policyRepository.existsByPolicyRefAndTenantId(candidate, tenantId)) {
            candidate = sourceRef + "-" + suffix + "-" + n++;
        }
        return candidate;
    }

    /** Throws unless the caller may modify this policy. Platform Admin may touch global rows. */
    private void requireOwnedPolicy(AuditPolicy policy) {
        if (utilityService.isSystemUser()) return;
        Long callerTenant = utilityService.getLoggedInDataContext().getTenantId();
        if (java.util.Objects.equals(policy.getTenantId(), callerTenant)) return;

        // Global policies (tenantId null) are readable by everyone but writable
        // only by Platform Admin — a client that wants its own version uses
        // new-version, which creates a tenant-owned copy.
        log.warn("[AUDIT-POLICY] Refused cross-tenant access | policyId={} policyTenant={} caller={}",
                policy.getId(), policy.getTenantId(), callerTenant);
        throw new BusinessException("POLICY_ACCESS_DENIED",
                policy.getTenantId() == null
                        ? "Global policies are maintained by the platform and cannot be edited"
                        : "You can only work with policies belonging to your organisation",
                HttpStatus.FORBIDDEN);
    }

    /** Read guard — same rule, but global rows are allowed through. */
    private void requireReadablePolicy(AuditPolicy policy) {
        if (utilityService.isSystemUser()) return;
        if (policy.getTenantId() == null) return;                 // global library, readable by all
        Long callerTenant = utilityService.getLoggedInDataContext().getTenantId();
        if (java.util.Objects.equals(policy.getTenantId(), callerTenant)) return;

        log.warn("[AUDIT-POLICY] Refused cross-tenant read | policyId={} caller={}",
                policy.getId(), callerTenant);
        throw new BusinessException("POLICY_ACCESS_DENIED",
                "You can only view policies belonging to your organisation",
                HttpStatus.FORBIDDEN);
    }
    /**
     * Same JSON shape as the entity overload, built from the list projection.
     * Kept as an overload rather than replacing the entity version, because the
     * detail endpoint still needs the full entity (it returns contentBody).
     */
    private Map<String, Object> toPolicySummaryMap(com.kashi.grc.audit.repository.AuditPolicySummary p) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("id",                    p.id());
        m.put("title",                 p.title());
        m.put("policyRef",             p.policyRef());
        m.put("description",           p.description());
        m.put("version",               p.version());
        m.put("status",                p.status() != null ? p.status().name() : null);
        m.put("contentType",           p.contentType() != null ? p.contentType().name() : null);
        m.put("ownerId",               p.ownerId());
        m.put("ownerTeam",             p.ownerTeam());
        m.put("approvedAt",            p.approvedAt());
        m.put("effectiveDate",         p.effectiveDate());
        m.put("nextReviewDate",        p.nextReviewDate());
        m.put("reviewFrequencyMonths", p.reviewFrequencyMonths());
        m.put("controlTags",           p.controlTags());
        m.put("frameworkRefs",         p.frameworkRefs());
        m.put("tenantId",              p.tenantId());
        m.put("createdAt",             p.createdAt());

        boolean globalP = p.tenantId() == null;
        m.put("origin",   globalP ? "GLOBAL" : "ORG");
        m.put("editable", !globalP || utilityService.isSystemUser());
        return m;
    }

    /**
     * Null-tolerant response map.
     *
     * Map.of() throws NullPointerException on a null VALUE, not just a null key.
     * sendForReview and approvePolicy both returned
     * Map.of(..., "workflowInstanceId", policy.getWorkflowInstanceId()) — and that
     * is null whenever no policy workflow is configured for the tenant, which is
     * the normal case for a library policy that is simply approved by hand.
     *
     * The failure was nastier than a 500: the status change had already been
     * saved and committed, so the policy DID move to UNDER_REVIEW while the user
     * saw "an unexpected error occurred" and reasonably assumed it had not.
     *
     * HashMap, not Map.ofEntries + filter: a null workflowInstanceId is
     * meaningful to the client ("no workflow attached"), so it should be present
     * in the JSON as null rather than silently absent.
     */
    private static Map<String, Object> responseMap(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

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
        // origin/editable let the UI show a Global badge and hide Edit/Delete on rows
        // the server would refuse anyway. The guards are the boundary; this exists so
        // the interface stops offering actions that end in a 403.
        boolean globalP = p.getTenantId() == null;
        m.put("origin",   globalP ? "GLOBAL" : "ORG");
        m.put("editable", !globalP || utilityService.isSystemUser());
        m.put("createdAt",             p.getCreatedAt());
        return m;
    }

    private Map<String, Object> toPolicyDetailMap(AuditPolicy p) {
        Map<String, Object> m = toPolicySummaryMap(p);
        // Placeholders resolved on the way OUT, never baked into content_body.
        // {{approver_name}} and {{approval_date}} do not exist until the policy is
        // approved, so no copy-time substitution could produce them — and a baked
        // {{company_name}} would need a bulk find-and-replace after a rename.
        m.put("contentBody", policyVariables.resolve(p.getContentBody(), p,
                utilityService.getLoggedInDataContext().getTenantId()));
        // The unresolved source, for the editor — editing the RESOLVED text would
        // silently bake the values in and destroy the placeholders.
        m.put("contentBodyRaw",     p.getContentBody());
        m.put("externalUrl",        p.getExternalUrl());
        m.put("evidenceRecordId",   p.getEvidenceRecordId());
        m.put("approvedById",       p.getApprovedById());
        m.put("workflowInstanceId", p.getWorkflowInstanceId());
        // Drives ui_actions.requires_assignment. Without it that flag is inert —
        // the gate reads this field and treats "absent" as "allowed", so an
        // action marked assignment-scoped was visible to everyone.
        m.put("isAssignedToCurrentUser",
                policyWorkflow.isCurrentActor(p, utilityService.getLoggedInDataContext().getId()));
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