package com.kashi.grc.uiconfig.repository;

import com.kashi.grc.uiconfig.domain.DashboardWidget;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.util.List;

/**
 * JPA Criteria API implementation of DashboardWidgetRepositoryCustom.
 * Replaces:
 *   SELECT w FROM DashboardWidget w
 *   WHERE w.isActive = true AND (w.tenantId IS NULL OR w.tenantId = :tenantId)
 *   ORDER BY w.sortOrder
 */
public class DashboardWidgetRepositoryImpl implements DashboardWidgetRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public List<DashboardWidget> findActiveByTenant(Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<DashboardWidget> cq = cb.createQuery(DashboardWidget.class);
        Root<DashboardWidget> wdg = cq.from(DashboardWidget.class);
        cq.where(
                cb.isTrue(wdg.get("isActive")),
                cb.or(cb.isNull(wdg.get("tenantId")), cb.equal(wdg.get("tenantId"), tenantId))
        );
        cq.orderBy(cb.asc(wdg.get("sortOrder")));
        return em.createQuery(cq).getResultList();
    }
}
