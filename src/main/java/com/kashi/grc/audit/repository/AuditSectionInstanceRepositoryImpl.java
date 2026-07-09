package com.kashi.grc.audit.repository;

import com.kashi.grc.audit.domain.AuditSectionInstance;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.util.List;

/** JPA Criteria API implementation of AuditSectionInstanceRepositoryCustom. */
public class AuditSectionInstanceRepositoryImpl implements AuditSectionInstanceRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public List<AuditSectionInstance> findAllDescendants(Long instanceId, String pathPrefix) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<AuditSectionInstance> cq = cb.createQuery(AuditSectionInstance.class);
        Root<AuditSectionInstance> s = cq.from(AuditSectionInstance.class);
        cq.where(
                cb.like(s.get("path"), pathPrefix + "%"),
                cb.notEqual(s.get("id"), instanceId)
        );
        cq.orderBy(cb.asc(s.get("path")), cb.asc(s.get("orderNo")));
        return em.createQuery(cq).getResultList();
    }

    private List<Long> distinctAssigned(String field, Long engagementId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<AuditSectionInstance> s = cq.from(AuditSectionInstance.class);
        cq.select(s.get(field)).distinct(true).where(
                cb.equal(s.get("engagementId"), engagementId),
                cb.isNotNull(s.get(field))
        );
        return em.createQuery(cq).getResultList();
    }

    @Override
    public List<Long> findDistinctAssignedAuditorIdsByEngagementId(Long engagementId) {
        return distinctAssigned("assignedAuditorId", engagementId);
    }

    @Override
    public List<Long> findDistinctAssignedAuditeeIdsByEngagementId(Long engagementId) {
        return distinctAssigned("auditeeAssignedUserId", engagementId);
    }

    @Override
    public long countSubmittedByEngagement(Long engagementId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<AuditSectionInstance> s = cq.from(AuditSectionInstance.class);
        cq.select(cb.count(s)).where(
                cb.equal(s.get("engagementId"), engagementId),
                cb.isNotNull(s.get("submittedAt"))
        );
        Long r = em.createQuery(cq).getSingleResult();
        return r != null ? r : 0L;
    }

    @Override
    public long countTotalByEngagement(Long engagementId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<AuditSectionInstance> s = cq.from(AuditSectionInstance.class);
        cq.select(cb.count(s)).where(cb.equal(s.get("engagementId"), engagementId));
        Long r = em.createQuery(cq).getSingleResult();
        return r != null ? r : 0L;
    }
}
