package com.kashi.grc.uiconfig.repository;

import com.kashi.grc.uiconfig.domain.UiAction;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.util.List;

/**
 * JPA Criteria API implementation of UiActionRepositoryCustom.
 * Replaces the former JPQL:
 *   SELECT a FROM UiAction a
 *   WHERE [a.screenKey = :screenKey] [AND a.isActive = true]
 *     AND (a.tenantId IS NULL OR a.tenantId = :tenantId)
 *   ORDER BY [a.screenKey,] a.sortOrder
 */
public class UiActionRepositoryImpl implements UiActionRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    /** Tenant-overlay predicate: global rows (tenantId IS NULL) OR this tenant's rows. */
    private Predicate tenantOverlay(CriteriaBuilder cb, Root<UiAction> a, Long tenantId) {
        return cb.or(cb.isNull(a.get("tenantId")), cb.equal(a.get("tenantId"), tenantId));
    }

    @Override
    public List<UiAction> findByScreenAndTenant(String screenKey, Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<UiAction> cq = cb.createQuery(UiAction.class);
        Root<UiAction> a = cq.from(UiAction.class);
        cq.where(
                cb.equal(a.get("screenKey"), screenKey),
                cb.isTrue(a.get("isActive")),
                tenantOverlay(cb, a, tenantId)
        );
        cq.orderBy(cb.asc(a.get("sortOrder")));
        return em.createQuery(cq).getResultList();
    }

    @Override
    public List<UiAction> findAllByScreenAndTenant(String screenKey, Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<UiAction> cq = cb.createQuery(UiAction.class);
        Root<UiAction> a = cq.from(UiAction.class);
        cq.where(
                cb.equal(a.get("screenKey"), screenKey),
                tenantOverlay(cb, a, tenantId)
        );
        cq.orderBy(cb.asc(a.get("sortOrder")));
        return em.createQuery(cq).getResultList();
    }

    @Override
    public List<UiAction> findAllByTenant(Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<UiAction> cq = cb.createQuery(UiAction.class);
        Root<UiAction> a = cq.from(UiAction.class);
        cq.where(tenantOverlay(cb, a, tenantId));
        cq.orderBy(cb.asc(a.get("screenKey")), cb.asc(a.get("sortOrder")));
        return em.createQuery(cq).getResultList();
    }
}
