package com.kashi.grc.uiconfig.repository;

import com.kashi.grc.uiconfig.domain.UiComponent;
import com.kashi.grc.uiconfig.domain.UiOption;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.util.Collection;
import java.util.List;

/**
 * JPA Criteria API implementation of UiOptionRepositoryCustom.
 *
 * UiOption has a mapped @ManyToOne to UiComponent, so the JPQL association
 * path o.component.componentKey becomes a Criteria join:
 *   Join<UiOption, UiComponent> comp = o.join("component");
 *   comp.get("componentKey")
 */
public class UiOptionRepositoryImpl implements UiOptionRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public List<UiOption> findByComponentKeyAndTenant(String componentKey, Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<UiOption> cq = cb.createQuery(UiOption.class);
        Root<UiOption> o = cq.from(UiOption.class);
        Join<UiOption, UiComponent> comp = o.join("component");

        cq.where(
                cb.equal(comp.get("componentKey"), componentKey),
                cb.isTrue(o.get("isActive")),
                cb.or(cb.isNull(o.get("tenantId")), cb.equal(o.get("tenantId"), tenantId))
        );
        cq.orderBy(cb.asc(o.get("sortOrder")));
        return em.createQuery(cq).getResultList();
    }

    @Override
    public List<UiOption> findByComponentKeysAndTenant(Collection<String> componentKeys, Long tenantId) {
        // Guard: empty IN () is invalid SQL
        if (componentKeys == null || componentKeys.isEmpty()) return List.of();

        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<UiOption> cq = cb.createQuery(UiOption.class);
        Root<UiOption> o = cq.from(UiOption.class);
        Join<UiOption, UiComponent> comp = o.join("component");

        cq.where(
                comp.get("componentKey").in(componentKeys),
                cb.isTrue(o.get("isActive")),
                cb.or(cb.isNull(o.get("tenantId")), cb.equal(o.get("tenantId"), tenantId))
        );
        cq.orderBy(cb.asc(comp.get("componentKey")), cb.asc(o.get("sortOrder")));
        return em.createQuery(cq).getResultList();
    }
}
