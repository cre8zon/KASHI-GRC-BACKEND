package com.kashi.grc.uiconfig.repository;

import com.kashi.grc.uiconfig.domain.Dashboard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DashboardRepository extends JpaRepository<Dashboard, Long> {

    /**
     * Every dashboard this caller could see, before permission filtering.
     *
     * Platform rows (tenant_id NULL) plus the tenant's own, plus the caller's
     * personal ones. Personal dashboards belonging to OTHER people are excluded
     * here rather than filtered later, so a bug in the service layer cannot
     * leak them.
     */
    @Query("""
        SELECT d FROM Dashboard d
         WHERE d.isActive = true
           AND (d.tenantId IS NULL OR d.tenantId = :tenantId)
           AND (d.scope <> com.kashi.grc.uiconfig.domain.Dashboard$Scope.PERSONAL
                OR d.ownerUserId = :userId)
         ORDER BY d.sortOrder ASC, d.name ASC
        """)
    List<Dashboard> findVisible(@Param("tenantId") Long tenantId, @Param("userId") Long userId);

    /**
     * A tenant's own override wins over the platform row of the same key.
     * Ordered tenant-first so the caller takes the head.
     */
    @Query("""
        SELECT d FROM Dashboard d
         WHERE d.dashboardKey = :key AND d.isActive = true
           AND (d.tenantId IS NULL OR d.tenantId = :tenantId)
         ORDER BY CASE WHEN d.tenantId IS NULL THEN 1 ELSE 0 END
        """)
    List<Dashboard> findByKeyForTenant(@Param("key") String key, @Param("tenantId") Long tenantId);

    Optional<Dashboard> findByDashboardKeyAndTenantId(String dashboardKey, Long tenantId);

    List<Dashboard> findByEntityTypeAndIsActiveTrueOrderBySortOrderAsc(String entityType);
}