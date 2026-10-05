package com.kashi.grc.trustcenter.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.trustcenter.domain.TrustAccessRequest;
import com.kashi.grc.trustcenter.domain.TrustCenter;
import com.kashi.grc.trustcenter.repository.TrustAccessRequestRepository;
import com.kashi.grc.trustcenter.repository.TrustCenterRepository;
import com.kashi.grc.trustcenter.repository.TrustDocumentDownloadRepository;
import com.kashi.grc.trustcenter.service.TrustCenterService;
import com.kashi.grc.usermanagement.domain.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** The tenant's own side: configure the page, and decide who gets what. */
@Slf4j
@RestController
@RequestMapping("/v1/trust")
@RequiredArgsConstructor
@Tag(name = "Trust center", description = "Configure the public page and handle access requests")
public class TrustCenterController {

    private final TrustCenterService             service;
    private final TrustCenterRepository          centerRepository;
    private final TrustAccessRequestRepository   requestRepository;
    private final TrustDocumentDownloadRepository downloadRepository;
    private final UtilityService                 utilityService;

    @GetMapping
    @Operation(summary = "This tenant's trust center, published or not")
    public ResponseEntity<ApiResponse<TrustCenter>> mine() {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(
                centerRepository.findByTenantIdAndIsDeletedFalse(tenantId).orElse(null)));
    }

    /**
     * The whole page as the tenant edits it — centre, sections and documents.
     *
     * One call rather than three, because the configuration screen needs all of
     * it to render anything and three round trips would show the page arriving
     * in pieces.
     */
    @GetMapping("/admin")
    @Operation(summary = "Everything needed to edit the page")
    public ResponseEntity<ApiResponse<Map<String, Object>>> adminView() {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(service.adminView(tenantId)));
    }

    /** Creates the trust page or updates it. One per tenant, hence upsert. */
    @PutMapping
    @Operation(summary = "Create or update this tenant's trust page")
    public ResponseEntity<ApiResponse<TrustCenter>> upsert(@RequestBody Map<String, Object> body) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                service.upsert(body, ctx.getId(), ctx.getTenantId())));
    }

    @PutMapping("/sections")
    @Operation(summary = "Add or edit a section")
    public ResponseEntity<ApiResponse<Object>> saveSection(@RequestBody Map<String, Object> body) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(service.saveSection(body, tenantId)));
    }

    @DeleteMapping("/sections/{id}")
    @Operation(summary = "Remove a section")
    public ResponseEntity<ApiResponse<Void>> deleteSection(@PathVariable Long id) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        service.deleteSection(id, tenantId);
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PutMapping("/documents")
    @Operation(summary = "Put a document on the page, or change how it is gated")
    public ResponseEntity<ApiResponse<Object>> saveDocument(@RequestBody Map<String, Object> body) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(service.saveDocument(body, tenantId)));
    }

    /** Takes it off the page. The underlying file is untouched. */
    @DeleteMapping("/documents/{id}")
    @Operation(summary = "Remove a document from the page")
    public ResponseEntity<ApiResponse<Void>> deleteDocument(@PathVariable Long id) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        service.deleteDocument(id, tenantId);
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PostMapping("/publish")
    @Operation(summary = "Make it public — refuses on an empty page")
    public ResponseEntity<ApiResponse<TrustCenter>> publish() {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                service.publish(ctx.getId(), ctx.getTenantId())));
    }

    @PostMapping("/unpublish")
    @Operation(summary = "Take it down")
    public ResponseEntity<ApiResponse<TrustCenter>> unpublish() {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(service.unpublish(tenantId)));
    }

    // ── Access requests, which are also the leads ────────────────────────────

    @GetMapping("/requests")
    @Operation(summary = "Who has asked for what")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> requests() {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(
                requestRepository.findByTenantId(tenantId).stream()
                        .map(this::toRow).toList()));
    }

    @PostMapping("/requests/{id}/approve")
    @Operation(summary = "Grant access")
    public ResponseEntity<ApiResponse<Map<String, Object>>> approve(@PathVariable Long id) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                toRow(service.approve(id, ctx.getId(), ctx.getTenantId()))));
    }

    @PostMapping("/requests/{id}/deny")
    @Operation(summary = "Refuse it")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deny(
            @PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        User ctx = utilityService.getLoggedInDataContext();
        Object r = body == null ? null : body.get("remarks");
        return ResponseEntity.ok(ApiResponse.success(toRow(service.deny(
                id, r == null ? null : String.valueOf(r), ctx.getId(), ctx.getTenantId()))));
    }

    @PostMapping("/requests/{id}/revoke")
    @Operation(summary = "Kill a live grant immediately")
    public ResponseEntity<ApiResponse<Map<String, Object>>> revoke(@PathVariable Long id) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(toRow(service.revoke(id, tenantId))));
    }

    @GetMapping("/requests/{id}/linked-downloads")
    @Operation(summary = "Every fetch this grant has made")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> downloads(@PathVariable Long id) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(
                downloadRepository.findByRequestIdOrderByDownloadedAtDesc(id).stream()
                        .filter(d -> tenantId.equals(d.getTenantId()))
                        .map(d -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("id",           d.getId());
                            m.put("title",        "Downloaded " + d.getDownloadedAt());
                            m.put("downloadedAt", d.getDownloadedAt());
                            m.put("sourceIp",     d.getSourceIp());
                            return m;
                        }).toList()));
    }

    @GetMapping("/stats")
    @Operation(summary = "Counts for the dashboard")
    public ResponseEntity<ApiResponse<Map<String, Object>>> stats() {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(service.getStats(tenantId)));
    }

    private Map<String, Object> toRow(TrustAccessRequest r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",              r.getId());
        m.put("title",           r.getRequesterEmail());
        m.put("requesterEmail",  r.getRequesterEmail());
        m.put("requesterName",   r.getRequesterName());
        m.put("company",         r.getCompany());
        m.put("jobTitle",        r.getJobTitle());
        m.put("purpose",         r.getPurpose());
        m.put("ndaAcknowledged", r.isNdaAcknowledged());
        m.put("status",          r.getStatus());
        m.put("createdAt",       r.getCreatedAt());
        m.put("tokenExpiresAt",  r.getTokenExpiresAt());
        // Whether the grant works RIGHT NOW — computed, because a stored flag
        // would keep saying yes after the token expired.
        m.put("liveGrant",       r.isUsable());
        // The token itself is never returned here. Somebody with the requests
        // list could otherwise read every live grant and use it themselves,
        // which defeats the per-recipient point of the token.
        m.put("editable",        r.getStatus() == TrustAccessRequest.Status.PENDING);
        return m;
    }
}
