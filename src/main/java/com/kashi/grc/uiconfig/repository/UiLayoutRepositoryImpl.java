package com.kashi.grc.uiconfig.repository;

import com.kashi.grc.uiconfig.domain.UiLayout;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.util.List;

/**
 * JPA Criteria API implementation of UiLayoutRepositoryCustom.
 * Replaces the former JPQL tenant-overlay list queries on UiLayout.
 */
public class UiLayoutRepositoryImpl implements UiLayoutRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    private Predicate tenantOverlay(CriteriaBuilder cb, Root<UiLayout> l, Long tenantId) {
        return cb.or(cb.isNull(l.get("tenantId")), cb.equal(l.get("tenantId"), tenantId));
    }

    @Override
    public List<UiLayout> findAllByScreenAndTenant(String screen, Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<UiLayout> cq = cb.createQuery(UiLayout.class);
        Root<UiLayout> l = cq.from(UiLayout.class);
        cq.where(cb.equal(l.get("screen"), screen), tenantOverlay(cb, l, tenantId));
        cq.orderBy(cb.asc(l.get("id")));
        return em.createQuery(cq).getResultList();
    }

    @Override
    public List<UiLayout> findAllByTenant(Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<UiLayout> cq = cb.createQuery(UiLayout.class);
        Root<UiLayout> l = cq.from(UiLayout.class);
        cq.where(tenantOverlay(cb, l, tenantId));
        cq.orderBy(cb.asc(l.get("screen")), cb.asc(l.get("id")));
        return em.createQuery(cq).getResultList();
    }
}
