package com.kashi.grc.uiconfig.repository;

import com.kashi.grc.uiconfig.domain.FeatureFlag;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

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
                cb.isTrue(f.get("isEnabled")),
                cb.isNull(f.get("deletedAt"))            // ignore soft-deleted rows
        );
        return em.createQuery(cq).getResultList();
    }

    @Override
    public Set<String> resolveEnabledFeaturesForTenant(Long tenantId) {
        // Fetch all ACTIVE (non-soft-deleted) rows relevant to this tenant:
        // its own tenant rows + the global catalogue rows.
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<FeatureFlag> cq = cb.createQuery(FeatureFlag.class);
        Root<FeatureFlag> f = cq.from(FeatureFlag.class);
        cq.where(
                cb.or(cb.isNull(f.get("tenantId")), cb.equal(f.get("tenantId"), tenantId)),
                cb.isNull(f.get("deletedAt"))
        );
        List<FeatureFlag> rows = em.createQuery(cq).getResultList();

        Map<String, List<FeatureFlag>> byKey = rows.stream()
                .collect(Collectors.groupingBy(FeatureFlag::getFlagKey));

        Set<String> enabled = new HashSet<>();
        for (Map.Entry<String, List<FeatureFlag>> e : byKey.entrySet()) {
            List<FeatureFlag> flags = e.getValue();

            FeatureFlag global = flags.stream()
                    .filter(x -> x.getTenantId() == null).findFirst().orElse(null);
            FeatureFlag tenantRow = flags.stream()
                    .filter(x -> tenantId.equals(x.getTenantId())).findFirst().orElse(null);

            // Mode lives on the global (catalogue) row. Default GLOBAL if absent.
            String mode = (global != null && global.getMode() != null)
                    ? global.getMode() : "GLOBAL";

            if ("LICENSED".equals(mode)) {
                // Licensed: only an active, enabled tenant row grants. Global inert.
                if (tenantRow != null && tenantRow.isEnabled()) enabled.add(e.getKey());
            } else {
                // Global: the global row's isEnabled decides for everyone.
                if (global != null && global.isEnabled()) enabled.add(e.getKey());
            }
        }
        return enabled;
    }
}