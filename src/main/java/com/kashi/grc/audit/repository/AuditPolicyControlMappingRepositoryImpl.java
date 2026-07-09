package com.kashi.grc.audit.repository;

import com.kashi.grc.audit.domain.AuditPolicyControlMapping;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.util.List;

/** JPA Criteria API implementation of AuditPolicyControlMappingRepositoryCustom. */
public class AuditPolicyControlMappingRepositoryImpl
        implements AuditPolicyControlMappingRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public List<Long> findControlIdsByPolicyId(Long policyId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<AuditPolicyControlMapping> m = cq.from(AuditPolicyControlMapping.class);
        cq.select(m.get("controlId")).where(cb.equal(m.get("policyId"), policyId));
        return em.createQuery(cq).getResultList();
    }
}
