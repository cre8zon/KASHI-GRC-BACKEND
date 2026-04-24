package com.kashi.grc.guard.repository;

import com.kashi.grc.guard.domain.GuardRule;

import java.util.List;

/**
 * Custom repository interface for GuardRule criteria-based queries.
 *
 * Spring Data JPA picks this up automatically when the impl class is named
 * GuardRuleRepositoryImpl and implements this interface.
 */
public interface GuardRuleRepositoryCustom {

    /**
     * Find all active guard rules for a given question tag + tenant.
     *
     * Returns BOTH:
     *   - Global rules (tenantId IS NULL) — the baseline for all tenants
     *   - Tenant-specific rules (tenantId = tenantId) — custom overrides
     *
     * Results are ordered: global rules first (tenantId NULL), tenant rules after.
     * This gives callers a predictable evaluation order.
     *
     * @param questionTag  the tag snapshotted on the question instance
     * @param tenantId     the calling tenant's ID
     * @return list of matching active rules, never null, may be empty
     */
    List<GuardRule> findActiveRulesForTag(String questionTag, Long tenantId);
}