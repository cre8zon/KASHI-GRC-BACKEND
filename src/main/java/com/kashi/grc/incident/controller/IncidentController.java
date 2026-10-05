package com.kashi.grc.incident.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.dto.PaginatedResponse;
import com.kashi.grc.common.repository.DbRepository;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.incident.domain.Incident;
import com.kashi.grc.incident.domain.IncidentNotification;
import com.kashi.grc.incident.dto.*;
import com.kashi.grc.incident.repository.IncidentNotificationRepository;
import com.kashi.grc.incident.service.IncidentService;
import com.kashi.grc.usermanagement.domain.User;
import com.kashi.grc.usermanagement.repository.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Incident management API.
 *
 * ── THE ENDPOINT LIST IS NOT FREE-FORM ────────────────────────────────────
 * Every path is named by a row in 11_incident_management_seed.sql:
 *
 *   ui_forms.submit_url
 *     POST /v1/incidents                      incident_create_form
 *     PUT  /v1/incidents/{id}                 incident_detail_header, _tab_overview
 *     PUT  /v1/incidents/{id}/response        incident_detail_tab_response
 *     PUT  /v1/incidents/{id}/regulatory      incident_detail_tab_regulatory
 *     POST /v1/incidents/{id}/notifications   incident_notification_form
 *
 *   ui_actions.api_endpoint
 *     POST /v1/incidents/{id}/triage          INC_TRIAGE
 *     POST /v1/incidents/{id}/investigate     INC_INVESTIGATE
 *     POST /v1/incidents/{id}/contain         INC_CONTAIN
 *     POST /v1/incidents/{id}/eradicate       INC_ERADICATE
 *     POST /v1/incidents/{id}/recover         INC_RECOVER
 *     POST /v1/incidents/{id}/notifications   INC_RECORD_NOTIFICATION (__formKey)
 *     POST /v1/incidents/{id}/close           INC_CLOSE
 *     POST /v1/incidents/{id}/reopen          INC_REOPEN
 *     POST /v1/incidents/{id}/false-positive  INC_FALSE_POSITIVE
 *     POST /v1/incidents/{id}/assets          INC_LINK_ASSET
 *     POST /v1/incidents/{id}/risks           INC_LINK_RISK
 *
 *   ui_layouts.tabs_json — served by the generic LinkedEntitiesTab, which
 *   derives {apiBasePath}/{id}/<tabKey> from the key itself:
 *     GET  /v1/incidents/{id}/linked-notifications
 *     GET  /v1/incidents/{id}/linked-assets
 *     GET  /v1/incidents/{id}/linked-risks
 */
@Slf4j
@RestController
@RequestMapping("/v1/incidents")
@Tag(name = "Incident Management",
     description = "ISO 27001 A.5.24-A.5.28, NIST SP 800-61, with the CERT-In and DPDPA reporting clocks")
@RequiredArgsConstructor
public class IncidentController {

    private final IncidentService                incidentService;
    private final IncidentNotificationRepository notificationRepository;
    private final UtilityService                 utilityService;
    private final UserRepository                 userRepository;
    private final DbRepository                   dbRepository;

    // ── Create ────────────────────────────────────────────────────────────────

