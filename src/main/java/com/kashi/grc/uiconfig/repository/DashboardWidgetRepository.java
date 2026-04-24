package com.kashi.grc.uiconfig.repository;

import com.kashi.grc.uiconfig.domain.DashboardWidget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface DashboardWidgetRepository extends JpaRepository<DashboardWidget, Long> {
    @Query("""
        SELECT w FROM DashboardWidget w
        WHERE w.isActive = true
          AND (w.tenantId IS NULL OR w.tenantId = :tenantId)
        ORDER BY w.sortOrder
    """)
    List<DashboardWidget> findActiveByTenant(@Param("tenantId") Long tenantId);
}
