package com.kashi.grc.audit.controller;

import com.kashi.grc.audit.domain.AuditControlInstance;
import com.kashi.grc.audit.domain.AuditEngagement;
import com.kashi.grc.audit.domain.AuditFinding;
import com.kashi.grc.audit.repository.AuditControlInstanceRepository;
import com.kashi.grc.audit.repository.AuditEngagementRepository;
import com.kashi.grc.audit.repository.AuditFindingRepository;
import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.dto.PaginatedResponse;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.repository.DbRepository;
import com.kashi.grc.common.util.UtilityService;
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
 *   DELETE /v1/audit/findings/{id}                    — soft-delete (CANCELLED)
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "Audit Findings", description = "Raise, track, and close audit findings per engagement")
public class AuditFindingController {

    private final AuditFindingRepository       findingRepository;
    private final AuditEngagementRepository    engagementRepository;
    private final AuditControlInstanceRepository controlInstanceRepository;
    private final DbRepository                 dbRepository;
    private final UtilityService               utilityService;

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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> toMap(AuditFinding f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",                 f.getId());
        m.put("findingRef",         f.getFindingRef());
        m.put("engagementId",       f.getEngagementId());
        m.put("controlInstanceId",  f.getControlInstanceId());
        m.put("controlRefSnapshot", f.getControlRefSnapshot());
        m.put("title",              f.getTitle());
        m.put("description",        f.getDescription());
        m.put("rootCause",          f.getRootCause());
        m.put("recommendation",     f.getRecommendation());
        m.put("auditorNotes",       f.getAuditorNotes());
        m.put("severity",           f.getSeverity());
        m.put("findingType",        f.getFindingType());
        m.put("status",             f.getStatus());
        m.put("frameworkRef",       f.getFrameworkRef());
        m.put("raisedBy",           f.getRaisedBy());
        m.put("ownerId",            f.getOwnerId());
        m.put("dueAt",              f.getDueAt());
        m.put("raisedAt",           f.getRaisedAt());
        m.put("remediationPlan",    f.getRemediationPlan());
        m.put("remediationType",    f.getRemediationType());
        m.put("remediationStartedAt", f.getRemediationStartedAt());
        m.put("remediatedAt",       f.getRemediatedAt());
        m.put("validatedAt",        f.getValidatedAt());
        m.put("closedAt",           f.getClosedAt());
        m.put("acceptedRisk",       f.isAcceptedRisk());
        m.put("acceptedRiskNote",   f.getAcceptedRiskNote());
        m.put("linkedIssueId",      f.getLinkedIssueId());
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