package com.kashi.grc.actionitem.repository;

import com.kashi.grc.actionitem.domain.ActionItemBlueprint;
import com.kashi.grc.actionitem.domain.ActionItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ActionItemBlueprintRepository
    extends JpaRepository<ActionItemBlueprint, Long>,
            JpaSpecificationExecutor<ActionItemBlueprint> {

    /** All blueprints visible to a tenant: global + tenant-specific */
    @Query("SELECT b FROM ActionItemBlueprint b WHERE b.isActive = true " +
           "AND (b.tenantId IS NULL OR b.tenantId = :tenantId) " +
           "ORDER BY b.tenantId NULLS FIRST, b.category, b.titleTemplate")
    List<ActionItemBlueprint> findVisibleToTenant(@Param("tenantId") Long tenantId);

    /** Find by unique code (for programmatic lookup) */
    Optional<ActionItemBlueprint> findByBlueprintCode(String code);

    /** Blueprints by source type visible to tenant */
    @Query("SELECT b FROM ActionItemBlueprint b WHERE b.sourceType = :sourceType " +
           "AND b.isActive = true " +
           "AND (b.tenantId IS NULL OR b.tenantId = :tenantId)")
    List<ActionItemBlueprint> findBySourceTypeAndTenant(
        @Param("sourceType") ActionItem.SourceType sourceType,
        @Param("tenantId") Long tenantId);
}
