package com.kashi.grc.uiconfig.repository;

import com.kashi.grc.uiconfig.domain.UiComponent;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.util.List;

/**
 * JPA Criteria API implementation of UiComponentRepositoryCustom.
 * Replaces the former JPQL:
 *   SELECT c FROM UiComponent c
 *   WHERE (c.screen = :screen OR c.screen IS NULL OR c.screen = '')
 *     AND (c.tenantId IS NULL OR c.tenantId = :tenantId)
 *     AND c.isVisible = true
 *   ORDER BY c.componentKey
 */
public class UiComponentRepositoryImpl implements UiComponentRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public List<UiComponent> findByScreenForTenant(String screen, Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<UiComponent> cq = cb.createQuery(UiComponent.class);
        Root<UiComponent> c = cq.from(UiComponent.class);

        Predicate screenMatch = cb.or(
                cb.equal(c.get("screen"), screen),
                cb.isNull(c.get("screen")),
                cb.equal(c.get("screen"), "")
        );
        Predicate tenantOverlay = cb.or(
                cb.isNull(c.get("tenantId")),
                cb.equal(c.get("tenantId"), tenantId)
        );

        cq.where(screenMatch, tenantOverlay, cb.isTrue(c.get("isVisible")));
        cq.orderBy(cb.asc(c.get("componentKey")));
        return em.createQuery(cq).getResultList();
    }
}
