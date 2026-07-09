package com.kashi.grc.uiconfig.repository;

import com.kashi.grc.uiconfig.domain.UiState;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.util.List;
import java.util.Optional;

/**
 * JPA Criteria API implementation of UiStateRepositoryCustom.
 *
 * ── NULLS LAST in Criteria ──
 * JPA Criteria has no direct NULLS LAST. Same technique as
 * GuardRuleRepositoryImpl: order by CASE WHEN tenantId IS NULL THEN 1 ELSE 0 END
 * ascending — non-null (tenant) rows first, null (global) rows last.
 */
public class UiStateRepositoryImpl implements UiStateRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    private CriteriaQuery<UiState> baseQuery(CriteriaBuilder cb, String screenKey,
                                             String stateType, Long tenantId) {
        CriteriaQuery<UiState> cq = cb.createQuery(UiState.class);
        Root<UiState> s = cq.from(UiState.class);

        Predicate screenMatch   = cb.equal(s.get("screenKey"), screenKey);
        Predicate active        = cb.isTrue(s.get("isActive"));
        Predicate tenantOverlay = cb.or(cb.isNull(s.get("tenantId")),
                                        cb.equal(s.get("tenantId"), tenantId));

        if (stateType != null) {
            cq.where(screenMatch, cb.equal(s.get("stateType"), stateType), active, tenantOverlay);
        } else {
            cq.where(screenMatch, active, tenantOverlay);
        }

        // tenantId NULLS LAST: tenant rows (0) before global rows (1)
        cq.orderBy(cb.asc(cb.selectCase()
                .when(cb.isNull(s.get("tenantId")), 1)
                .otherwise(0)));
        return cq;
    }

    @Override
    public List<UiState> findByScreenForTenant(String screenKey, Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        return em.createQuery(baseQuery(cb, screenKey, null, tenantId)).getResultList();
    }

    @Override
    public Optional<UiState> findByScreenAndType(String screenKey, String stateType, Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        List<UiState> result = em.createQuery(baseQuery(cb, screenKey, stateType, tenantId))
                .setMaxResults(1)   // tenant override wins; no NonUniqueResult crash
                .getResultList();
        return result.isEmpty() ? Optional.empty() : Optional.of(result.get(0));
    }
}
