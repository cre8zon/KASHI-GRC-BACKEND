package com.kashi.grc.evidence.controller;

import com.kashi.grc.audit.service.AuditEvidenceBackfillService;
import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.evidence.service.KashiLinkQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * KashiLink — observability for the evidence reuse engine, plus the pull action.
 *
 * The engine's failure mode is silence: a mistyped tag returns an empty list and
 * logs newLinks=0, which is byte-identical to "worked correctly, nothing to
 * link". These endpoints make that difference visible.
 *
 *   GET  /v1/kashilink/stats     headline numbers for the overview cards
 *   GET  /v1/kashilink/coverage  per-tag census across every tag-carrying entity
 *   GET  /v1/kashilink/gaps      untagged instances + orphan evidence
 *   GET  /v1/kashilink/engagements/{id}/pull/preview
 *   POST /v1/kashilink/engagements/{id}/pull
 *
 * All querying lives in KashiLinkQueryService (JPA Criteria API), consistent with
 * the *RepositoryImpl fragments used across the codebase.
 */
@Slf4j
@RestController
@RequestMapping("/v1/kashilink")
@Tag(name = "KashiLink", description = "Evidence reuse coverage and tag health")
@RequiredArgsConstructor
public class KashiLinkController {

    private final KashiLinkQueryService        queryService;
    private final AuditEvidenceBackfillService backfillService;
    private final UtilityService               utilityService;

    // ── Overview ─────────────────────────────────────────────────────────────

    @GetMapping("/stats")
    @Operation(summary = "Headline reuse metrics for this tenant")
    public ResponseEntity<ApiResponse<Map<String, Object>>> stats() {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(queryService.stats(tenantId)));
    }

    // ── Per-tag coverage ─────────────────────────────────────────────────────

    @GetMapping("/coverage")
    @Operation(summary = "Every tag in circulation and where it appears",
            description = "One row per tag. controlInstances/testInstances/policyInstances are "
                    + "what the matcher can actually reach; evidenceRecords is what has been "
                    + "filed under it. A tag with evidence but zero instances is drift.")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> coverage() {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(queryService.coverage(tenantId)));
    }

    // ── Gaps ─────────────────────────────────────────────────────────────────

    @GetMapping("/gaps")
    @Operation(summary = "Instances with no tag, and evidence that matched nothing")
    public ResponseEntity<ApiResponse<Map<String, Object>>> gaps() {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(queryService.gaps(tenantId)));
    }

    // ── Pull existing evidence into an engagement ─────────────────────────────

    /**
     * Preview what a backfill would link. Writes nothing.
     *
     * Propagation is push-only, so an engagement instantiated today starts empty
     * even when the tenant already holds evidence under the same tags. This is
     * the pull side — deliberately an explicit action rather than automatic,
     * because prior-period evidence appearing in scope without a documented
     * decision is exactly what a peer reviewer objects to.
     */
    @GetMapping("/engagements/{engagementId}/pull/preview")
    @Operation(summary = "Preview which existing evidence would link into this engagement")
    public ResponseEntity<ApiResponse<Map<String, Object>>> previewPull(
            @PathVariable Long engagementId) {

        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(
                backfillService.preview(engagementId, tenantId)));
    }

    /**
     * Execute the backfill. Every link is created as PENDING_REVIEW — the auditor
     * still decides, per link, whether prior-period evidence satisfies this period.
     */
    @PostMapping("/engagements/{engagementId}/pull")
    @Operation(summary = "Link existing tenant evidence into this engagement for review")
    public ResponseEntity<ApiResponse<Map<String, Object>>> pull(
            @PathVariable Long engagementId) {

        var ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                backfillService.backfill(engagementId, ctx.getTenantId(), ctx.getId())));
    }
}