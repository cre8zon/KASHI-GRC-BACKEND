package com.kashi.grc.audit.repository;

import com.kashi.grc.audit.domain.AuditPolicyInstance;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.util.List;

/** JPA Criteria API implementation of AuditPolicyInstanceRepositoryCustom. */
public class AuditPolicyInstanceRepositoryImpl implements AuditPolicyInstanceRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public List<AuditPolicyInstance> findByEngagementIdAndTenantId(Long engagementId, Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<AuditPolicyInstance> cq = cb.createQuery(AuditPolicyInstance.class);
        Root<AuditPolicyInstance> p = cq.from(AuditPolicyInstance.class);
        cq.where(
                cb.equal(p.get("engagementId"), engagementId),
                cb.equal(p.get("tenantId"), tenantId)
        );
        cq.orderBy(cb.asc(p.get("titleSnapshot")));
        return em.createQuery(cq).getResultList();
    }
}
