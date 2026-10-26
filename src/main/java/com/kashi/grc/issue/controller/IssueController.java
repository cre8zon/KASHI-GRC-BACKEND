package com.kashi.grc.issue.controller;

import com.kashi.grc.audit.domain.AuditFinding;
import com.kashi.grc.audit.repository.AuditFindingRepository;
import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.dto.PaginatedResponse;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.repository.DbRepository;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.issue.domain.Issue;
import com.kashi.grc.issue.dto.IssueIngestRequest;
import com.kashi.grc.issue.dto.IssueRequest;
import com.kashi.grc.issue.dto.IssueResponse;
import com.kashi.grc.issue.repository.IssueRepository;
import com.kashi.grc.issue.service.IssueService;
import com.kashi.grc.workflow.dto.response.WorkflowHistoryResponse;
import com.kashi.grc.workflow.service.WorkflowEngineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/v1/issues")
@Tag(name = "Issue Management", description = "Enterprise issue management — internal, external, and automated issues")
@RequiredArgsConstructor
public class IssueController {

    private final IssueService                                               issueService;
    private final UtilityService                                             utilityService;
    private final DbRepository                                               dbRepository;
    private final WorkflowEngineService                                      workflowEngineService;
    private final com.kashi.grc.workflow.repository.WorkflowInstanceRepository instanceRepository;
    // ADDED
    private final IssueRepository                                            issueRepository;
    private final com.kashi.grc.audit.service.AuditScopeService              auditScope;
    private final AuditFindingRepository                                     findingRepository;
    private final com.kashi.grc.actionitem.repository.ActionItemRepository                     actionItemRepository;
    private final com.kashi.grc.assessment.repository.AssessmentQuestionInstanceRepository     questionInstanceRepository;
    private final com.kashi.grc.assessment.repository.AssessmentSectionInstanceRepository      sectionInstanceRepository;
    private final com.kashi.grc.assessment.repository.AssessmentResponseRepository             responseRepository;
    private final com.kashi.grc.assessment.repository.AssessmentOptionInstanceRepository       optionInstanceRepository;
    private final com.kashi.grc.workflow.service.WorkflowAccessService      workflowAccessService;

    @Value("${app.ingest.token:}")
    private String ingestToken;

    // ── Create ────────────────────────────────────────────────────────────────

    @PostMapping
    @Operation(summary = "Create a new issue — INTERNAL or EXTERNAL type")
    public ResponseEntity<ApiResponse<IssueResponse>> create(
            @Valid @RequestBody IssueRequest req) {
        var ctx = utilityService.getLoggedInDataContext();
        IssueResponse response = issueService.create(req, ctx.getId(), ctx.getTenantId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    // ── List ──────────────────────────────────────────────────────────────────

    @GetMapping
    @Operation(summary = "List issues — filterable by issueType, severity, status, ownerId")
    public ResponseEntity<ApiResponse<PaginatedResponse<Map<String, Object>>>> list(
            @RequestParam Map<String, String> allParams) {

        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();

        return ResponseEntity.ok(ApiResponse.success(dbRepository.findAll(
                Issue.class,
                utilityService.getpageDetails(allParams),
                (cb, root) -> {
                    List<jakarta.persistence.criteria.Predicate> preds = new java.util.ArrayList<>();
                    preds.add(cb.equal(root.get("tenantId"), tenantId));
                    if (allParams.containsKey("issueType"))
                        preds.add(cb.equal(root.get("issueType"),
                                Issue.IssueType.valueOf(allParams.get("issueType").toUpperCase())));
                    if (allParams.containsKey("severity"))
                        preds.add(cb.equal(root.get("severity"),
                                Issue.Severity.valueOf(allParams.get("severity").toUpperCase())));
                    if (allParams.containsKey("status"))
                        preds.add(cb.equal(root.get("status"),
                                Issue.Status.valueOf(allParams.get("status").toUpperCase())));
                    if (allParams.containsKey("ownerId"))
                        preds.add(cb.equal(root.get("ownerId"), Long.parseLong(allParams.get("ownerId"))));
                    if (allParams.containsKey("slaBreached"))
                        preds.add(cb.equal(root.get("slaBreached"),
                                Boolean.parseBoolean(allParams.get("slaBreached"))));
                    if (allParams.containsKey("externalSource"))
                        preds.add(cb.equal(root.get("externalSource"),
                                allParams.get("externalSource").toUpperCase()));
                    return preds;
                },
                (cb, root) -> Map.of(
                        "created_at", root.get("createdAt"),
                        "severity",   root.get("severity"),
                        "status",     root.get("status")
                ),
                i -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id",             i.getId());
                    m.put("issueRef",       i.getIssueRef());
                    m.put("title",          i.getTitle());
                    m.put("issueType",      i.getIssueType());
                    m.put("severity",       i.getSeverity());
                    m.put("status",         i.getStatus());
                    m.put("category",       i.getCategory()       != null ? i.getCategory()       : "");
                    m.put("sourceModule",   i.getSourceModule()   != null ? i.getSourceModule()   : "");
                    m.put("externalSource", i.getExternalSource() != null ? i.getExternalSource() : "");
                    m.put("externalId",     i.getExternalId()     != null ? i.getExternalId()     : "");
                    m.put("ownerId",        i.getOwnerId());
                    m.put("dueAt",          i.getDueAt());
                    m.put("slaBreached",    i.isSlaBreached());
                    m.put("escalationCount",i.getEscalationCount());
                    m.put("acceptedRisk",   i.isAcceptedRisk());
                    m.put("createdAt",      i.getCreatedAt());
                    m.put("updatedAt",      i.getUpdatedAt());
                    m.put("listScreenKey",  i.getListScreenKey());
                    m.put("detailScreenKey",i.getDetailScreenKey());
                    m.put("workflowInstanceId", i.getWorkflowInstanceId());
                    return m;
                }
        )));
    }

    // ── Get single ────────────────────────────────────────────────────────────

    @GetMapping("/{id}")
    @Operation(summary = "Get a single issue with full detail")
    public ResponseEntity<ApiResponse<IssueResponse>> getById(@PathVariable Long id) {
        // Reachable by URL. A guest is legitimately inside this tenant, so the
        // tenant check below passes for every issue the client has ever raised —
        // including ones from vendor assessments and rival firms' audits. Only the
        // link back to a finding on their own engagement narrows it.
        auditScope.requireIssueVisible(id);
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(issueService.getById(id, tenantId)));
    }

