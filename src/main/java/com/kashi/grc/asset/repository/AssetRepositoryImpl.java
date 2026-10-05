package com.kashi.grc.asset.repository;

import com.kashi.grc.asset.domain.Asset;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Criteria API implementation of AssetRepositoryCustom.
 * Every method scopes to one tenant and excludes soft-deleted rows.
 */
public class AssetRepositoryImpl implements AssetRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public long nextAssetRefSequence(Long tenantId) {
        LocalDateTime startOfYear     = LocalDate.now().withDayOfYear(1).atStartOfDay();
        LocalDateTime startOfNextYear = startOfYear.plusYears(1);

        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<Asset> a = cq.from(Asset.class);

        cq.select(cb.count(a)).where(
                cb.equal(a.get("tenantId"), tenantId),
                cb.greaterThanOrEqualTo(a.<LocalDateTime>get("createdAt"), startOfYear),
                cb.lessThan(a.<LocalDateTime>get("createdAt"), startOfNextYear)
        );
        Long count = em.createQuery(cq).getSingleResult();
        return (count != null ? count : 0L) + 1;
    }

    @Override
    public List<Object[]> countByStatusForTenant(Long tenantId) {
        return countGroupedBy("status", tenantId);
    }

    @Override
    public List<Object[]> countByTypeForTenant(Long tenantId) {
        return countGroupedBy("assetType", tenantId);
    }

    @Override
    public List<Object[]> countByCriticalityForTenant(Long tenantId) {
        return countGroupedBy("criticality", tenantId);
    }

    private List<Object[]> countGroupedBy(String attribute, Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Root<Asset> a = cq.from(Asset.class);

        Path<?> grouped = a.get(attribute);
        cq.multiselect(grouped, cb.count(a))
                .where(cb.equal(a.get("tenantId"), tenantId),
                       cb.isFalse(a.get("isDeleted")))
                .groupBy(grouped);

        return em.createQuery(cq).getResultList();
    }

    /**
     * Past end of life AND still in service. DISPOSED and RETIRED assets are
     * excluded deliberately: an EOL server that has been retired is the
     * control working, not a finding.
     */
    @Override
    public List<Asset> findPastEndOfLife(Long tenantId, LocalDate asOf) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Asset> cq = cb.createQuery(Asset.class);
        Root<Asset> a = cq.from(Asset.class);

        cq.where(
                cb.equal(a.get("tenantId"), tenantId),
                cb.isFalse(a.get("isDeleted")),
                cb.isNotNull(a.get("endOfLifeDate")),
                cb.lessThan(a.<LocalDate>get("endOfLifeDate"), asOf),
                a.get("status").in(Asset.Status.ACTIVE, Asset.Status.IN_REPAIR)
        );
        return em.createQuery(cq).getResultList();
    }

    @Override
    public List<Asset> findOverdueForReview(Long tenantId, LocalDate asOf) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Asset> cq = cb.createQuery(Asset.class);
        Root<Asset> a = cq.from(Asset.class);

        cq.where(
                cb.equal(a.get("tenantId"), tenantId),
                cb.isFalse(a.get("isDeleted")),
                cb.isNotNull(a.get("nextReviewDate")),
                cb.lessThan(a.<LocalDate>get("nextReviewDate"), asOf),
                cb.not(a.get("status").in(Asset.Status.DISPOSED, Asset.Status.RETIRED))
        );
        return em.createQuery(cq).getResultList();
    }
}
