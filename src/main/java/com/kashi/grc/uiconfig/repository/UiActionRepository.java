package com.kashi.grc.uiconfig.repository;

import com.kashi.grc.uiconfig.domain.UiAction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface UiActionRepository extends JpaRepository<UiAction, Long> {
    @Query("""
        SELECT a FROM UiAction a
        WHERE a.screenKey = :screenKey
          AND a.isActive = true
          AND (a.tenantId IS NULL OR a.tenantId = :tenantId)
        ORDER BY a.sortOrder
    """)
    List<UiAction> findByScreenAndTenant(
            @Param("screenKey") String screenKey,
            @Param("tenantId") Long tenantId);
}
