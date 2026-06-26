package com.kashi.grc.uiconfig.repository;

import com.kashi.grc.uiconfig.domain.UiOption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.Collection;
import java.util.List;

@Repository
public interface UiOptionRepository extends JpaRepository<UiOption, Long> {
    List<UiOption> findByComponentIdAndIsActiveTrueOrderBySortOrder(Long componentId);

    @Query("""
        SELECT o FROM UiOption o
        WHERE o.component.componentKey = :componentKey
          AND o.isActive = true
          AND (o.tenantId IS NULL OR o.tenantId = :tenantId)
        ORDER BY o.sortOrder
    """)
    List<UiOption> findByComponentKeyAndTenant(
            @Param("componentKey") String componentKey,
            @Param("tenantId") Long tenantId);

    /**
     * Batch fetch options for all component keys in one query.
     * Replaces the N+1 loop in UiConfigServiceImpl.getScreenConfig().
     */
    @Query("""
        SELECT o FROM UiOption o
        WHERE o.component.componentKey IN :componentKeys
          AND o.isActive = true
          AND (o.tenantId IS NULL OR o.tenantId = :tenantId)
        ORDER BY o.component.componentKey, o.sortOrder
    """)
    List<UiOption> findByComponentKeysAndTenant(
            @Param("componentKeys") Collection<String> componentKeys,
            @Param("tenantId") Long tenantId);
}