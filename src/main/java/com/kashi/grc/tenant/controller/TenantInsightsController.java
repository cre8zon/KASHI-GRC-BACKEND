package com.kashi.grc.tenant.controller;

import com.kashi.grc.billing.domain.TenantSubscription;
import com.kashi.grc.billing.service.SubscriptionService;
import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.tenant.domain.Tenant;
import com.kashi.grc.tenant.repository.TenantRepository;
import com.kashi.grc.usermanagement.domain.User;
import com.kashi.grc.usermanagement.repository.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;

/**
 * Read-only views behind the Tenant Detail tabs (Usage, Users).
 *
 * Under /v1/tenants/** — SIDE_SYSTEM only, enforced by SecurityConfig. Counts
 * come from native grouped SQL against the owning modules' tables so this
 * controller depends on none of their services and changes none of them.
 */
@RestController
@RequestMapping("/v1/tenants")
@Tag(name = "Tenant Management — insights")
@RequiredArgsConstructor
public class TenantInsightsController {

    private final TenantRepository    tenantRepository;
    private final UserRepository      userRepository;
    private final SubscriptionService subscriptionService;

    @PersistenceContext
    private EntityManager em;

    @GetMapping("/{tenantId}/usage")
    @Transactional(readOnly = true)
    @Operation(summary = "Seats, vendors, assessments, AI usage and limits for one tenant")
    public ApiResponse<Map<String, Object>> usage(@PathVariable Long tenantId) {
        Tenant t = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant", tenantId));
        LocalDateTime d30 = LocalDateTime.now().minusDays(30);
        String period = YearMonth.now().toString();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tenantId", tenantId);

        Map<String, Object> seats = new LinkedHashMap<>();
        seats.put("total", count("SELECT COUNT(*) FROM users WHERE tenant_id = :t AND is_deleted = false AND vendor_id IS NULL", tenantId));
        seats.put("active30d", count("SELECT COUNT(*) FROM users WHERE tenant_id = :t AND is_deleted = false AND vendor_id IS NULL AND last_login >= :d", tenantId, d30));
        seats.put("neverLoggedIn", count("SELECT COUNT(*) FROM users WHERE tenant_id = :t AND is_deleted = false AND vendor_id IS NULL AND last_login IS NULL", tenantId));
        seats.put("limit", t.getMaxUsers());
        seats.put("lastLoginAt", scalar("SELECT MAX(last_login) FROM users WHERE tenant_id = :t AND is_deleted = false", tenantId));
        m.put("seats", seats);

        Map<String, Object> vendors = new LinkedHashMap<>();
        vendors.put("total", count("SELECT COUNT(*) FROM vendors WHERE tenant_id = :t AND is_deleted = false", tenantId));
        vendors.put("limit", t.getMaxVendors());
        vendors.put("byStatus", grouped("SELECT status, COUNT(*) FROM vendors WHERE tenant_id = :t AND is_deleted = false GROUP BY status", tenantId));
        vendors.put("portalUsers", count("SELECT COUNT(*) FROM users WHERE tenant_id = :t AND is_deleted = false AND vendor_id IS NOT NULL", tenantId));
        m.put("vendors", vendors);

        Map<String, Object> assessments = new LinkedHashMap<>();
        assessments.put("total", count("SELECT COUNT(*) FROM vendor_assessments WHERE tenant_id = :t", tenantId));
        assessments.put("byStatus", grouped("SELECT status, COUNT(*) FROM vendor_assessments WHERE tenant_id = :t GROUP BY status", tenantId));
        assessments.put("submitted", count("SELECT COUNT(*) FROM vendor_assessments WHERE tenant_id = :t AND submitted_at IS NOT NULL", tenantId));
        m.put("assessments", assessments);

        Map<String, Object> ai = new LinkedHashMap<>();
        ai.put("period", period);
        ai.put("tokens", count("SELECT COALESCE(SUM(total_tokens),0) FROM ai_usage_counters WHERE tenant_id = :t AND period_key = :p", tenantId, period));
        ai.put("requests", count("SELECT COALESCE(SUM(request_count),0) FROM ai_usage_counters WHERE tenant_id = :t AND period_key = :p", tenantId, period));
        ai.put("costMicros", count("SELECT COALESCE(SUM(cost_micros),0) FROM ai_usage_counters WHERE tenant_id = :t AND period_key = :p", tenantId, period));
        m.put("ai", ai);

        Optional<TenantSubscription> cur = subscriptionService.current(tenantId);
        m.put("subscription", cur.map(s -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", s.getId());
            x.put("planCode", s.getPlanCode());
            x.put("status", s.getStatus());
            x.put("termEnd", s.getTermEnd());
            x.put("seatsPurchased", s.getSeatsPurchased());
            x.put("vendorsPurchased", s.getVendorsPurchased());
            x.put("mrrMinor", subscriptionService.mrrMinor(s));
            x.put("currency", s.getCurrency());
            return x;
        }).orElse(null));
        return ApiResponse.success(m);
    }

    @GetMapping("/{tenantId}/users")
    @Transactional(readOnly = true)
    @Operation(summary = "Users of one tenant with roles and last login (org and vendor side)")
    public ApiResponse<List<Map<String, Object>>> users(@PathVariable Long tenantId) {
        tenantRepository.findById(tenantId).orElseThrow(() -> new ResourceNotFoundException("Tenant", tenantId));
        List<Map<String, Object>> out = new ArrayList<>();
        for (User u : userRepository.findByTenantIdAndIsDeletedFalse(tenantId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", u.getId());
            m.put("fullName", u.getFullName());
            m.put("email", u.getEmail());
            m.put("status", u.getStatus() == null ? null : u.getStatus().name());
            m.put("lastLogin", u.getLastLogin());
            m.put("vendorId", u.getVendorId());
            m.put("createdAt", u.getCreatedAt());
            m.put("roles", u.getRoles().stream().map(r -> {
                Map<String, Object> rm = new LinkedHashMap<>();
                rm.put("name", r.getName());
                rm.put("side", r.getSide() == null ? null : r.getSide().name());
                rm.put("level", r.getLevel() == null ? null : r.getLevel().name());
                return rm;
            }).toList());
            out.add(m);
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> m.get("vendorId") != null)
                .thenComparing(m -> String.valueOf(m.get("email"))));
        return ApiResponse.success(out);
    }

    // ── internals ───────────────────────────────────────────────────────────

    private long count(String sql, Long tenantId, Object... extra) {
        var q = em.createNativeQuery(sql).setParameter("t", tenantId);
        if (extra.length > 0) q.setParameter(sql.contains(":d") ? "d" : "p", extra[0]);
        Object r = q.getSingleResult();
        return r == null ? 0L : ((Number) r).longValue();
    }

    private Object scalar(String sql, Long tenantId) {
        Object r = em.createNativeQuery(sql).setParameter("t", tenantId).getSingleResult();
        return r instanceof java.sql.Timestamp ts ? ts.toLocalDateTime() : r;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Long> grouped(String sql, Long tenantId) {
        Map<String, Long> m = new TreeMap<>();
        for (Object[] r : (List<Object[]>) em.createNativeQuery(sql).setParameter("t", tenantId).getResultList()) {
            m.put(r[0] == null ? "UNKNOWN" : r[0].toString(), r[1] == null ? 0L : ((Number) r[1]).longValue());
        }
        return m;
    }
}
