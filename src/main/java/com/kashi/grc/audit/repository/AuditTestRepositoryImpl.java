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
    public List<AuditTestSummary> findSummariesForTenant(Long tenantId, String search) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<AuditTestSummary> cq = cb.createQuery(AuditTestSummary.class);
        Root<AuditTest> t = cq.from(AuditTest.class);

        // cb.construct is what keeps test_procedure and evidence_guidance out of
        // the generated SELECT. Column order must match the record.
        cq.select(cb.construct(AuditTestSummary.class,
                t.get("id"), t.get("name"), t.get("testRef"), t.get("description"),
                t.get("frameworkRef"), t.get("frameworkTestId"), t.get("controlTag"),
                t.get("automationType"), t.get("automationKey"), t.get("frequency"),
                t.get("tenantId"), t.get("createdAt")));

        List<Predicate> where = new java.util.ArrayList<>();
        where.add(cb.or(cb.isNull(t.get("tenantId")), cb.equal(t.get("tenantId"), tenantId)));
        if (search != null && !search.isBlank()) {
            String like = "%" + search.toLowerCase() + "%";
            where.add(cb.or(
                    cb.like(cb.lower(t.get("name")),    like),
                    cb.like(cb.lower(t.get("testRef")), like)));
        }
        cq.where(where.toArray(new Predicate[0]));
        // The unsorted list came back in insertion order, which for a 200-row
        // library is effectively random. Name matches how people look tests up.
        cq.orderBy(cb.asc(t.get("name")));
        return em.createQuery(cq).getResultList();
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