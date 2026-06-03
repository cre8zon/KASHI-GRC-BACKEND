package com.kashi.grc.uiconfig.repository;

import com.kashi.grc.uiconfig.domain.UiComponent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface UiComponentRepository extends JpaRepository<UiComponent, Long> {

    Optional<UiComponent> findByComponentKey(String componentKey);

    /**
     * Returns components for a given screen PLUS all global components (screen IS NULL or empty).
     * Global components are option lists (DROPDOWN, BADGE) that apply to multiple screens —
     * e.g. audit_automation_type, audit_test_frequency created without a screen assignment.
     * Without the IS NULL OR screen = '' clause they are invisible to the screen config resolver.
     */
    @Query("""
        SELECT c FROM UiComponent c
        WHERE (c.screen = :screen OR c.screen IS NULL OR c.screen = '')
          AND (c.tenantId IS NULL OR c.tenantId = :tenantId)
          AND c.isVisible = true
        ORDER BY c.componentKey
    """)
    List<UiComponent> findByScreenForTenant(
            @Param("screen") String screen,
            @Param("tenantId") Long tenantId);

    List<UiComponent> findByModuleAndIsVisibleTrue(String module);
}