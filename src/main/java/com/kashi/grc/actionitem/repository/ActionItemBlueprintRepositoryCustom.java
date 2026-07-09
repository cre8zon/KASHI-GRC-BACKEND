package com.kashi.grc.actionitem.repository;

import com.kashi.grc.actionitem.domain.ActionItem;
import com.kashi.grc.actionitem.domain.ActionItemBlueprint;

import java.util.List;

/** Criteria API fragment for ActionItemBlueprintRepository. */
public interface ActionItemBlueprintRepositoryCustom {

    /**
     * Active blueprints visible to a tenant, global rows first
     * (former ORDER BY tenantId NULLS FIRST, category, titleTemplate).
     */
    List<ActionItemBlueprint> findVisibleToTenant(Long tenantId);

    /** Active blueprints for a source type visible to a tenant. */
    List<ActionItemBlueprint> findBySourceTypeAndTenant(ActionItem.SourceType sourceType, Long tenantId);
}
