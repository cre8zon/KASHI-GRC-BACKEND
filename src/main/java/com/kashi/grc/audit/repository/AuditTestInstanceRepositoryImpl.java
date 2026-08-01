package com.kashi.grc.audit.repository;

import com.kashi.grc.audit.domain.AuditTestInstance;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.util.List;

/** JPA Criteria API implementation of AuditTestInstanceRepositoryCustom. */
public class AuditTestInstanceRepositoryImpl implements AuditTestInstanceRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public List<AuditTestInstance> findByEngagementIdAndTenantId(Long engagementId, Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<AuditTestInstance> cq = cb.createQuery(AuditTestInstance.class);
        Root<AuditTestInstance> t = cq.from(AuditTestInstance.class);
        cq.where(
                cb.equal(t.get("engagementId"), engagementId),
                cb.equal(t.get("tenantId"), tenantId)
        );
        cq.orderBy(cb.asc(t.get("testNameSnapshot")));
        return em.createQuery(cq).getResultList();
    }

    @Override
    public List<AuditTestInstance> findManualByTenantAndExpandedTag(Long tenantId, String tag) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<AuditTestInstance> cq = cb.createQuery(AuditTestInstance.class);
        Root<AuditTestInstance> t = cq.from(AuditTestInstance.class);

        Expression<String> wrapped = cb.concat(cb.concat(
                cb.literal(","), t.get("matchedTagsSnapshot")), cb.literal(","));

        cq.where(
                cb.equal(t.get("tenantId"), tenantId),
                // AUTOMATED tests are fed by checkKey routing, never by tag
                cb.notEqual(t.get("automationTypeSnapshot"), "AUTOMATED"),
                cb.or(
                        cb.and(
                                cb.isNotNull(t.get("matchedTagsSnapshot")),
                                cb.like(wrapped, "%," + tag + ",%")
                        ),
                        cb.and(
                                cb.isNull(t.get("matchedTagsSnapshot")),
                                cb.equal(t.get("controlTagSnapshot"), tag)
                        )
                )
        );
        return em.createQuery(cq).getResultList();
    }
}