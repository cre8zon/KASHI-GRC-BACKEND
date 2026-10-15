package com.kashi.grc.billing.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only counts across modules, by native SQL against their tables.
 *
 * Billing needs to know how many users and vendors a tenant has, but must not
 * depend on the user or vendor modules' services (and must not change them).
 * One grouped query per measure, for all tenants at once — never one per tenant.
 */
@Component
public class TenantUsageReader {

    @PersistenceContext
    private EntityManager em;

    /** Non-deleted users per tenant. Vendor-side users (vendor_id set) excluded: they are not seats. */
    public Map<Long, Long> seatsTotal() {
        return grouped("SELECT tenant_id, COUNT(*) FROM users "
                + "WHERE is_deleted = false AND vendor_id IS NULL GROUP BY tenant_id", null);
    }

    /** Org-side users who have logged in since {@code since}. */
    public Map<Long, Long> seatsActiveSince(LocalDateTime since) {
        return grouped("SELECT tenant_id, COUNT(*) FROM users "
                + "WHERE is_deleted = false AND vendor_id IS NULL AND last_login >= :since GROUP BY tenant_id", since);
    }

    /** Vendors under management — the priced metric. Offboarded vendors still count until deleted. */
    public Map<Long, Long> vendorsTotal() {
        return grouped("SELECT tenant_id, COUNT(*) FROM vendors WHERE is_deleted = false GROUP BY tenant_id", null);
    }

    /** Vendor-side users (portal logins) per tenant — the vendor channel's size. */
    public Map<Long, Long> vendorPortalUsers() {
        return grouped("SELECT tenant_id, COUNT(*) FROM users "
                + "WHERE is_deleted = false AND vendor_id IS NOT NULL GROUP BY tenant_id", null);
    }

    public Map<Long, Long> assessmentsTotal() {
        return grouped("SELECT tenant_id, COUNT(*) FROM vendor_assessments GROUP BY tenant_id", null);
    }

    /** AI inference cost, micro-USD, for one YYYY-MM period. */
    public Map<Long, Long> aiCostMicros(String periodMonth) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT tenant_id, SUM(cost_micros) FROM ai_usage_counters "
                                + "WHERE period_key = :p AND tenant_id IS NOT NULL GROUP BY tenant_id")
                .setParameter("p", periodMonth).getResultList();
        return toMap(rows);
    }

    /** AI tokens used, for one YYYY-MM period. */
    public Map<Long, Long> aiTokens(String periodMonth) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT tenant_id, SUM(total_tokens) FROM ai_usage_counters "
                                + "WHERE period_key = :p AND tenant_id IS NOT NULL GROUP BY tenant_id")
                .setParameter("p", periodMonth).getResultList();
        return toMap(rows);
    }

    private Map<Long, Long> grouped(String sql, LocalDateTime since) {
        var q = em.createNativeQuery(sql);
        if (since != null) q.setParameter("since", since);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();
        return toMap(rows);
    }

    private static Map<Long, Long> toMap(List<Object[]> rows) {
        Map<Long, Long> m = new HashMap<>();
        for (Object[] r : rows) {
            if (r[0] == null) continue;
            m.put(((Number) r[0]).longValue(), r[1] == null ? 0L : ((Number) r[1]).longValue());
        }
        return m;
    }
}
