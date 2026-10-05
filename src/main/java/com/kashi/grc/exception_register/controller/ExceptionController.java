package com.kashi.grc.exception_register.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.repository.DbRepository;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.exception_register.domain.ExceptionLink;
import com.kashi.grc.exception_register.domain.ExceptionRecord;
import com.kashi.grc.exception_register.repository.ExceptionLinkRepository;
import com.kashi.grc.exception_register.repository.ExceptionRenewalRepository;
import com.kashi.grc.exception_register.service.ExceptionService;
import com.kashi.grc.usermanagement.domain.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Slf4j
@RestController
@RequestMapping("/v1/exceptions")
@RequiredArgsConstructor
@Tag(name = "Exceptions", description = "Time-boxed, approved deviations from policy or control")
public class ExceptionController {

    private final ExceptionService            exceptionService;
    private final ExceptionLinkRepository     linkRepository;
    private final ExceptionRenewalRepository  renewalRepository;
    private final UtilityService              utilityService;
    private final DbRepository                dbRepository;

    // ── List and read ─────────────────────────────────────────────────────────

    @GetMapping
    @Operation(summary = "The exception register")
    public ResponseEntity<ApiResponse<Object>> list(@RequestParam Map<String, String> allParams) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();

        return ResponseEntity.ok(ApiResponse.success(dbRepository.findAll(
                ExceptionRecord.class,
                utilityService.getpageDetails(allParams),
                (cb, root) -> {
                    List<jakarta.persistence.criteria.Predicate> preds = new ArrayList<>();
                    preds.add(cb.equal(root.get("tenantId"), tenantId));
                    preds.add(cb.isFalse(root.get("isDeleted")));
                    String status = allParams.get("status");
                    if (status != null && !status.isBlank()) {
                        preds.add(cb.equal(root.get("status"),
                                ExceptionRecord.Status.valueOf(status)));
                    }
                    String type = allParams.get("exceptionType");
                    if (type != null && !type.isBlank()) {
                        preds.add(cb.equal(root.get("exceptionType"), type));
                    }
                    return preds;
                },
                /**
                 * Sortable and searchable fields.
                 *
                 * DbRepository.findAll takes FIVE arguments, not four — this map
                 * sits between the predicate and the row mapper, and omitting it
                 * makes the compiler bind the mapper lambda to this slot, which
                 * is why the errors all read "cannot find symbol getTitle() on
                 * CriteriaBuilder".
                 *
                 * Both snake_case and camelCase keys, matching the training
                 * controller: the frontend sends whichever the column key uses.
                 */
                (cb, root) -> {
                    Map<String, jakarta.persistence.criteria.Path<?>> f = new HashMap<>();
                    f.put("title",          root.get("title"));
                    f.put("exception_ref",  root.get("exceptionRef"));
                    f.put("exceptionref",   root.get("exceptionRef"));
                    f.put("exception_type", root.get("exceptionType"));
                    f.put("exceptiontype",  root.get("exceptionType"));
                    f.put("risk_level",     root.get("riskLevel"));
                    f.put("risklevel",      root.get("riskLevel"));
                    f.put("status",         root.get("status"));
                    f.put("expires_at",     root.get("expiresAt"));
                    f.put("expiresat",      root.get("expiresAt"));
                    f.put("review_due_at",  root.get("reviewDueAt"));
                    f.put("renewal_count",  root.get("renewalCount"));
                    f.put("created_at",     root.get("createdAt"));
                    return f;
                },
                e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",            e.getId());
                    m.put("exceptionRef",  e.getExceptionRef());
                    m.put("title",         e.getTitle());
                    m.put("exceptionType", e.getExceptionType());
                    m.put("riskLevel",     e.getRiskLevel());
                    m.put("status",        e.getStatus());
                    m.put("expiresAt",     e.getExpiresAt());
                    m.put("reviewDueAt",   e.getReviewDueAt());
                    // Surfaced in the list on purpose: an exception on its fifth
                    // extension is not an exception, and a column makes that
                    // impossible to miss.
                    m.put("renewalCount",  e.getRenewalCount());
                    m.put("activeCover",   e.isActiveCover());
                    m.put("dueForReview",  e.isDueForReview());
                    m.put("editable",      e.getStatus() == ExceptionRecord.Status.DRAFT);
                    m.put("createdAt",     e.getCreatedAt());
                    return m;
                })));
    }

    @GetMapping("/{id}/linked-items")
    @Operation(summary = "What this exception covers")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> links(@PathVariable Long id) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        List<Map<String, Object>> rows = linkRepository.findByExceptionId(id).stream()
                .filter(l -> tenantId.equals(l.getTenantId()))
                .map(l -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",         l.getId());
                    m.put("entityType", l.getEntityType());
                    m.put("entityId",   l.getEntityId());
                    m.put("title",      l.getEntityLabel());
                    return m;
                }).toList();
        return ResponseEntity.ok(ApiResponse.success(rows));
    }

    @GetMapping("/{id}/linked-renewals")
    @Operation(summary = "Every extension, with its reason")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> renewals(@PathVariable Long id) {
        List<Map<String, Object>> rows = renewalRepository
                .findByExceptionIdOrderByRenewedAtDesc(id).stream()
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",             r.getId());
                    m.put("title",          r.getPreviousExpiry() + " → " + r.getNewExpiry());
                    m.put("reason",         r.getReason());
                    m.put("renewedAt",      r.getRenewedAt());
                    return m;
                }).toList();
        return ResponseEntity.ok(ApiResponse.success(rows));
    }

    @GetMapping("/stats")
    @Operation(summary = "Register counts for the dashboard")
    public ResponseEntity<ApiResponse<Map<String, Object>>> stats() {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(exceptionService.getStats(tenantId)));
    }

    /** Whether something is currently covered — for other modules to call. */
    @GetMapping("/cover")
    @Operation(summary = "Is this entity covered by a live exception?")
    public ResponseEntity<ApiResponse<Map<String, Object>>> cover(
            @RequestParam String entityType, @RequestParam Long entityId) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        Map<String, Object> out = new LinkedHashMap<>();
        exceptionService.activeCoverFor(entityType, entityId, tenantId).ifPresentOrElse(e -> {
            out.put("covered",      true);
            out.put("exceptionRef", e.getExceptionRef());
            out.put("expiresAt",    e.getExpiresAt());
            out.put("id",           e.getId());
        }, () -> out.put("covered", false));
        return ResponseEntity.ok(ApiResponse.success(out));
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    @PostMapping
    @Operation(summary = "Raise an exception")
    public ResponseEntity<ApiResponse<ExceptionRecord>> create(@RequestBody Map<String, Object> body) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                exceptionService.create(body, ctx.getId(), ctx.getTenantId())));
    }

    @PostMapping("/{id}/submit")
    @Operation(summary = "Send for approval")
    public ResponseEntity<ApiResponse<ExceptionRecord>> submit(@PathVariable Long id) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                exceptionService.submit(id, ctx.getId(), ctx.getTenantId())));
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "Approve — refuses if you raised it")
    public ResponseEntity<ApiResponse<ExceptionRecord>> approve(
            @PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        User ctx = utilityService.getLoggedInDataContext();
        Object r = body == null ? null : body.get("remarks");
        return ResponseEntity.ok(ApiResponse.success(exceptionService.approve(
                id, r == null ? null : String.valueOf(r), ctx.getId(), ctx.getTenantId())));
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "Reject — a reason is required")
    public ResponseEntity<ApiResponse<ExceptionRecord>> reject(
            @PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        User ctx = utilityService.getLoggedInDataContext();
        Object r = body == null ? null : body.get("remarks");
        return ResponseEntity.ok(ApiResponse.success(exceptionService.reject(
                id, r == null ? null : String.valueOf(r), ctx.getId(), ctx.getTenantId())));
    }

    @PostMapping("/{id}/renew")
    @Operation(summary = "Extend the expiry — recorded, counted and reasoned")
    public ResponseEntity<ApiResponse<ExceptionRecord>> renew(
            @PathVariable Long id, @RequestBody Map<String, Object> body) {
        User ctx = utilityService.getLoggedInDataContext();
        Object r = body.get("remarks");
        return ResponseEntity.ok(ApiResponse.success(exceptionService.renew(
                id, body.get("expiresAt"), r == null ? null : String.valueOf(r),
                ctx.getId(), ctx.getTenantId())));
    }

    @PostMapping("/{id}/revoke")
    @Operation(summary = "End it early — a reason is required")
    public ResponseEntity<ApiResponse<ExceptionRecord>> revoke(
            @PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        User ctx = utilityService.getLoggedInDataContext();
        Object r = body == null ? null : body.get("remarks");
        return ResponseEntity.ok(ApiResponse.success(exceptionService.revoke(
                id, r == null ? null : String.valueOf(r), ctx.getId(), ctx.getTenantId())));
    }

    @PostMapping("/{id}/links")
    @Operation(summary = "Attach what this exception covers")
    public ResponseEntity<ApiResponse<ExceptionLink>> link(
            @PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(exceptionService.link(
                id,
                String.valueOf(body.get("entityType")),
                Long.parseLong(String.valueOf(body.get("entityId"))),
                body.get("entityLabel") == null ? null : String.valueOf(body.get("entityLabel")),
                tenantId)));
    }
}