package com.kashi.grc.risk.repository;

import com.kashi.grc.risk.domain.Risk;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * JPA Criteria API implementation of RiskRepositoryCustom.
 *
 * Every method here filters tenant_id = :tenantId with an equality predicate,
 * which excludes library rows (tenant_id IS NULL) automatically — SQL NULL is
 * never equal to anything. That is the intent: a tenant's counts must not
 * include 28 platform scenarios they have not adopted.
 */
public class RiskRepositoryImpl implements RiskRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public long nextRiskRefSequence(Long tenantId) {
        LocalDateTime startOfYear     = LocalDate.now().withDayOfYear(1).atStartOfDay();
        LocalDateTime startOfNextYear = startOfYear.plusYears(1);

        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<Risk> r = cq.from(Risk.class);

        cq.select(cb.count(r)).where(
                cb.equal(r.get("tenantId"), tenantId),
                cb.greaterThanOrEqualTo(r.<LocalDateTime>get("createdAt"), startOfYear),
                cb.lessThan(r.<LocalDateTime>get("createdAt"), startOfNextYear)
        );
        Long count = em.createQuery(cq).getSingleResult();
        return (count != null ? count : 0L) + 1;
    }

    @Override
    public List<Object[]> countByStatusForTenant(Long tenantId) {
        return countGroupedBy("status", tenantId);
    }

    @Override
    public List<Object[]> countByCategoryForTenant(Long tenantId) {
        return countGroupedBy("category", tenantId);
    }

    private List<Object[]> countGroupedBy(String attribute, Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Root<Risk> r = cq.from(Risk.class);

        Path<?> grouped = r.get(attribute);
        cq.multiselect(grouped, cb.count(r))
                .where(cb.equal(r.get("tenantId"), tenantId),
                       cb.isFalse(r.get("isDeleted")))
                .groupBy(grouped);

        return em.createQuery(cq).getResultList();
    }

    @Override
    public List<Risk> findOverdueForReview(Long tenantId, LocalDate asOf) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Risk> cq = cb.createQuery(Risk.class);
        Root<Risk> r = cq.from(Risk.class);

        cq.where(
                cb.equal(r.get("tenantId"), tenantId),
                cb.isFalse(r.get("isDeleted")),
                cb.isNotNull(r.get("nextReviewDate")),
                cb.lessThan(r.<LocalDate>get("nextReviewDate"), asOf),
                cb.notEqual(r.get("status"), Risk.Status.CLOSED)
        );
        return em.createQuery(cq).getResultList();
    }
}
