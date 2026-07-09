package com.kashi.grc.audit.repository;

import com.kashi.grc.audit.domain.AuditTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.util.List;

/** JPA Criteria API implementation of AuditTestRepositoryCustom. */
public class AuditTestRepositoryImpl implements AuditTestRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public long countForTenant(Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<AuditTest> t = cq.from(AuditTest.class);
        cq.select(cb.count(t)).where(
                cb.or(cb.equal(t.get("tenantId"), tenantId), cb.isNull(t.get("tenantId")))
        );
        Long r = em.createQuery(cq).getSingleResult();
        return r != null ? r : 0L;
    }

    @Override
    public List<AuditTest> searchByName(Long tenantId, String search) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<AuditTest> cq = cb.createQuery(AuditTest.class);
        Root<AuditTest> t = cq.from(AuditTest.class);
        cq.where(
                cb.or(cb.isNull(t.get("tenantId")), cb.equal(t.get("tenantId"), tenantId)),
                cb.like(cb.lower(t.get("name")), "%" + search.toLowerCase() + "%")
        );
        return em.createQuery(cq).getResultList();
    }
}
