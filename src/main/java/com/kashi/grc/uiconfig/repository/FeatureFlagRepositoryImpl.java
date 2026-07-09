package com.kashi.grc.uiconfig.repository;

import com.kashi.grc.uiconfig.domain.FeatureFlag;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.util.List;

/**
 * JPA Criteria API implementation of FeatureFlagRepositoryCustom.
 * Replaces:
 *   SELECT f FROM FeatureFlag f
 *   WHERE (f.tenantId IS NULL OR f.tenantId = :tenantId) AND f.isEnabled = true
 */
public class FeatureFlagRepositoryImpl implements FeatureFlagRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public List<FeatureFlag> findEnabledForTenant(Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<FeatureFlag> cq = cb.createQuery(FeatureFlag.class);
        Root<FeatureFlag> f = cq.from(FeatureFlag.class);
        cq.where(
                cb.or(cb.isNull(f.get("tenantId")), cb.equal(f.get("tenantId"), tenantId)),
                cb.isTrue(f.get("isEnabled"))
        );
        return em.createQuery(cq).getResultList();
    }
}
