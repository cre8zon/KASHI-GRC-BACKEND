package com.kashi.grc.guard.repository;

import com.kashi.grc.guard.domain.GuardRule;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * GuardRuleRepository — tag-based rule lookup via Criteria API.
 *
 * ── WHY CRITERIA API ─────────────────────────────────────────────────────────
 * JPA Criteria queries are:
 *   - Compile-time type-safe (typos in field names fail at build, not runtime)
 *   - Refactor-safe (renaming a field is caught by the compiler everywhere)
 *   - Testable without a running DB (can be mocked at the Criteria level)
 *   - Portable across JPA providers without dialect differences
 *
 * No @Query JPQL strings, no native SQL — all queries are programmatic.
 *
 * ── LOOKUP STRATEGY ──────────────────────────────────────────────────────────
 * Rules are matched by questionTag (a category string), not by question ID.
 * One rule covers all questions carrying that tag, across all templates and modules.
 * Both global rules (tenantId IS NULL) and tenant rules (tenantId = X) are returned —
 * they apply together; global rules are the baseline, tenant rules add on top.
 */
@Repository
public interface GuardRuleRepository extends JpaRepository<GuardRule, Long>, GuardRuleRepositoryCustom {

    /** Admin/audit view — find all rules for a blueprint code */
    List<GuardRule> findByBlueprintCodeAndIsActiveTrue(String blueprintCode);

    /** Admin view — find all rules for a specific tag */
    List<GuardRule> findByQuestionTagAndIsActiveTrue(String questionTag);
}