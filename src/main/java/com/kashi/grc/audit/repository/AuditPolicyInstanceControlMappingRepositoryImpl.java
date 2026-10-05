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
    public java.util.Map<Long, Long> countPoliciesByControlForEngagement(Long engagementId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<jakarta.persistence.Tuple> cq = cb.createTupleQuery();
        Root<AuditPolicyInstanceControlMapping> m = cq.from(AuditPolicyInstanceControlMapping.class);
        cq.multiselect(m.get("controlInstanceId"), cb.countDistinct(m.get("policyInstanceId")))
                .where(cb.equal(m.get("engagementId"), engagementId))
                .groupBy(m.get("controlInstanceId"));
        java.util.Map<Long, Long> counts = new java.util.HashMap<>();
        for (jakarta.persistence.Tuple t : em.createQuery(cq).getResultList()) {
            counts.put(t.get(0, Long.class), t.get(1, Long.class));
        }
        return counts;
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