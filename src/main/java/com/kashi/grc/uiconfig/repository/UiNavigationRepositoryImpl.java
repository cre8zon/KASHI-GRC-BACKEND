package com.kashi.grc.uiconfig.repository;

import com.kashi.grc.uiconfig.domain.UiNavigation;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.util.List;

/**
 * JPA Criteria API implementation of UiNavigationRepositoryCustom.
 * Replaces:
 *   SELECT n FROM UiNavigation n
 *   WHERE (n.tenantId IS NULL OR n.tenantId = :tenantId)
 *   ORDER BY n.sortOrder
 */
public class UiNavigationRepositoryImpl implements UiNavigationRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public List<UiNavigation> findAllForTenant(Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<UiNavigation> cq = cb.createQuery(UiNavigation.class);
        Root<UiNavigation> n = cq.from(UiNavigation.class);
        cq.where(cb.or(
                cb.isNull(n.get("tenantId")),
                cb.equal(n.get("tenantId"), tenantId)
        ));
        cq.orderBy(cb.asc(n.get("sortOrder")));
        return em.createQuery(cq).getResultList();
    }
}
