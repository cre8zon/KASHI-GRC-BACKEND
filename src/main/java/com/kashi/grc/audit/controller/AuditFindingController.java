package com.kashi.grc.audit.controller;

import com.kashi.grc.audit.domain.AuditControlInstance;
import com.kashi.grc.audit.domain.AuditEngagement;
import com.kashi.grc.audit.domain.AuditFinding;
import com.kashi.grc.audit.repository.AuditControlInstanceRepository;
import com.kashi.grc.audit.repository.AuditEngagementRepository;
import com.kashi.grc.audit.repository.AuditFindingRepository;
import com.kashi.grc.audit.service.AuditTestPolicySnapshotService;
import com.kashi.grc.audit.service.ComplianceScoreService;
import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.dto.PaginatedResponse;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.repository.DbRepository;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.issue.domain.Issue;
import com.kashi.grc.issue.dto.IssueRequest;
import com.kashi.grc.issue.dto.IssueResponse;
import com.kashi.grc.issue.service.IssueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.Year;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

/**
 * AuditFindingController — CRUD + lifecycle for AuditFindings.
 *
 * ENDPOINTS:
 *   POST   /v1/audit/findings                         — raise a finding
 *   GET    /v1/audit/findings                         — list all findings (tenant-scoped, filterable)
 *   GET    /v1/audit/engagements/{id}/findings        — findings for one engagement
 *   GET    /v1/audit/findings/{id}                    — get one finding
 *   PUT    /v1/audit/findings/{id}                    — update finding content
 *   POST   /v1/audit/findings/{id}/start-remediation  — OPEN → IN_REMEDIATION
 *   POST   /v1/audit/findings/{id}/validate           — IN_REMEDIATION → PENDING_VALIDATION → CLOSED
 *   POST   /v1/audit/findings/{id}/accept-risk        — OPEN/IN_REMEDIATION → ACCEPTED_RISK
 *   POST   /v1/audit/findings/{id}/close              — manual close (any status)
 *   POST   /v1/audit/findings/{id}/reopen             — CLOSED → OPEN
 *   POST   /v1/audit/findings/{id}/escalate-to-issue  — create linked Issue in Issue Management
 *   DELETE /v1/audit/findings/{id}                    — soft-delete (CANCELLED)
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "Audit Findings", description = "Raise, track, and close audit findings per engagement")
public class AuditFindingController {

    private final AuditFindingRepository         findingRepository;
    private final com.kashi.grc.workflow.repository.WorkflowRepository workflowRepository;
    private final AuditEngagementRepository      engagementRepository;
    private final AuditControlInstanceRepository controlInstanceRepository;
    private final DbRepository                   dbRepository;
    private final UtilityService                 utilityService;
    // EXISTING
    private final IssueService                   issueService;
    private final AuditTestPolicySnapshotService snapshotService;
    // ADDED: full finding-aware compliance recalculation (Vanta/AuditBoard model)
    private final ComplianceScoreService         complianceScoreService;

    // ── CREATE ────────────────────────────────────────────────────────────────

    @PostMapping("/v1/audit/findings")
    @Operation(summary = "Raise a new audit finding")
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @RequestBody Map<String, Object> body) {

        var ctx      = utilityService.getLoggedInDataContext();
        Long tenantId = ctx.getTenantId();
        Long userId   = ctx.getId();

        Long engagementId      = getLong(body, "engagementId");
        Long controlInstanceId = getLong(body, "controlInstanceId");

        // Verify engagement belongs to tenant
        engagementRepository.findById(engagementId)
                .filter(e -> e.getTenantId().equals(tenantId))
                .orElseThrow(() -> new ResourceNotFoundException("AuditEngagement", engagementId));

        // Snapshot control ref if provided
        String controlRefSnapshot = null;
        if (controlInstanceId != null) {
            controlRefSnapshot = controlInstanceRepository.findById(controlInstanceId)
                    .map(AuditControlInstance::getControlCodeSnapshot)
                    .orElse(null);
        }

        AuditFinding finding = AuditFinding.builder()
                .tenantId(tenantId)
                .findingRef(generateRef(tenantId))
                .engagementId(engagementId)
                .controlInstanceId(controlInstanceId)
                .controlRefSnapshot(controlRefSnapshot)
                .title(getString(body, "title"))
                .description(getString(body, "description"))
                .rootCause(getString(body, "rootCause"))
                .recommendation(getString(body, "recommendation"))
                .auditorNotes(getString(body, "auditorNotes"))
                .severity(resolveEnum(body, "severity", AuditFinding.Severity.class, AuditFinding.Severity.MEDIUM))
                .findingType(resolveEnum(body, "findingType", AuditFinding.FindingType.class, AuditFinding.FindingType.CONTROL_DEFICIENCY))
                .frameworkRef(getString(body, "frameworkRef"))
                .raisedBy(userId)
                .ownerId(getLong(body, "ownerId"))
                .dueAt(body.get("dueAt") != null ? LocalDateTime.parse(body.get("dueAt").toString()) : null)
                .status(AuditFinding.Status.OPEN)
                .raisedAt(LocalDateTime.now())
                .build();

        findingRepository.save(finding);
        log.info("[AUDIT-FINDING] Created | ref={} | engagementId={} | by={}", finding.getFindingRef(), engagementId, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(toMap(finding)));
    }

    // ── LIST (global, filterable) ─────────────────────────────────────────────

    @GetMapping("/v1/audit/findings")
    @Operation(summary = "List all findings for this tenant — filterable by status, severity, engagementId")
    public ResponseEntity<ApiResponse<PaginatedResponse<Map<String, Object>>>> list(
            @RequestParam Map<String, String> allParams) {

        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();

        return ResponseEntity.ok(ApiResponse.success(dbRepository.findAll(
                AuditFinding.class,
                utilityService.getpageDetails(allParams),
                (cb, root) -> {
                    var preds = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
                    preds.add(cb.equal(root.get("tenantId"), tenantId));
                    if (allParams.containsKey("engagementId"))
                        preds.add(cb.equal(root.get("engagementId"), Long.parseLong(allParams.get("engagementId"))));
                    if (allParams.containsKey("status"))
                        preds.add(cb.equal(root.get("status"),
                                AuditFinding.Status.valueOf(allParams.get("status").toUpperCase())));
                    if (allParams.containsKey("severity"))
                        preds.add(cb.equal(root.get("severity"),
                                AuditFinding.Severity.valueOf(allParams.get("severity").toUpperCase())));
                    if (allParams.containsKey("controlInstanceId"))
                        preds.add(cb.equal(root.get("controlInstanceId"), Long.parseLong(allParams.get("controlInstanceId"))));
                    // frameworkRef scopes the SHARED findings list to one framework
                    // (e.g. ISO27001) so a tenant reaching it via a framework-specific
                    // nav row sees only that framework's findings.
                    if (allParams.containsKey("frameworkRef")
                            && !allParams.get("frameworkRef").isBlank())
                        preds.add(cb.equal(root.get("frameworkRef"), allParams.get("frameworkRef")));
                    return preds;
                },
                (cb, root) -> Map.of(
                        "title",       root.get("title"),
                        "findingRef",  root.get("findingRef"),
                        "status",      root.get("status"),
                        "severity",    root.get("severity"),
                        "raisedAt",    root.get("raisedAt")
                ),
                this::toMap
        )));
    }

    // ── LIST by engagement ────────────────────────────────────────────────────

    @GetMapping("/v1/audit/engagements/{engagementId}/findings")
    @Operation(summary = "List findings for a specific engagement")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listByEngagement(
            @PathVariable Long engagementId) {

        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        List<Map<String, Object>> result = findingRepository
                .findByEngagementIdAndTenantId(engagementId, tenantId)
                .stream().map(this::toMap).toList();
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // ── GET one ───────────────────────────────────────────────────────────────

    @GetMapping("/v1/audit/findings/{id}")
    @Operation(summary = "Get a single finding by ID")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getById(@PathVariable Long id) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        AuditFinding f = findingRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditFinding", id));
        return ResponseEntity.ok(ApiResponse.success(toMap(f)));
    }

    // ── UPDATE content ────────────────────────────────────────────────────────

    @PutMapping("/v1/audit/findings/{id}")
    @Operation(summary = "Update finding content (title, description, root cause, recommendation)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> update(
            @PathVariable Long id, @RequestBody Map<String, Object> body) {

        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        AuditFinding f = findingRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditFinding", id));

        if (body.containsKey("title"))          f.setTitle(getString(body, "title"));
        if (body.containsKey("description"))    f.setDescription(getString(body, "description"));
        if (body.containsKey("rootCause"))      f.setRootCause(getString(body, "rootCause"));
        if (body.containsKey("recommendation")) f.setRecommendation(getString(body, "recommendation"));
        if (body.containsKey("auditorNotes"))   f.setAuditorNotes(getString(body, "auditorNotes"));
        if (body.containsKey("remediationPlan"))f.setRemediationPlan(getString(body, "remediationPlan"));
        if (body.containsKey("remediationType"))f.setRemediationType(getString(body, "remediationType"));
        if (body.containsKey("ownerId"))        f.setOwnerId(getLong(body, "ownerId"));
        if (body.containsKey("dueAt") && body.get("dueAt") != null)
            f.setDueAt(LocalDateTime.parse(body.get("dueAt").toString()));
        if (body.containsKey("severity"))
            f.setSeverity(AuditFinding.Severity.valueOf(body.get("severity").toString().toUpperCase()));

        findingRepository.save(f);
        log.info("[AUDIT-FINDING] Updated | id={}", id);
        return ResponseEntity.ok(ApiResponse.success(toMap(f)));
    }

    // ── LIFECYCLE TRANSITIONS ─────────────────────────────────────────────────

    @PostMapping("/v1/audit/findings/{id}/start-remediation")
    @Operation(summary = "Start remediation — OPEN → IN_REMEDIATION")
    public ResponseEntity<ApiResponse<Map<String, Object>>> startRemediation(
            @PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {

        var ctx = utilityService.getLoggedInDataContext();
        AuditFinding f = findingRepository.findByIdAndTenantId(id, ctx.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("AuditFinding", id));

        f.setStatus(AuditFinding.Status.IN_REMEDIATION);
        f.setRemediationStartedAt(LocalDateTime.now());
        if (body != null && body.containsKey("remediationPlan"))
            f.setRemediationPlan(getString(body, "remediationPlan"));
        if (body != null && body.containsKey("remediationType"))
            f.setRemediationType(getString(body, "remediationType"));

        findingRepository.save(f);
        log.info("[AUDIT-FINDING] Remediation started | id={} | by={}", id, ctx.getId());
        return ResponseEntity.ok(ApiResponse.success(Map.of("id", id, "status", f.getStatus())));
    }

    @PostMapping("/v1/audit/findings/{id}/validate")
    @Operation(summary = "Validate remediation — IN_REMEDIATION → CLOSED")
    public ResponseEntity<ApiResponse<Map<String, Object>>> validate(
            @PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {

        var ctx = utilityService.getLoggedInDataContext();
        AuditFinding f = findingRepository.findByIdAndTenantId(id, ctx.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("AuditFinding", id));

        f.setStatus(AuditFinding.Status.CLOSED);
        f.setValidatedAt(LocalDateTime.now());
        f.setValidatedBy(ctx.getId());
        f.setRemediatedAt(LocalDateTime.now());
        f.setRemediatedBy(ctx.getId());
        f.setClosedAt(LocalDateTime.now());
        f.setClosedBy(ctx.getId());

        findingRepository.save(f);
        // ADDED: full compliance recalculation (finding-aware: open/accepted/closed counts)
        complianceScoreService.syncEngagementScore(f.getEngagementId(), ctx.getTenantId());
        log.info("[AUDIT-FINDING] Validated/closed | id={} | by={}", id, ctx.getId());
        return ResponseEntity.ok(ApiResponse.success(Map.of("id", id, "status", f.getStatus())));
    }

    @PostMapping("/v1/audit/findings/{id}/accept-risk")
    @Operation(summary = "Accept risk — skips remediation, OPEN → ACCEPTED_RISK")
    public ResponseEntity<ApiResponse<Map<String, Object>>> acceptRisk(
            @PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {

        var ctx = utilityService.getLoggedInDataContext();
        AuditFinding f = findingRepository.findByIdAndTenantId(id, ctx.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("AuditFinding", id));

        f.setStatus(AuditFinding.Status.ACCEPTED_RISK);
        f.setAcceptedRisk(true);
        f.setAcceptedRiskBy(ctx.getId());
        f.setAcceptedRiskAt(LocalDateTime.now());
        if (body != null) f.setAcceptedRiskNote(getString(body, "acceptedRiskNote"));

        findingRepository.save(f);
        // ADDED: full compliance recalculation — accepted risk improves compliancePct
        complianceScoreService.syncEngagementScore(f.getEngagementId(), ctx.getTenantId());
        log.info("[AUDIT-FINDING] Risk accepted | id={} | by={}", id, ctx.getId());
        return ResponseEntity.ok(ApiResponse.success(Map.of("id", id, "status", f.getStatus())));
    }

    @PostMapping("/v1/audit/findings/{id}/close")
    @Operation(summary = "Manually close a finding")
    public ResponseEntity<ApiResponse<Map<String, Object>>> close(@PathVariable Long id) {
        var ctx = utilityService.getLoggedInDataContext();
        AuditFinding f = findingRepository.findByIdAndTenantId(id, ctx.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("AuditFinding", id));
        f.setStatus(AuditFinding.Status.CLOSED);
        f.setClosedAt(LocalDateTime.now());
        f.setClosedBy(ctx.getId());
        findingRepository.save(f);
        // ADDED: full compliance recalculation
        complianceScoreService.syncEngagementScore(f.getEngagementId(), ctx.getTenantId());
        return ResponseEntity.ok(ApiResponse.success(Map.of("id", id, "status", f.getStatus())));
    }

    @PostMapping("/v1/audit/findings/{id}/reopen")
    @Operation(summary = "Reopen a closed finding")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reopen(@PathVariable Long id) {
        var ctx = utilityService.getLoggedInDataContext();
        AuditFinding f = findingRepository.findByIdAndTenantId(id, ctx.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("AuditFinding", id));
        f.setStatus(AuditFinding.Status.OPEN);
        f.setClosedAt(null); f.setClosedBy(null);
        findingRepository.save(f);
        // ADDED: full compliance recalculation — reopening reduces compliance
        complianceScoreService.syncEngagementScore(f.getEngagementId(), ctx.getTenantId());
        return ResponseEntity.ok(ApiResponse.success(Map.of("id", id, "status", f.getStatus())));
    }

    @DeleteMapping("/v1/audit/findings/{id}")
    @Operation(summary = "Delete a finding (hard delete — use with caution)")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        AuditFinding f = findingRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditFinding", id));
        findingRepository.delete(f);
        return ResponseEntity.ok(ApiResponse.success());
    }

    // ── EXISTING: escalate finding to Issue Management ────────────────────────

    @PostMapping("/v1/audit/findings/{id}/escalate-to-issue")
    @Operation(summary = "Escalate finding — creates a linked Issue in Issue Management")
    public ResponseEntity<ApiResponse<Map<String, Object>>> escalateToIssue(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body) {

        var ctx       = utilityService.getLoggedInDataContext();
        Long tenantId = ctx.getTenantId();
        Long userId   = ctx.getId();

        AuditFinding finding = findingRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditFinding", id));

        if (finding.getLinkedIssueId() != null) {
            throw new BusinessException("ALREADY_ESCALATED",
                    "This finding is already linked to Issue #" + finding.getLinkedIssueId());
        }

        IssueRequest req = new IssueRequest();
        req.setTitle(finding.getTitle());
        req.setDescription(finding.getDescription() != null ? finding.getDescription()
                : "Escalated from audit finding " + finding.getFindingRef());
        req.setIssueType(Issue.IssueType.EXTERNAL);
        req.setSeverity(mapFindingSeverityToIssueSeverity(finding.getSeverity()));
        req.setSourceModule("AUDIT");
        req.setSourceEntityType("AUDIT_FINDING");
        req.setSourceEntityId(finding.getId());
        req.setFrameworkRef(finding.getFrameworkRef());
        // Owner is who must remediate. Findings raised before the auditee-side
        // resolution existed have owner_id NULL, and IssueService now refuses a
        // null owner — so without this fallback the Escalate button fails on
        // every historical finding with "ownerId is required".
        //
        // Falls back to the engagement's evidence lead, never to an auditor: an
        // auditor remediating the control they tested is the situation an audit
        // exists to prevent.
        Long owner = finding.getOwnerId();
        if (owner == null) {
            owner = engagementRepository.findById(finding.getEngagementId())
                    .map(e -> e.getLeadAuditeeId() != null ? e.getLeadAuditeeId() : e.getOwnerId())
                    .orElse(null);
            if (owner == null) {
                throw new BusinessException("NO_OWNER",
                        "This finding has no owner and the engagement has no lead auditee. "
                                + "Name a lead auditee on the engagement, or set an owner on the "
                                + "finding, then escalate.");
            }
            // Persist it, so the finding itself stops being ownerless.
            finding.setOwnerId(owner);
        }
        req.setOwnerId(owner);
        // Workflow: caller may override, otherwise resolve the finding workflow by
        // name. Previously this defaulted to null when the UI sent no body — and
        // the button sends none — so escalated issues were created with NO
        // workflow at all. They appeared in the issue list and then sat there,
        // because nothing had created a task for anyone.
        if (body != null && body.get("workflowId") instanceof Number n) {
            req.setWorkflowId(n.longValue());
        } else {
            req.setWorkflowId(findingWorkflowId(tenantId));
        }

        IssueResponse issueResponse = issueService.create(req, userId, tenantId);

        finding.setLinkedIssueId(issueResponse.getId());
        findingRepository.save(finding);

        if (finding.getControlInstanceId() != null) {
            controlInstanceRepository.findById(finding.getControlInstanceId()).ifPresent(ctrl -> {
                ctrl.setFindingLinked(true);
                ctrl.setFindingIssueId(issueResponse.getId());
                controlInstanceRepository.save(ctrl);
            });
        }

        // Sync both snapshot score and compliance score after escalation
        snapshotService.syncEngagementScore(finding.getEngagementId(), tenantId);

        log.info("[AUDIT-FINDING] Escalated to issue | findingId={} issueId={} by={}",
                id, issueResponse.getId(), userId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("findingId",     id);
        result.put("findingRef",    finding.getFindingRef());
        result.put("linkedIssueId", issueResponse.getId());
        result.put("issueRef",      issueResponse.getIssueRef());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(result));
    }

    /**
     * Workflow for issues escalated from a finding, resolved by name.
     *
     * Same lookup the automatic path uses, so a manually escalated finding and an
     * auto-escalated one follow identical steps. Falls back to the generic issue
     * workflow when the finding workflow has not been seeded.
     */
    private Long findingWorkflowId(Long tenantId) {
        return workflowRepository.findAll().stream()
                .filter(w -> w.isActive())
                .filter(w -> "ISSUE".equalsIgnoreCase(w.getEntityType()))
                .filter(w -> w.getTenantId() == null || w.getTenantId().equals(tenantId))
                .filter(w -> "Audit Finding Remediation".equalsIgnoreCase(w.getName()))
                .map(w -> w.getId())
                .findFirst()
                .orElse(15L);
    }

    private Issue.Severity mapFindingSeverityToIssueSeverity(AuditFinding.Severity s) {
        if (s == null) return Issue.Severity.MEDIUM;
        return switch (s) {
            case CRITICAL           -> Issue.Severity.CRITICAL;
            case HIGH               -> Issue.Severity.HIGH;
            case MEDIUM             -> Issue.Severity.MEDIUM;
            case LOW, INFORMATIONAL -> Issue.Severity.LOW;
        };
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> toMap(AuditFinding f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",                   f.getId());
        m.put("findingRef",           f.getFindingRef());
        m.put("engagementId",         f.getEngagementId());
        m.put("controlInstanceId",    f.getControlInstanceId());
        m.put("controlRefSnapshot",   f.getControlRefSnapshot());
        m.put("title",                f.getTitle());
        m.put("description",          f.getDescription());
        m.put("rootCause",            f.getRootCause());
        m.put("recommendation",       f.getRecommendation());
        m.put("auditorNotes",         f.getAuditorNotes());
        m.put("severity",             f.getSeverity());
        m.put("findingType",          f.getFindingType());
        // Lets the UI distinguish "automation could not finish" from "nobody has
        // escalated this yet" — identical on screen without it.
        m.put("source",               f.getSource());
        m.put("status",               f.getStatus());
        m.put("frameworkRef",         f.getFrameworkRef());
        m.put("raisedBy",             f.getRaisedBy());
        m.put("ownerId",              f.getOwnerId());
        m.put("dueAt",                f.getDueAt());
        m.put("raisedAt",             f.getRaisedAt());
        m.put("remediationPlan",      f.getRemediationPlan());
        m.put("remediationType",      f.getRemediationType());
        m.put("remediationStartedAt", f.getRemediationStartedAt());
        m.put("remediatedAt",         f.getRemediatedAt());
        m.put("validatedAt",          f.getValidatedAt());
        m.put("closedAt",             f.getClosedAt());
        m.put("acceptedRisk",         f.isAcceptedRisk());
        m.put("acceptedRiskNote",     f.getAcceptedRiskNote());
        m.put("linkedIssueId",        f.getLinkedIssueId());
        return m;
    }

    private String generateRef(Long tenantId) {
        long count = findingRepository.countByTenantId(tenantId) + 1;
        String candidate = String.format("FND-%d-%04d", Year.now().getValue(), count);
        while (findingRepository.existsByFindingRefAndTenantId(candidate, tenantId)) {
            candidate = String.format("FND-%d-%04d", Year.now().getValue(), ++count);
        }
        return candidate;
    }

    private String getString(Map<String, Object> body, String key) {
        return body.get(key) != null ? body.get(key).toString() : null;
    }

    private Long getLong(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null) return null;
        return v instanceof Number n ? n.longValue() : Long.parseLong(v.toString());
    }

    private <E extends Enum<E>> E resolveEnum(Map<String, Object> body, String key,
                                              Class<E> clazz, E defaultVal) {
        Object v = body.get(key);
        if (v == null) return defaultVal;
        try { return Enum.valueOf(clazz, v.toString().toUpperCase()); }
        catch (IllegalArgumentException e) { return defaultVal; }
    }
}