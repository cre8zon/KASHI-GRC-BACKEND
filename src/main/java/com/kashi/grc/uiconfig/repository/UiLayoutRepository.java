package com.kashi.grc.uiconfig.repository;

import com.kashi.grc.uiconfig.domain.UiLayout;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface UiLayoutRepository extends JpaRepository<UiLayout, Long> {

    Optional<UiLayout> findByLayoutKeyAndTenantIdIsNull(String layoutKey);

    Optional<UiLayout> findByLayoutKeyAndTenantId(String layoutKey, Long tenantId);

    // ── NEW: list all layouts for a screen key — used by Screen Designer list endpoint
    @Query("""
        SELECT l FROM UiLayout l
        WHERE l.screen = :screen
          AND (l.tenantId IS NULL OR l.tenantId = :tenantId)
        ORDER BY l.id
    """)
    List<UiLayout> findAllByScreenAndTenant(
            @Param("screen") String screen,
            @Param("tenantId") Long tenantId);

    // ── NEW: list ALL layouts across all screens — Screen Designer registry
    @Query("""
        SELECT l FROM UiLayout l
        WHERE (l.tenantId IS NULL OR l.tenantId = :tenantId)
        ORDER BY l.screen, l.id
    """)
    List<UiLayout> findAllByTenant(@Param("tenantId") Long tenantId);
}