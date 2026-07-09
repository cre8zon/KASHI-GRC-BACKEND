package com.kashi.grc.audit.repository;

import com.kashi.grc.audit.domain.AuditPolicy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.time.LocalDate;
import java.util.List;

/**
 * JPA Criteria API implementation of AuditPolicyRepositoryCustom.
 * Enum literal 'APPROVED' → AuditPolicy.PolicyStatus.APPROVED (type-safe).
 */
public class AuditPolicyRepositoryImpl implements AuditPolicyRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    private Predicate visibleToTenant(CriteriaBuilder cb, Root<AuditPolicy> p, Long tenantId) {
        return cb.or(cb.equal(p.get("tenantId"), tenantId), cb.isNull(p.get("tenantId")));
    }

    @Override
    public long countForTenant(Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<AuditPolicy> p = cq.from(AuditPolicy.class);
        cq.select(cb.count(p)).where(visibleToTenant(cb, p, tenantId));
        Long r = em.createQuery(cq).getSingleResult();
        return r != null ? r : 0L;
    }

    @Override
    public List<AuditPolicy> findByTenantIdOrderByTitleAsc(Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<AuditPolicy> cq = cb.createQuery(AuditPolicy.class);
        Root<AuditPolicy> p = cq.from(AuditPolicy.class);
        cq.where(visibleToTenant(cb, p, tenantId));
        cq.orderBy(cb.asc(p.get("title")));
        return em.createQuery(cq).getResultList();
    }

    @Override
    public List<AuditPolicy> findByTenantIdAndStatus(Long tenantId, AuditPolicy.PolicyStatus status) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<AuditPolicy> cq = cb.createQuery(AuditPolicy.class);
        Root<AuditPolicy> p = cq.from(AuditPolicy.class);
        cq.where(
                visibleToTenant(cb, p, tenantId),
                cb.equal(p.get("status"), status)
        );
        return em.createQuery(cq).getResultList();
    }

    @Override
    public List<AuditPolicy> findByPolicyRefForTenant(String policyRef, Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<AuditPolicy> cq = cb.createQuery(AuditPolicy.class);
        Root<AuditPolicy> p = cq.from(AuditPolicy.class);
        cq.where(
                cb.equal(p.get("policyRef"), policyRef),
                visibleToTenant(cb, p, tenantId)
        );
        cq.orderBy(cb.desc(p.get("tenantId")));   // tenant row first, global (NULL) last
        return em.createQuery(cq).getResultList();
    }

    @Override
    public List<AuditPolicy> searchByTitle(Long tenantId, String search) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<AuditPolicy> cq = cb.createQuery(AuditPolicy.class);
        Root<AuditPolicy> p = cq.from(AuditPolicy.class);
        cq.where(
                visibleToTenant(cb, p, tenantId),
                cb.like(cb.lower(p.get("title")), "%" + search.toLowerCase() + "%")
        );
        return em.createQuery(cq).getResultList();
    }

    @Override
    public List<AuditPolicy> findDueForReview(Long tenantId, LocalDate reviewBefore) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<AuditPolicy> cq = cb.createQuery(AuditPolicy.class);
        Root<AuditPolicy> p = cq.from(AuditPolicy.class);
        cq.where(
                visibleToTenant(cb, p, tenantId),
                cb.lessThanOrEqualTo(p.get("nextReviewDate"), reviewBefore),
                cb.equal(p.get("status"), AuditPolicy.PolicyStatus.APPROVED)
        );
        return em.createQuery(cq).getResultList();
    }
}
