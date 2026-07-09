package com.kashi.grc.guard.repository;

import com.kashi.grc.guard.domain.SodRule;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * JPA Criteria API implementation of SodRuleRepositoryCustom.
 *
 * Notes on the conversion from JPQL:
 *  - ruleType is an @Enumerated(STRING) enum; the JPQL string literal
 *    'ROLE_PAIR' becomes the type-safe SodRule.RuleType.ROLE_PAIR constant.
 *  - The dynamic "(:severity IS NULL OR s.severity = :severity)" clause is now
 *    a conditionally added predicate — cleaner SQL than the OR-param trick.
 *  - Empty currentRoleIds returns [] immediately: .in(empty) is invalid SQL.
 */
public class SodRuleRepositoryImpl implements SodRuleRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    /** (tenantId IS NULL OR tenantId = :tenantId) — global + tenant rules. */
    private Predicate tenantOverlay(CriteriaBuilder cb, Root<SodRule> r, Long tenantId) {
        return cb.or(cb.isNull(r.get("tenantId")), cb.equal(r.get("tenantId"), tenantId));
    }

    @Override
    public long countActiveForTenant(Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<SodRule> s = cq.from(SodRule.class);
        cq.select(cb.count(s)).where(
                cb.isTrue(s.get("isActive")),
                tenantOverlay(cb, s, tenantId)
        );
        Long result = em.createQuery(cq).getSingleResult();
        return result != null ? result : 0L;
    }

    @Override
    public List<SodRule> findConflictBetween(Long tenantId, Long role1, Long role2) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<SodRule> cq = cb.createQuery(SodRule.class);
        Root<SodRule> s = cq.from(SodRule.class);

        Predicate forward  = cb.and(
                cb.equal(s.get("conflictingRole1Id"), role1),
                cb.equal(s.get("conflictingRole2Id"), role2));
        Predicate backward = cb.and(
                cb.equal(s.get("conflictingRole1Id"), role2),
                cb.equal(s.get("conflictingRole2Id"), role1));

        cq.where(
                cb.equal(s.get("ruleType"), SodRule.RuleType.ROLE_PAIR),
                cb.isTrue(s.get("isActive")),
                tenantOverlay(cb, s, tenantId),
                cb.or(forward, backward)
        );
        return em.createQuery(cq).getResultList();
    }

    @Override
    public List<SodRule> findViolationsForProposedRole(Long tenantId, Long proposedRoleId,
                                                       Set<Long> currentRoleIds) {
        if (currentRoleIds == null || currentRoleIds.isEmpty()) return List.of();

        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<SodRule> cq = cb.createQuery(SodRule.class);
        Root<SodRule> s = cq.from(SodRule.class);

        Predicate proposedIsRole1 = cb.and(
                cb.equal(s.get("conflictingRole1Id"), proposedRoleId),
                s.get("conflictingRole2Id").in(currentRoleIds));
        Predicate proposedIsRole2 = cb.and(
                cb.equal(s.get("conflictingRole2Id"), proposedRoleId),
                s.get("conflictingRole1Id").in(currentRoleIds));

        cq.where(
                cb.equal(s.get("ruleType"), SodRule.RuleType.ROLE_PAIR),
                cb.isTrue(s.get("isActive")),
                tenantOverlay(cb, s, tenantId),
                cb.or(proposedIsRole1, proposedIsRole2)
        );
        return em.createQuery(cq).getResultList();
    }

    @Override
    public List<SodRule> findByTenantId(Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<SodRule> cq = cb.createQuery(SodRule.class);
        Root<SodRule> s = cq.from(SodRule.class);
        cq.where(
                cb.equal(s.get("ruleType"), SodRule.RuleType.ROLE_PAIR),
                tenantOverlay(cb, s, tenantId)
        );
        cq.orderBy(cb.asc(s.get("ruleName")));
        return em.createQuery(cq).getResultList();
    }

    @Override
    public List<SodRule> findByTenantIdAndSeverity(Long tenantId, String severity) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<SodRule> cq = cb.createQuery(SodRule.class);
        Root<SodRule> s = cq.from(SodRule.class);

        List<Predicate> predicates = new ArrayList<>();
        predicates.add(cb.equal(s.get("ruleType"), SodRule.RuleType.ROLE_PAIR));
        predicates.add(tenantOverlay(cb, s, tenantId));
        if (severity != null) {
            predicates.add(cb.equal(s.get("severity"), severity));
        }
        cq.where(predicates.toArray(new Predicate[0]));
        return em.createQuery(cq).getResultList();
    }

    @Override
    public List<SodRule> findActiveByTenantId(Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<SodRule> cq = cb.createQuery(SodRule.class);
        Root<SodRule> r = cq.from(SodRule.class);
        cq.where(
                cb.equal(r.get("ruleType"), SodRule.RuleType.PERMISSION_PAIR),
                cb.isTrue(r.get("isActive")),
                tenantOverlay(cb, r, tenantId)
        );
        cq.orderBy(cb.asc(r.get("ruleName")));
        return em.createQuery(cq).getResultList();
    }

    @Override
    public List<SodRule> findActiveRulesForEntityType(String entityType) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<SodRule> cq = cb.createQuery(SodRule.class);
        Root<SodRule> r = cq.from(SodRule.class);
        cq.where(
                cb.equal(r.get("ruleType"), SodRule.RuleType.PERMISSION_PAIR),
                cb.isTrue(r.get("isActive")),
                cb.or(
                        cb.isNull(r.get("entityTypes")),
                        cb.like(r.get("entityTypes"), "%" + entityType + "%")
                )
        );
        return em.createQuery(cq).getResultList();
    }

    @Override
    public boolean existsConflictBetween(Long tenantId, String permissionA, String permissionB) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<SodRule> r = cq.from(SodRule.class);

        Predicate forward  = cb.and(
                cb.equal(r.get("permissionA"), permissionA),
                cb.equal(r.get("permissionB"), permissionB));
        Predicate backward = cb.and(
                cb.equal(r.get("permissionA"), permissionB),
                cb.equal(r.get("permissionB"), permissionA));

        cq.select(cb.count(r)).where(
                cb.equal(r.get("ruleType"), SodRule.RuleType.PERMISSION_PAIR),
                tenantOverlay(cb, r, tenantId),
                cb.or(forward, backward)
        );
        Long count = em.createQuery(cq).getSingleResult();
        return count != null && count > 0;
    }
}
