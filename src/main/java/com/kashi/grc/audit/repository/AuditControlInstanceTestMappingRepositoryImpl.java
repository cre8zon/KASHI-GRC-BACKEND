package com.kashi.grc.audit.repository;

import com.kashi.grc.audit.domain.AuditControlInstanceTestMapping;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.util.List;

/** JPA Criteria API implementation of AuditControlInstanceTestMappingRepositoryCustom. */
public class AuditControlInstanceTestMappingRepositoryImpl
        implements AuditControlInstanceTestMappingRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public List<Long> findRequiredTestInstanceIdsByControlInstanceId(Long controlId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<AuditControlInstanceTestMapping> m = cq.from(AuditControlInstanceTestMapping.class);
        cq.select(m.get("testInstanceId")).where(
                cb.equal(m.get("controlInstanceId"), controlId),
                cb.isTrue(m.get("isRequired"))
        );
        return em.createQuery(cq).getResultList();
    }

    @Override
    public List<Long> findControlInstanceIdsByTestInstanceId(Long testId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<AuditControlInstanceTestMapping> m = cq.from(AuditControlInstanceTestMapping.class);
        cq.select(m.get("controlInstanceId"))
                .where(cb.equal(m.get("testInstanceId"), testId));
        return em.createQuery(cq).getResultList();
    }
}
