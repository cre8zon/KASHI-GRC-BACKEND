package com.kashi.grc.audit.repository;

import com.kashi.grc.audit.domain.AuditControlInstance;
import com.kashi.grc.audit.domain.AuditEngagement;
import com.kashi.grc.audit.domain.AuditSectionInstance;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JPA Criteria API implementation of AuditControlInstanceRepositoryCustom.
 *
 * Conversion notes:
 *  - "sectionPath LIKE CONCAT(:prefix, '%')" → cb.like(path, prefix + "%").
 *  - IN (SELECT ...) subqueries → Subquery<...> (originalSectionId lookup,
 *    section-auditor path lookup, tenant engagement scoping).
 *  - "SELECT new map(id, assignedAuditorId)" → multiselect Object[] mapped to
 *    HashMap in Java (HashMap, not Map.of — assignedAuditorId can be null).
 *  - Enum literal 'NOT_TESTED' → AuditControlInstance.TestResult.NOT_TESTED.
 */
public class AuditControlInstanceRepositoryImpl implements AuditControlInstanceRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public List<AuditControlInstance> findByEngagementIdAndSectionPathStartingWith(
            Long engagementId, String pathPrefix) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<AuditControlInstance> cq = cb.createQuery(AuditControlInstance.class);
        Root<AuditControlInstance> c = cq.from(AuditControlInstance.class);
        cq.where(
                cb.equal(c.get("engagementId"), engagementId),
                cb.like(c.get("sectionPath"), pathPrefix + "%")
        );
        cq.orderBy(cb.asc(c.get("sectionPath")), cb.asc(c.get("orderNo")));
        return em.createQuery(cq).getResultList();
    }

    @Override
    public List<AuditControlInstance> findBySectionInstanceId_OriginalSectionId(Long originalSectionId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<AuditControlInstance> cq = cb.createQuery(AuditControlInstance.class);
        Root<AuditControlInstance> c = cq.from(AuditControlInstance.class);

        Subquery<Long> sectionIds = cq.subquery(Long.class);
        Root<AuditSectionInstance> s = sectionIds.from(AuditSectionInstance.class);
        sectionIds.select(s.get("id"))
                .where(cb.equal(s.get("originalSectionId"), originalSectionId));

        cq.where(c.get("sectionInstanceId").in(sectionIds));
        return em.createQuery(cq).getResultList();
    }

    @Override
    public List<AuditControlInstance> findByEngagementIdAndSectionAuditorId(
            Long engagementId, Long auditorId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<AuditControlInstance> cq = cb.createQuery(AuditControlInstance.class);
        Root<AuditControlInstance> c = cq.from(AuditControlInstance.class);

        Subquery<String> paths = cq.subquery(String.class);
        Root<AuditSectionInstance> s = paths.from(AuditSectionInstance.class);
        paths.select(s.get("path")).where(
                cb.equal(s.get("engagementId"), engagementId),
                cb.equal(s.get("assignedAuditorId"), auditorId)
        );

        cq.where(
                cb.equal(c.get("engagementId"), engagementId),
                c.get("sectionPath").in(paths)
        );
        return em.createQuery(cq).getResultList();
    }

    @Override
    public List<Long> findDistinctAssignedAuditeeIdsByEngagementId(Long engagementId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<AuditControlInstance> c = cq.from(AuditControlInstance.class);
        cq.select(c.get("auditeeAssignedUserId")).distinct(true)
                .where(
                        cb.equal(c.get("engagementId"), engagementId),
                        cb.isNotNull(c.get("auditeeAssignedUserId"))
                );
        return em.createQuery(cq).getResultList();
    }

    @Override
    public List<AuditControlInstance> findDueForEvidenceReminder(LocalDate deadline) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<AuditControlInstance> cq = cb.createQuery(AuditControlInstance.class);
        Root<AuditControlInstance> c = cq.from(AuditControlInstance.class);
        cq.where(
                cb.isNotNull(c.get("auditeeAssignedUserId")),
                cb.isFalse(c.get("auditeeEvidenceSubmitted")),
                cb.isNotNull(c.get("evidenceDueDate")),
                cb.lessThanOrEqualTo(c.get("evidenceDueDate"), deadline)
        );
        return em.createQuery(cq).getResultList();
    }

    @Override
    public List<Map<String, Object>> findByTenantIdAndControlTagSnapshot(Long tenantId, String tag) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Root<AuditControlInstance> c = cq.from(AuditControlInstance.class);

        Subquery<Long> engagementIds = cq.subquery(Long.class);
        Root<AuditEngagement> e = engagementIds.from(AuditEngagement.class);
        engagementIds.select(e.get("id")).where(cb.equal(e.get("tenantId"), tenantId));

        cq.multiselect(c.get("id"), c.get("assignedAuditorId"))
                .where(
                        c.get("engagementId").in(engagementIds),
                        cb.equal(c.get("controlTagSnapshot"), tag)
                );

        return em.createQuery(cq).getResultList().stream()
                .map(row -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", row[0]);
                    m.put("assignedAuditorId", row[1]);   // may be null — HashMap tolerates it
                    return m;
                })
                .toList();
    }

    @Override
    public long countTestedByEngagement(Long engagementId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<AuditControlInstance> c = cq.from(AuditControlInstance.class);
        cq.select(cb.count(c)).where(
                cb.equal(c.get("engagementId"), engagementId),
                cb.isNotNull(c.get("testResult")),
                cb.notEqual(c.get("testResult"), AuditControlInstance.TestResult.NOT_TESTED)
        );
        Long r = em.createQuery(cq).getSingleResult();
        return r != null ? r : 0L;
    }

    @Override
    public List<Object[]> countByResultForEngagement(Long engagementId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Root<AuditControlInstance> c = cq.from(AuditControlInstance.class);
        cq.multiselect(c.get("testResult"), cb.count(c))
                .where(cb.equal(c.get("engagementId"), engagementId))
                .groupBy(c.get("testResult"));
        return em.createQuery(cq).getResultList();
    }

    @Override
    public long countFindingsLinkedByEngagement(Long engagementId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<AuditControlInstance> c = cq.from(AuditControlInstance.class);
        cq.select(cb.count(c)).where(
                cb.equal(c.get("engagementId"), engagementId),
                cb.isTrue(c.get("findingLinked"))
        );
        Long r = em.createQuery(cq).getSingleResult();
        return r != null ? r : 0L;
    }

    @Override
    public long countTestedByEngagementId(Long engagementId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<AuditControlInstance> c = cq.from(AuditControlInstance.class);
        // SQL "<>" excludes NULL rows — identical semantics to the former JPQL
        cq.select(cb.count(c)).where(
                cb.equal(c.get("engagementId"), engagementId),
                cb.notEqual(c.get("testResult"), AuditControlInstance.TestResult.NOT_TESTED)
        );
        Long r = em.createQuery(cq).getSingleResult();
        return r != null ? r : 0L;
    }
}
