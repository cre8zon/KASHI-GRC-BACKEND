package com.kashi.grc.guard.repository;

import com.kashi.grc.guard.domain.GuardRule;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Criteria API implementation of GuardRuleRepositoryCustom.
 *
 * ── QUERY LOGIC ──────────────────────────────────────────────────────────────
 * findActiveRulesForTag builds the equivalent of:
 *
 *   SELECT r FROM guard_rules r
 *   WHERE r.question_tag  = :questionTag
 *     AND r.is_active     = true
 *     AND (r.tenant_id IS NULL OR r.tenant_id = :tenantId)
 *   ORDER BY r.tenant_id NULLS FIRST
 *
 * Written entirely via the Criteria API — no JPQL strings, no native SQL.
 * Type-safe: field names reference GuardRule class members, not column strings.
 *
 * ── NULL TAG GUARD ────────────────────────────────────────────────────────────
 * If questionTag is null or blank, returns an empty list immediately.
 * This prevents a full-table scan and ensures untagged questions are silently
 * skipped by the guard system without any error.
 */
public class GuardRuleRepositoryImpl implements GuardRuleRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public List<GuardRule> findActiveRulesForTag(String questionTag, Long tenantId) {

        // Untagged questions are never evaluated — return early, no DB hit
        if (questionTag == null || questionTag.isBlank()) {
            return List.of();
        }

        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<GuardRule> cq = cb.createQuery(GuardRule.class);
        Root<GuardRule> r = cq.from(GuardRule.class);

        // ── Predicates ────────────────────────────────────────────────────────

        // 1. Tag must match
        Predicate tagMatch = cb.equal(r.get("questionTag"), questionTag);

        // 2. Rule must be active
        Predicate isActive = cb.isTrue(r.get("isActive"));

        // 3. Global rules (tenantId IS NULL) + this tenant's rules
        Predicate globalRule  = cb.isNull(r.get("tenantId"));
        Predicate tenantRule  = cb.equal(r.get("tenantId"), tenantId);
        Predicate tenantScope = cb.or(globalRule, tenantRule);

        cq.where(cb.and(tagMatch, isActive, tenantScope));

        // ── Order: global rules first, then tenant-specific ───────────────────
        // cb.nullsFirst on tenantId: NULL (global) comes before non-null (tenant)
        cq.orderBy(cb.asc(
                cb.selectCase()
                        .when(cb.isNull(r.get("tenantId")), 0)
                        .otherwise(1)
        ));

        return em.createQuery(cq).getResultList();
    }
}