    // ── Update ────────────────────────────────────────────────────────────────

    @PutMapping("/{id}")
    @Operation(summary = "Update an issue — title, description, severity, owner, RCA, remediation")
    public ResponseEntity<ApiResponse<IssueResponse>> update(
            @PathVariable Long id,
            @RequestBody IssueRequest req) {
        var ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(issueService.update(id, req, ctx.getId(), ctx.getTenantId())));
    }

    // ── Status update ─────────────────────────────────────────────────────────

    @PatchMapping("/{id}/status")
    @Operation(summary = "Update issue status — OPEN → TRIAGED → IN_PROGRESS → RESOLVED → CLOSED")
    public ResponseEntity<ApiResponse<IssueResponse>> updateStatus(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        var ctx = utilityService.getLoggedInDataContext();
        requireCanMoveStatus(id, ctx);
        Issue.Status newStatus = Issue.Status.valueOf(body.get("status").toUpperCase());
        // Same completeness rules as the named endpoints: this is how "Send for
        // validation" moves, and a generic PATCH must not be a way round them.
        return ResponseEntity.ok(ApiResponse.success(
                issueService.transition(id, newStatus, null, ctx.getId(), ctx.getTenantId())));
    }

    /** See IssueService.requireCanMoveStatus — the step holder, or an override holder. */
    private void requireCanMoveStatus(Long id, com.kashi.grc.usermanagement.domain.User ctx) {
        java.util.List<String> perms = workflowAccessService.resolvePermissions(ctx);
        boolean override = perms.contains("workflow:step:override") || perms.contains("WORKFLOW_STEP_OVERRIDE");
        issueService.requireCanMoveStatus(id, ctx.getId(), override);
    }

    // ── Dashboard stats ───────────────────────────────────────────────────────

    @GetMapping("/stats")
    @Operation(summary = "Issue dashboard stats — counts by status, severity, SLA breach count")
    public ResponseEntity<ApiResponse<Map<String, Object>>> stats() {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(issueService.getStats(tenantId)));
    }


    // ── Automated ingestion (no JWT — X-Ingest-Token) ─────────────────────────

    /**
     * Accepts automated issue payloads from external tools.
     *
     * Authentication: X-Ingest-Token header (no JWT needed).
     * The token resolves the tenantId. Set app.ingest.token in your .env file.
     *
     * NO external API key, no third-party service needed right now.
     * Just configure the token, point your scanner's webhook here, done.
     *
     * Idempotent: re-posting the same source + externalId updates, not duplicates.
     *
     * Example curl for testing:
     *   curl -X POST http://localhost:8080/v1/issues/ingest \\
     *     -H "X-Ingest-Token: your-token" \\
     *     -H "Content-Type: application/json" \\
     *     -d '{"externalId":"CVE-2024-1234","source":"QUALYS","title":"OpenSSL vuln","cvssScore":9.8}'
     */

    @PostMapping("/ingest")
    @Operation(summary = "Automated issue ingestion from external tools (X-Ingest-Token auth)")
    public ResponseEntity<ApiResponse<IssueResponse>> ingest(
            @RequestHeader(value = "X-Ingest-Token", required = false) String token,
            @Valid @RequestBody IssueIngestRequest req) {

        // Constant-time compare. String.equals returns early on the first differing
        // character, which leaks the shared secret's prefix to anyone able to time
        // the response — and this endpoint is unauthenticated by design.
        boolean tokenOk = token != null && !token.isBlank()
                && ingestToken != null && !ingestToken.isBlank()
                && java.security.MessageDigest.isEqual(
                token.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                ingestToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        if (!tokenOk) {
            log.warn("[INGEST] Rejected — invalid or missing X-Ingest-Token from source={}", req.getSource());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("UNAUTHORIZED", "Invalid or missing X-Ingest-Token"));
        }

        Long tenantId = resolveTenantFromToken(token);
        if (tenantId == null) {
            log.error("[INGEST] Rejected — token does not encode a tenant. Expected " +
                    "kashi.ingest.token in the form tenant_<id>_<secret>. source={}", req.getSource());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("INGEST_TENANT_UNRESOLVED",
                            "Ingest token does not identify a tenant"));
        }
        log.info("[INGEST] Received | source={} | externalId={} | tenantId={}",
                req.getSource(), req.getExternalId(), tenantId);
        IssueResponse response = issueService.ingest(req, tenantId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    // ── Lifecycle transition endpoints ────────────────────────────────────────

    @PostMapping("/{id}/triage")
    @Operation(summary = "Triage issue — OPEN → TRIAGED, sets acknowledgedAt")
    public ResponseEntity<ApiResponse<IssueResponse>> triage(
            @PathVariable Long id,
            @RequestBody(required = false) IssueRequest fields) {
        var ctx = utilityService.getLoggedInDataContext();
        requireCanMoveStatus(id, ctx);
        // The step's own fields ride along — see IssueService.transition.
        IssueResponse resp = issueService.transition(
                id, Issue.Status.TRIAGED, fields, ctx.getId(), ctx.getTenantId());
        log.info("[ISSUE] Triaged | id={} | by={}", id, ctx.getId());
        return ResponseEntity.ok(ApiResponse.success(resp));
    }

    @PostMapping("/{id}/start")
    @Operation(summary = "Start work — TRIAGED → IN_PROGRESS")
    public ResponseEntity<ApiResponse<IssueResponse>> start(
            @PathVariable Long id,
            @RequestBody(required = false) IssueRequest fields) {
        var ctx = utilityService.getLoggedInDataContext();
        requireCanMoveStatus(id, ctx);
        // The step's own fields ride along — see IssueService.transition.
        IssueResponse resp = issueService.transition(
                id, Issue.Status.IN_PROGRESS, fields, ctx.getId(), ctx.getTenantId());
        log.info("[ISSUE] Started | id={} | by={}", id, ctx.getId());
        return ResponseEntity.ok(ApiResponse.success(resp));
    }

    @PostMapping("/{id}/submit-for-review")
    @Operation(summary = "Submit for review — IN_PROGRESS → PENDING_REVIEW")
    public ResponseEntity<ApiResponse<IssueResponse>> submitForReview(
            @PathVariable Long id,
            @RequestBody(required = false) IssueRequest fields) {
        var ctx = utilityService.getLoggedInDataContext();
        requireCanMoveStatus(id, ctx);
        // The step's own fields ride along — see IssueService.transition.
        IssueResponse resp = issueService.transition(
                id, Issue.Status.PENDING_REVIEW, fields, ctx.getId(), ctx.getTenantId());
        log.info("[ISSUE] Submitted for review | id={} | by={}", id, ctx.getId());
        return ResponseEntity.ok(ApiResponse.success(resp));
    }

    @PostMapping("/{id}/resolve")
    @Operation(summary = "Resolve issue — PENDING_REVIEW → RESOLVED, sets remediatedAt")
    public ResponseEntity<ApiResponse<IssueResponse>> resolve(
            @PathVariable Long id,
            @RequestBody(required = false) IssueRequest fields) {
        var ctx = utilityService.getLoggedInDataContext();
        requireCanMoveStatus(id, ctx);
        // The step's own fields ride along — see IssueService.transition.
        IssueResponse resp = issueService.transition(
                id, Issue.Status.RESOLVED, fields, ctx.getId(), ctx.getTenantId());
        log.info("[ISSUE] Resolved | id={} | by={}", id, ctx.getId());
        return ResponseEntity.ok(ApiResponse.success(resp));
    }

    @PostMapping("/{id}/close")
    @Operation(summary = "Close issue — RESOLVED → CLOSED, sets closedAt + closedBy")
    public ResponseEntity<ApiResponse<IssueResponse>> close(
            @PathVariable Long id,
            @RequestBody(required = false) IssueRequest fields) {
        var ctx = utilityService.getLoggedInDataContext();
        requireCanMoveStatus(id, ctx);
        // The step's own fields ride along — see IssueService.transition.
        IssueResponse resp = issueService.transition(
                id, Issue.Status.CLOSED, fields, ctx.getId(), ctx.getTenantId());
        log.info("[ISSUE] Closed | id={} | by={}", id, ctx.getId());
        return ResponseEntity.ok(ApiResponse.success(resp));
    }

    @PostMapping("/{id}/accept-risk")
    @Operation(summary = "Accept risk — sets acceptedRisk flag then OPEN/IN_PROGRESS → ACCEPTED_RISK")
    public ResponseEntity<ApiResponse<IssueResponse>> acceptRisk(
            @PathVariable Long id,
            @RequestBody(required = false) IssueRequest fields) {
        var ctx = utilityService.getLoggedInDataContext();
        // Like every other status move this approves the current step's task,
        // so it needs the same check — it was the one move anyone could make.
        requireCanMoveStatus(id, ctx);
        // acceptedRiskNote is required (IssueService.transition).
        IssueResponse resp = issueService.transition(
                id, Issue.Status.ACCEPTED_RISK, fields, ctx.getId(), ctx.getTenantId());
        log.info("[ISSUE] Risk accepted | id={} | by={}", id, ctx.getId());
        return ResponseEntity.ok(ApiResponse.success(resp));
    }

    /**
     * The validator accepts or rejects one remediation evidence file.
     * Body: { "decision": "ACCEPTED" | "REJECTED", "note": "…" } — a note is
     * required to reject. Only the validation step's task holder.
     */
    @PostMapping("/{id}/evidence/{linkId}/review")
    @Operation(summary = "Accept or reject a remediation evidence file — validation step only")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reviewEvidence(
            @PathVariable Long id,
            @PathVariable Long linkId,
            @RequestBody Map<String, String> body) {
        var ctx = utilityService.getLoggedInDataContext();
        requireCanMoveStatus(id, ctx);
        return ResponseEntity.ok(ApiResponse.success(issueService.reviewEvidence(
                id, linkId, body.get("decision"), body.get("note"), ctx.getId(), ctx.getTenantId())));
    }

    /**
     * Send the fix back to the owner with a reason. Body: { "note": "…" }.
     * The workflow returns to the step that submits for review and the issue
     * goes back to IN_PROGRESS. Only the current step's task holder.
     */
    @PostMapping("/{id}/request-changes")
    @Operation(summary = "Validator sends the remediation back to the owner — IN_PROGRESS, back to the execute step")
    public ResponseEntity<ApiResponse<IssueResponse>> requestChanges(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body) {
        var ctx = utilityService.getLoggedInDataContext();
        requireCanMoveStatus(id, ctx);
        String note = body != null ? body.getOrDefault("note", body.get("changesRequested")) : null;
        return ResponseEntity.ok(ApiResponse.success(
                issueService.requestChanges(id, note, ctx.getId(), ctx.getTenantId())));
    }

    @PostMapping("/{id}/reopen")
    @Operation(summary = "Reopen issue — CLOSED/ACCEPTED_RISK → OPEN + new workflow cycle")
    public ResponseEntity<ApiResponse<IssueResponse>> reopen(@PathVariable Long id) {
        var ctx = utilityService.getLoggedInDataContext();
        IssueResponse resp = issueService.reopen(id, ctx.getId(), ctx.getTenantId());
        log.info("[ISSUE] Reopened | id={} | by={}", id, ctx.getId());
        return ResponseEntity.ok(ApiResponse.success(resp));
    }

    /**
     * Token → tenantId resolution.
     * Simplification for single-tenant deployment: reads tenantId from the token
     * by convention (token = "tenant_{tenantId}_{secret}").
     *
     * Replace with a proper IssueIngestionToken entity lookup for multi-tenant.
     * Format: "tenant_42_secrettoken" → tenantId = 42
     */

    // ─────────────────────────────────────────────────────────────────────────
    // HISTORY — proxies to workflow instance history for this issue
    // Frontend HistoryTab calls GET /{apiBasePath}/{id}/history
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/{id}/history")
    @Operation(summary = "Full chronological history for an issue across ALL workflow cycles")
    public ResponseEntity<ApiResponse<List<WorkflowHistoryResponse>>> getHistory(
            @PathVariable Long id) {
        // Reachable by URL. A guest is legitimately inside this tenant, so the
        // tenant check below passes for every issue the client has ever raised —
        // including ones from vendor assessments and rival firms' audits. Only the
        // link back to a finding on their own engagement narrows it.
        auditScope.requireIssueVisible(id);
        var ctx = utilityService.getLoggedInDataContext();
        // Fetch ALL workflow instances for this issue (covers reopen cycles)
        List<com.kashi.grc.workflow.domain.WorkflowInstance> instances =
                instanceRepository.findByTenantIdAndEntityTypeAndEntityId(
                        ctx.getTenantId(), "ISSUE", id);
        if (instances.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.success(List.of()));
        }
        // BATCHED (was one getFullHistory() call — and one query — per
        // workflow instance/reopen cycle). getFullHistoryForInstances orders
        // by performedAt across the whole set, so cycles still read as one
        // true chronological timeline, not per-cycle concatenated blocks.
        List<Long> instanceIds = instances.stream()
                .map(com.kashi.grc.workflow.domain.WorkflowInstance::getId)
                .toList();
        List<WorkflowHistoryResponse> allHistory =
                workflowEngineService.getFullHistoryForInstances(instanceIds);
        return ResponseEntity.ok(ApiResponse.success(allHistory));
    }

    // ── ADDED: linked-findings endpoints ─────────────────────────────────────

    @GetMapping("/{id}/linked-findings")
    @Operation(summary = "What this issue was raised from — audit findings, or the vendor assessment question")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getLinkedFindings(
            @PathVariable Long id) {

        // Reachable by URL. A guest is legitimately inside this tenant, so the
        // tenant check below passes for every issue the client has ever raised —
        // including ones from vendor assessments and rival firms' audits. Only the
        // link back to a finding on their own engagement narrows it.
        auditScope.requireIssueVisible(id);
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        Issue issue = issueRepository.findById(id)
                .filter(i -> i.getTenantId().equals(tenantId))
                .orElseThrow(() -> new ResourceNotFoundException("Issue", id));

        List<Map<String, Object>> findings = new java.util.ArrayList<>(findingRepository
                .findByLinkedIssueIdAndTenantId(id, tenantId)
                .stream()
                .map(f -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("kind",               "AUDIT_FINDING");
                    m.put("findingId",          f.getId());
                    m.put("findingRef",          f.getFindingRef());
                    m.put("engagementId",        f.getEngagementId());
                    m.put("controlInstanceId",   f.getControlInstanceId());
                    m.put("controlRefSnapshot",  f.getControlRefSnapshot());
                    m.put("title",               f.getTitle());
                    m.put("severity",            f.getSeverity());
                    m.put("findingType",         f.getFindingType());
                    m.put("status",              f.getStatus());
                    m.put("frameworkRef",        f.getFrameworkRef());
                    m.put("raisedAt",            f.getRaisedAt());
                    m.put("remediationPlan",     f.getRemediationPlan());
                    return m;
                })
                .toList());

        // A vendor issue's source is the remediation a reviewer raised on one
        // assessment question. It has no audit_findings row, so this tab said
        // "No linked findings yet" on every vendor issue.
        Map<String, Object> vendorSource = vendorQuestionSource(issue, tenantId);
        if (vendorSource != null) findings.add(0, vendorSource);

        return ResponseEntity.ok(ApiResponse.success(findings));
    }

    /**
     * The assessment question a vendor issue came from: question, answer,
     * reviewer verdict, the remediation that was asked for, and the assessment
     * to link back to. Null when the issue did not come from an assessment.
     */
    private Map<String, Object> vendorQuestionSource(Issue issue, Long tenantId) {
        if (!"ASSESSMENT_REMEDIATION".equals(issue.getSourceEntityType()) || issue.getSourceEntityId() == null)
            return null;
        com.kashi.grc.actionitem.domain.ActionItem item = actionItemRepository.findById(issue.getSourceEntityId())
                .filter(a -> tenantId.equals(a.getTenantId()))
                .orElse(null);
        if (item == null) return null;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind",              "VENDOR_QUESTION");
        m.put("actionItemId",      item.getId());
        m.put("title",             item.getTitle());
        m.put("remediationAsked",  item.getDescription());
        m.put("status",            item.getStatus());
        m.put("severity",          item.getSeverity());
        m.put("assessmentId",      item.getParentEntityId());

        Long qiId = item.getEntityId();
        questionInstanceRepository.findById(qiId).ifPresent(qi -> {
            m.put("questionInstanceId", qi.getId());
            m.put("questionText",       qi.getQuestionTextSnapshot());
            if (qi.getSectionInstanceId() != null) {
                sectionInstanceRepository.findById(qi.getSectionInstanceId())
                        .ifPresent(s -> m.put("sectionName", s.getSectionNameSnapshot()));
            }
            if (item.getParentEntityId() != null) {
                responseRepository.findFirstByAssessmentIdAndQuestionInstanceIdOrderByIdDesc(
                                item.getParentEntityId(), qi.getId())
                        .ifPresent(r -> {
                            m.put("answer",         describeAnswer(r));
                            m.put("reviewerStatus", r.getReviewerStatus());
                        });
            }
        });
        return m;
    }

    /** The answer as a person would read it: the chosen option(s), or the text. */
    private String describeAnswer(com.kashi.grc.assessment.domain.AssessmentResponse r) {
        if (r.getSelectedOptionInstanceId() != null) {
            return optionInstanceRepository.findById(r.getSelectedOptionInstanceId())
                    .map(o -> o.getOptionValue()).orElse(null);
        }
        String t = r.getResponseText();
        if (t == null || t.isBlank()) return null;
        if (t.startsWith("[FILE_UPLOADED")) return "File uploaded";
        if (t.startsWith("[")) {
            try {
                Long[] ids = new com.fasterxml.jackson.databind.ObjectMapper().readValue(t, Long[].class);
                return java.util.Arrays.stream(ids)
                        .map(oid -> optionInstanceRepository.findById(oid).map(o -> o.getOptionValue()).orElse(null))
                        .filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.joining(", "));
            } catch (Exception ignored) { return t; }
        }
        return t;
    }

    @PostMapping("/{id}/link-finding")
    @Operation(summary = "Link an existing audit finding to this issue")
    public ResponseEntity<ApiResponse<Void>> linkFinding(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {

        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        Long findingId = body.get("findingId") instanceof Number n ? n.longValue()
                : Long.parseLong(body.get("findingId").toString());

        issueRepository.findById(id)
                .filter(i -> i.getTenantId().equals(tenantId))
                .orElseThrow(() -> new ResourceNotFoundException("Issue", id));

        AuditFinding finding = findingRepository.findByIdAndTenantId(findingId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditFinding", findingId));

        finding.setLinkedIssueId(id);
        findingRepository.save(finding);

        log.info("[ISSUE] Finding linked | issueId={} findingId={}", id, findingId);
        return ResponseEntity.ok(ApiResponse.success());
    }

    /**
     * Tenant encoded in the ingest token, or null when it is not.
     *
     * WAS: fell back to {@code return 1L}. A token in any other shape silently
     * wrote every ingested issue into tenant 1 — the platform admin tenant — where
     * it sat in someone else's issue register looking legitimate. Landing another
     * organisation's vulnerability data in the wrong tenant is worse than the
     * integration failing loudly, so the caller now gets a 401 and the operator
     * gets a log line naming the expected format.
     */
    private Long resolveTenantFromToken(String token) {
        try {
            String[] parts = token.split("_");
            if (parts.length >= 2 && "tenant".equals(parts[0])) {
                return Long.parseLong(parts[1]);
            }
        } catch (Exception ignored) {}
        return null;
    }
}