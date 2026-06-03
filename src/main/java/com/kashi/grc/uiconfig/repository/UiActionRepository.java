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

    // ── NEW: list all actions for a screen (active + inactive) — Screen Designer admin
    @Query("""
        SELECT a FROM UiAction a
        WHERE a.screenKey = :screenKey
          AND (a.tenantId IS NULL OR a.tenantId = :tenantId)
        ORDER BY a.sortOrder
    """)
    List<UiAction> findAllByScreenAndTenant(
            @Param("screenKey") String screenKey,
            @Param("tenantId") Long tenantId);

    // ── NEW: list ALL actions across all screens — Screen Designer registry (derive screen list)
    @Query("""
        SELECT a FROM UiAction a
        WHERE (a.tenantId IS NULL OR a.tenantId = :tenantId)
        ORDER BY a.screenKey, a.sortOrder
    """)
    List<UiAction> findAllByTenant(@Param("tenantId") Long tenantId);
}