    @PostMapping
    @Operation(summary = "Report an incident")
    public ResponseEntity<ApiResponse<IncidentResponse>> create(
            @Valid @RequestBody IncidentRequest req) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                incidentService.create(req, ctx.getId(), ctx.getTenantId())));
    }

    // ── List ──────────────────────────────────────────────────────────────────

    /**
     * Mapped inline so DbRepository's pagination stays on the query.
     *
     * reportingDueAt is the earliest outstanding regulatory deadline and is the
     * column the module exists for. It comes from ONE batch query over
     * incident_notifications for the whole page, not a lookup per row.
     */
    @GetMapping
    @Operation(summary = "List incidents — filter by status, severity, incidentType, ownerId, overdue")
    public ResponseEntity<ApiResponse<PaginatedResponse<Map<String, Object>>>> list(
            @RequestParam Map<String, String> allParams) {

        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        Map<Long, String> nameCache = new HashMap<>();

        PaginatedResponse<Map<String, Object>> page = dbRepository.findAll(
                Incident.class,
                utilityService.getpageDetails(allParams),
                (cb, root) -> {
                    List<jakarta.persistence.criteria.Predicate> preds = new ArrayList<>();
                    preds.add(cb.equal(root.get("tenantId"), tenantId));
                    preds.add(cb.isFalse(root.get("isDeleted")));

                    if (allParams.containsKey("status")) {
                        preds.add(cb.equal(root.get("status"),
                                Incident.Status.valueOf(allParams.get("status").toUpperCase())));
                    }
                    if (allParams.containsKey("severity")) {
                        preds.add(cb.equal(root.get("severity"),
                                Incident.Severity.valueOf(allParams.get("severity").toUpperCase())));
                    }
                    if (allParams.containsKey("incidentType")) {
                        preds.add(cb.equal(root.get("incidentType"),
                                allParams.get("incidentType").toUpperCase()));
                    }
                    if (allParams.containsKey("detectionSource")) {
                        preds.add(cb.equal(root.get("detectionSource"),
                                allParams.get("detectionSource").toUpperCase()));
                    }
                    if (allParams.containsKey("ownerId")) {
                        preds.add(cb.equal(root.get("ownerId"), Long.parseLong(allParams.get("ownerId"))));
                    }
                    if ("true".equalsIgnoreCase(allParams.get("personalDataInvolved"))) {
                        preds.add(cb.isTrue(root.get("personalDataInvolved")));
                    }
                    // "Still open" — excludes both terminal states, which is
                    // what people mean and what CLOSED alone would miss.
                    if ("true".equalsIgnoreCase(allParams.get("openOnly"))) {
                        preds.add(cb.not(root.get("status").in(
                                Incident.Status.CLOSED, Incident.Status.FALSE_POSITIVE)));
                    }
                    return preds;
                },
                (cb, root) -> {
                    Map<String, jakarta.persistence.criteria.Path<?>> f = new HashMap<>();
                    f.put("title",          root.get("title"));
                    f.put("incident_ref",   root.get("incidentRef"));
                    f.put("incidentref",    root.get("incidentRef"));
                    f.put("incidenttype",   root.get("incidentType"));
                    f.put("severity",       root.get("severity"));
                    f.put("status",         root.get("status"));
                    f.put("detectedat",     root.get("detectedAt"));
                    f.put("occurredat",     root.get("occurredAt"));
                    f.put("created_at",     root.get("createdAt"));
                    return f;
                },
                i -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",            i.getId());
                    m.put("incidentRef",   i.getIncidentRef());
                    m.put("title",         i.getTitle());
                    m.put("incidentType",  i.getIncidentType() != null ? i.getIncidentType() : "");
                    m.put("severity",      i.getSeverity());
                    m.put("status",        i.getStatus());
                    m.put("detectedAt",    i.getDetectedAt());
                    m.put("occurredAt",    i.getOccurredAt());
                    m.put("ownerId",       i.getOwnerId());
                    m.put("ownerName",     resolveName(i.getOwnerId(), nameCache));
                    m.put("slaBreached",   i.isSlaBreached());
                    m.put("personalDataInvolved", i.isPersonalDataInvolved());
                    m.put("editable",      i.getStatus() != Incident.Status.CLOSED);
                    m.put("createdAt",     i.getCreatedAt());
                    m.put("updatedAt",     i.getUpdatedAt());
                    return m;
                });

        attachReportingDeadlines(page, tenantId);
        return ResponseEntity.ok(ApiResponse.success(page));
    }

    /**
     * Fills reportingDueAt and reportingOverdue across the page in one query.
     *
     * Done after the fact rather than inside the row mapper because the mapper
     * runs per row, and a notification lookup there would be one query per
     * incident — the pattern the audit module's task inbox had to be rescued
     * from.
     */
    private void attachReportingDeadlines(PaginatedResponse<Map<String, Object>> page, Long tenantId) {
        List<Map<String, Object>> items = page.getItems();
        if (items == null || items.isEmpty()) return;

        List<Long> ids = items.stream()
                .map(m -> (Long) m.get("id"))
                .filter(Objects::nonNull)
                .toList();
        if (ids.isEmpty()) return;

        Map<Long, LocalDateTime> earliest = new HashMap<>();
        for (IncidentNotification n : notificationRepository.findByIncidentIdInAndTenantId(ids, tenantId)) {
            if (n.getNotifiedAt() != null || n.getDueAt() == null) continue;
            earliest.merge(n.getIncidentId(), n.getDueAt(),
                    (a, b) -> a.isBefore(b) ? a : b);
        }

        LocalDateTime now = LocalDateTime.now();
        for (Map<String, Object> m : items) {
            LocalDateTime due = earliest.get((Long) m.get("id"));
            m.put("reportingDueAt",   due);
            m.put("reportingOverdue", due != null && due.isBefore(now));
        }
    }

    private String resolveName(Long userId, Map<Long, String> cache) {
        if (userId == null) return "";
        return cache.computeIfAbsent(userId, id -> userRepository.findById(id)
                .map(u -> {
                    String full = u.getFullName();
                    return (full != null && !full.isBlank()) ? full : u.getEmail();
                })
                .orElse(""));
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    @GetMapping("/{id}")
    @Operation(summary = "Get one incident with its regulatory clocks")
    public ResponseEntity<ApiResponse<IncidentResponse>> getById(@PathVariable Long id) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(incidentService.getById(id, tenantId)));
    }

    // ── Update ────────────────────────────────────────────────────────────────

    @PutMapping("/{id}")
    @Operation(summary = "Update an incident — title, type, severity, ownership, provenance")
    public ResponseEntity<ApiResponse<IncidentResponse>> update(
            @PathVariable Long id, @Valid @RequestBody IncidentRequest req) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                incidentService.update(id, req, ctx.getId(), ctx.getTenantId())));
    }

    @PutMapping("/{id}/response")
    @Operation(summary = "Save the response tab — timeline and actions taken")
    public ResponseEntity<ApiResponse<IncidentResponse>> saveResponse(
            @PathVariable Long id, @Valid @RequestBody IncidentResponseActionsRequest req) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                incidentService.saveResponse(id, req, ctx.getId(), ctx.getTenantId())));
    }

    @PutMapping("/{id}/regulatory")
    @Operation(summary = "Save the regulatory tab — changing the applicable regimes re-syncs the clocks")
    public ResponseEntity<ApiResponse<IncidentResponse>> saveRegulatory(
            @PathVariable Long id, @Valid @RequestBody IncidentRegulatoryRequest req) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                incidentService.saveRegulatory(id, req, ctx.getId(), ctx.getTenantId())));
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @PostMapping("/{id}/triage")
    @Operation(summary = "NEW to TRIAGED")
    public ResponseEntity<ApiResponse<IncidentResponse>> triage(@PathVariable Long id) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                incidentService.triage(id, ctx.getId(), ctx.getTenantId())));
    }

    @PostMapping("/{id}/investigate")
    @Operation(summary = "TRIAGED to INVESTIGATING")
    public ResponseEntity<ApiResponse<IncidentResponse>> investigate(@PathVariable Long id) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                incidentService.investigate(id, ctx.getId(), ctx.getTenantId())));
    }

    @PostMapping("/{id}/contain")
    @Operation(summary = "INVESTIGATING to CONTAINED")
    public ResponseEntity<ApiResponse<IncidentResponse>> contain(
            @PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                incidentService.contain(id, remarksOf(body), ctx.getId(), ctx.getTenantId())));
    }

    @PostMapping("/{id}/eradicate")
    @Operation(summary = "CONTAINED to ERADICATED")
    public ResponseEntity<ApiResponse<IncidentResponse>> eradicate(
            @PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                incidentService.eradicate(id, remarksOf(body), ctx.getId(), ctx.getTenantId())));
    }

    @PostMapping("/{id}/recover")
    @Operation(summary = "ERADICATED to RECOVERED")
    public ResponseEntity<ApiResponse<IncidentResponse>> recover(
            @PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                incidentService.recover(id, remarksOf(body), ctx.getId(), ctx.getTenantId())));
    }

    @PostMapping("/{id}/close")
    @Operation(summary = "RECOVERED to CLOSED — requires a root cause, lessons learned and no outstanding reports")
    public ResponseEntity<ApiResponse<IncidentResponse>> close(
            @PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                incidentService.close(id, remarksOf(body), ctx.getId(), ctx.getTenantId())));
    }

    @PostMapping("/{id}/reopen")
    @Operation(summary = "Reopen a closed incident or one dismissed as a false positive")
    public ResponseEntity<ApiResponse<IncidentResponse>> reopen(
            @PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                incidentService.reopen(id, remarksOf(body), ctx.getId(), ctx.getTenantId())));
    }

    @PostMapping("/{id}/false-positive")
    @Operation(summary = "Dismiss as a false positive — drops unreported regulatory clocks")
    public ResponseEntity<ApiResponse<IncidentResponse>> falsePositive(
            @PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                incidentService.markFalsePositive(id, remarksOf(body), ctx.getId(), ctx.getTenantId())));
    }

    /** UniversalModulePage posts requires_remarks text as "remarks". */
    private String remarksOf(Map<String, Object> body) {
        if (body == null) return null;
        Object v = body.get("remarks");
        return v != null ? String.valueOf(v) : null;
    }

    // ── Regulatory notifications ──────────────────────────────────────────────

    @GetMapping("/{id}/linked-notifications")
    @Operation(summary = "Regulatory reporting obligations and their clocks")
    public ResponseEntity<ApiResponse<List<IncidentResponse.Notification>>> listNotifications(
            @PathVariable Long id) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(
                incidentService.listNotifications(id, tenantId)));
    }

    @PostMapping("/{id}/notifications")
    @Operation(summary = "Record that a regime has been notified — upsert by framework")
    public ResponseEntity<ApiResponse<IncidentResponse.Notification>> recordNotification(
            @PathVariable Long id, @Valid @RequestBody IncidentNotificationRequest req) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                incidentService.recordNotification(id, req, ctx.getId(), ctx.getTenantId())));
    }

    // ── Linked assets and risks ───────────────────────────────────────────────

    @GetMapping("/{id}/linked-assets")
    @Operation(summary = "Assets involved in this incident")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> linkedAssets(@PathVariable Long id) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(incidentService.listLinkedAssets(id, tenantId)));
    }

    @PostMapping("/{id}/assets")
    @Operation(summary = "Link an affected asset")
    public ResponseEntity<ApiResponse<Void>> linkAsset(
            @PathVariable Long id, @Valid @RequestBody IncidentLinkRequest req) {
        User ctx = utilityService.getLoggedInDataContext();
        incidentService.linkAsset(id, req, ctx.getId(), ctx.getTenantId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success());
    }

    @DeleteMapping("/{id}/assets/{assetId}")
    @Operation(summary = "Unlink an asset")
    public ResponseEntity<ApiResponse<Void>> unlinkAsset(
            @PathVariable Long id, @PathVariable Long assetId) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        incidentService.unlinkAsset(id, assetId, tenantId);
        return ResponseEntity.ok(ApiResponse.success());
    }

    @GetMapping("/{id}/linked-risks")
    @Operation(summary = "Risks that materialised in this incident")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> linkedRisks(@PathVariable Long id) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(incidentService.listLinkedRisks(id, tenantId)));
    }

    @PostMapping("/{id}/risks")
    @Operation(summary = "Link a risk that materialised")
    public ResponseEntity<ApiResponse<Void>> linkRisk(
            @PathVariable Long id, @Valid @RequestBody IncidentLinkRequest req) {
        User ctx = utilityService.getLoggedInDataContext();
        incidentService.linkRisk(id, req, ctx.getId(), ctx.getTenantId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success());
    }

    @DeleteMapping("/{id}/risks/{riskId}")
    @Operation(summary = "Unlink a risk")
    public ResponseEntity<ApiResponse<Void>> unlinkRisk(
            @PathVariable Long id, @PathVariable Long riskId) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        incidentService.unlinkRisk(id, riskId, tenantId);
        return ResponseEntity.ok(ApiResponse.success());
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    @GetMapping("/stats")
    @Operation(summary = "Counts by status, severity and type, SLA breaches, overdue reports, MTTD and MTTC")
    public ResponseEntity<ApiResponse<Map<String, Object>>> stats() {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(incidentService.getStats(tenantId)));
    }
}
