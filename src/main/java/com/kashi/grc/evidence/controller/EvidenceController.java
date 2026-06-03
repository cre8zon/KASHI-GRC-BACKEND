package com.kashi.grc.evidence.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.evidence.dto.request.EvidenceLinkReviewRequest;
import com.kashi.grc.evidence.dto.request.EvidenceRecordRequest;
import com.kashi.grc.evidence.dto.request.ManualEvidenceLinkRequest;
import com.kashi.grc.evidence.dto.response.EvidenceLinkResponse;
import com.kashi.grc.evidence.dto.response.EvidenceRecordResponse;
import com.kashi.grc.evidence.service.EvidenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * EvidenceController — REST API for the evidence reuse engine.
 *
 * ENDPOINTS:
 *
 *   POST   /v1/evidence                          — upload a new evidence record + trigger propagation
 *   GET    /v1/evidence                          — list all evidence for tenant (filterable by controlTag)
 *   GET    /v1/evidence/{id}                     — get evidence record with all its links
 *   DELETE /v1/evidence/{id}                     — soft-delete (marks expired, no file deletion)
 *
 *   GET    /v1/evidence/links                    — get all links for a target entity
 *                                                  ?entityType=AUDIT_CONTROL_INSTANCE&entityId=42
 *   POST   /v1/evidence/{id}/links               — manually link evidence to an entity
 *   PATCH  /v1/evidence/links/{linkId}/review    — accept or reject an auto-linked evidence
 *   GET    /v1/evidence/links/pending            — all PENDING_REVIEW auto-links for this tenant
 *                                                  (used by evidence review inbox)
 *
 * USAGE FROM CONTROL DETAIL PAGE:
 *   1. Page loads → GET /v1/evidence/links?entityType=AUDIT_CONTROL_INSTANCE&entityId={id}
 *      → shows all evidence linked (accepted, pending, rejected)
 *   2. Auditor uploads new evidence → POST /v1/evidence (with controlTag)
 *      → engine propagates to all matching controls async
 *   3. Auditor sees PENDING_REVIEW badge → PATCH /v1/evidence/links/{linkId}/review
 *      → accepts or rejects
 *   4. Auditor manually links old evidence → POST /v1/evidence/{evidenceId}/links
 *      → immediately ACCEPTED
 */
@Slf4j
@RestController
@RequestMapping("/v1/evidence")
@Tag(name = "Evidence", description = "Cross-module evidence reuse and compliance propagation")
@RequiredArgsConstructor
public class EvidenceController {

    private final EvidenceService  service;
    private final UtilityService   utilityService;

    // ── Evidence records ──────────────────────────────────────────────────────

    @PostMapping
    @Operation(summary = "Upload evidence record and trigger cross-module propagation")
    public ResponseEntity<ApiResponse<EvidenceRecordResponse>> create(
            @Valid @RequestBody EvidenceRecordRequest req) {

        var ctx = utilityService.getLoggedInDataContext();
        EvidenceRecordResponse response = service.create(req, ctx.getId(), ctx.getTenantId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @GetMapping
    @Operation(summary = "List evidence records for this tenant",
               description = "Filter by controlTag to see all evidence for a specific tag.")
    public ResponseEntity<ApiResponse<List<EvidenceRecordResponse>>> list(
            @RequestParam(required = false) String controlTag) {

        var ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
            service.listForTenant(ctx.getTenantId(), controlTag)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get evidence record detail with all linked entities")
    public ResponseEntity<ApiResponse<EvidenceRecordResponse>> getById(@PathVariable Long id) {
        var ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(service.getById(id, ctx.getTenantId())));
    }

    // ── Links ─────────────────────────────────────────────────────────────────

    @GetMapping("/links")
    @Operation(summary = "Get all evidence links for a specific entity",
               description = "Pass entityType and entityId as query params. " +
                             "Used by control detail, question detail, and issue detail pages " +
                             "to show what evidence satisfies this entity.")
    public ResponseEntity<ApiResponse<List<EvidenceLinkResponse>>> getLinksForEntity(
            @RequestParam String entityType,
            @RequestParam Long   entityId) {

        var ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
            service.getLinksForEntity(entityType, entityId, ctx.getTenantId())));
    }

    @GetMapping("/links/pending")
    @Operation(summary = "Get all PENDING_REVIEW auto-linked evidence for this tenant",
               description = "Used by the evidence review inbox and notification badge.")
    public ResponseEntity<ApiResponse<List<EvidenceLinkResponse>>> getPendingReview() {
        var ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(service.getPendingReview(ctx.getTenantId())));
    }

    @PostMapping("/{id}/links")
    @Operation(summary = "Manually link evidence to a target entity",
               description = "Creates an immediately ACCEPTED link — no review needed for manual links.")
    public ResponseEntity<ApiResponse<EvidenceLinkResponse>> manualLink(
            @PathVariable Long id,
            @Valid @RequestBody ManualEvidenceLinkRequest req) {

        var ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
            service.manualLink(id, req, ctx.getId(), ctx.getTenantId())));
    }

    @PatchMapping("/links/{linkId}/review")
    @Operation(summary = "Accept or reject an auto-linked evidence",
               description = "Called by auditor/reviewer from the control detail page. " +
                             "ACCEPT = evidence satisfies this control. REJECT = it does not.")
    public ResponseEntity<ApiResponse<EvidenceLinkResponse>> reviewLink(
            @PathVariable Long linkId,
            @Valid @RequestBody EvidenceLinkReviewRequest req) {

        var ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
            service.reviewLink(linkId, req, ctx.getId(), ctx.getTenantId())));
    }

    @GetMapping("/stats")
    @Operation(summary = "Evidence coverage stats for dashboard")
    public ResponseEntity<ApiResponse<Map<String, Object>>> stats() {
        var ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(Map.of(
            "tenantId",      ctx.getTenantId(),
            "pendingReview", service.getPendingReview(ctx.getTenantId()).size()
        )));
    }
}
