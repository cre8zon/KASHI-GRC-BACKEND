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

    @Query("""
        SELECT c FROM UiComponent c
        WHERE c.screen = :screen
          AND (c.tenantId IS NULL OR c.tenantId = :tenantId)
          AND c.isVisible = true
        ORDER BY c.componentKey
    """)
    List<UiComponent> findByScreenForTenant(
            @Param("screen") String screen,
            @Param("tenantId") Long tenantId);

    List<UiComponent> findByModuleAndIsVisibleTrue(String module);
}
