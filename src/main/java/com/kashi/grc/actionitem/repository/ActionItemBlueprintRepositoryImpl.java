package com.kashi.grc.actionitem.repository;

import com.kashi.grc.actionitem.domain.ActionItem;
import com.kashi.grc.actionitem.domain.ActionItemBlueprint;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.util.List;

/**
 * JPA Criteria API implementation of ActionItemBlueprintRepositoryCustom.
 * NULLS FIRST via CASE WHEN tenantId IS NULL THEN 0 ELSE 1 END ascending —
 * same technique as GuardRuleRepositoryImpl / UiStateRepositoryImpl (inverted).
 */
public class ActionItemBlueprintRepositoryImpl implements ActionItemBlueprintRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    private Predicate tenantOverlay(CriteriaBuilder cb, Root<ActionItemBlueprint> b, Long tenantId) {
        return cb.or(cb.isNull(b.get("tenantId")), cb.equal(b.get("tenantId"), tenantId));
    }

    @Override
    public List<ActionItemBlueprint> findVisibleToTenant(Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<ActionItemBlueprint> cq = cb.createQuery(ActionItemBlueprint.class);
        Root<ActionItemBlueprint> b = cq.from(ActionItemBlueprint.class);

        cq.where(cb.isTrue(b.get("isActive")), tenantOverlay(cb, b, tenantId));
        cq.orderBy(
                cb.asc(cb.selectCase()                       // tenantId NULLS FIRST
                        .when(cb.isNull(b.get("tenantId")), 0)
                        .otherwise(1)),
                cb.asc(b.get("category")),
                cb.asc(b.get("titleTemplate"))
        );
        return em.createQuery(cq).getResultList();
    }

    @Override
    public List<ActionItemBlueprint> findBySourceTypeAndTenant(
            ActionItem.SourceType sourceType, Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<ActionItemBlueprint> cq = cb.createQuery(ActionItemBlueprint.class);
        Root<ActionItemBlueprint> b = cq.from(ActionItemBlueprint.class);
        cq.where(
                cb.equal(b.get("sourceType"), sourceType),
                cb.isTrue(b.get("isActive")),
                tenantOverlay(cb, b, tenantId)
        );
        return em.createQuery(cq).getResultList();
    }
}
