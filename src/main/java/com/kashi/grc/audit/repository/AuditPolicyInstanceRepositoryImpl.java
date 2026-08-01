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

    @Override
    public List<AuditPolicyInstance> findByTenantAndExpandedTag(Long tenantId, String tag) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<AuditPolicyInstance> cq = cb.createQuery(AuditPolicyInstance.class);
        Root<AuditPolicyInstance> p = cq.from(AuditPolicyInstance.class);

        Expression<String> expandedWrapped = cb.concat(cb.concat(
                        cb.literal(","), cb.function("REPLACE", String.class,
                                p.get("matchedTagsSnapshot"), cb.literal(" "), cb.literal(""))),
                cb.literal(","));
        Expression<String> legacyWrapped = cb.concat(cb.concat(
                        cb.literal(","), cb.function("REPLACE", String.class,
                                p.get("controlTagsSnapshot"), cb.literal(" "), cb.literal(""))),
                cb.literal(","));

        cq.where(
                cb.equal(p.get("tenantId"), tenantId),
                cb.or(
                        cb.and(
                                cb.isNotNull(p.get("matchedTagsSnapshot")),
                                cb.like(expandedWrapped, "%," + tag + ",%")
                        ),
                        cb.and(
                                cb.isNull(p.get("matchedTagsSnapshot")),
                                cb.isNotNull(p.get("controlTagsSnapshot")),
                                cb.like(legacyWrapped, "%," + tag + ",%")
                        )
                )
        );
        cq.orderBy(cb.asc(p.get("titleSnapshot")));
        return em.createQuery(cq).getResultList();
    }
}