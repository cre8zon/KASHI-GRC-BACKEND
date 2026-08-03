package com.kashi.grc.audit.repository;

import com.kashi.grc.audit.domain.AuditPolicyInstanceControlMapping;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.util.List;

/** JPA Criteria API implementation of AuditPolicyInstanceControlMappingRepositoryCustom. */
public class AuditPolicyInstanceControlMappingRepositoryImpl
        implements AuditPolicyInstanceControlMappingRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public List<Long> findControlInstanceIdsByPolicyInstanceId(Long policyInstanceId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<AuditPolicyInstanceControlMapping> m = cq.from(AuditPolicyInstanceControlMapping.class);
        cq.select(m.get("controlInstanceId"))
                .where(cb.equal(m.get("policyInstanceId"), policyInstanceId));
        return em.createQuery(cq).getResultList();
    }

    @Override
    public java.util.Set<Long> controlIdsWithPolicyForEngagement(Long engagementId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<AuditPolicyInstanceControlMapping> m = cq.from(AuditPolicyInstanceControlMapping.class);
        cq.select(m.get("controlInstanceId")).distinct(true)
                .where(cb.equal(m.get("engagementId"), engagementId));
        return new java.util.HashSet<>(em.createQuery(cq).getResultList());
    }

    @Override
    public List<Long> findPolicyInstanceIdsByControlInstanceId(Long controlInstanceId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<AuditPolicyInstanceControlMapping> m = cq.from(AuditPolicyInstanceControlMapping.class);
        cq.select(m.get("policyInstanceId"))
                .where(cb.equal(m.get("controlInstanceId"), controlInstanceId));
        return em.createQuery(cq).getResultList();
    }
}