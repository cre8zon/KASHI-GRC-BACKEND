package com.kashi.grc.audit.repository;

import com.kashi.grc.audit.domain.AuditEngagement;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * JPA Criteria API implementation of AuditEngagementRepositoryCustom.
 * Enum literal 'CANCELLED' → AuditEngagement.Status.CANCELLED (type-safe).
 */
public class AuditEngagementRepositoryImpl implements AuditEngagementRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public long nextEngagementRefSequence(Long tenantId) {
        LocalDateTime startOfYear     = LocalDate.now().withDayOfYear(1).atStartOfDay();
        LocalDateTime startOfNextYear = startOfYear.plusYears(1);

        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<AuditEngagement> e = cq.from(AuditEngagement.class);

        cq.select(cb.count(e)).where(
                cb.equal(e.get("tenantId"), tenantId),
                cb.greaterThanOrEqualTo(e.get("createdAt"), startOfYear),
                cb.lessThan(e.get("createdAt"), startOfNextYear)
        );
        Long count = em.createQuery(cq).getSingleResult();
        return (count != null ? count : 0L) + 1;
    }

    @Override
    public long countActiveByProjectId(Long projectId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<AuditEngagement> e = cq.from(AuditEngagement.class);
        cq.select(cb.count(e)).where(
                cb.equal(e.get("projectId"), projectId),
                cb.notEqual(e.get("status"), AuditEngagement.Status.CANCELLED)
        );
        Long r = em.createQuery(cq).getSingleResult();
        return r != null ? r : 0L;
    }

    @Override
    public List<Object[]> countByStatusForTenant(Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Root<AuditEngagement> e = cq.from(AuditEngagement.class);
        cq.multiselect(e.get("status"), cb.count(e))
                .where(cb.equal(e.get("tenantId"), tenantId))
                .groupBy(e.get("status"));
        return em.createQuery(cq).getResultList();
    }
}
