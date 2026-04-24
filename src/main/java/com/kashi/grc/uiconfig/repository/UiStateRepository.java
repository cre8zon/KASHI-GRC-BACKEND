package com.kashi.grc.uiconfig.repository;

import com.kashi.grc.uiconfig.domain.UiState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface UiStateRepository extends JpaRepository<UiState, Long> {

    @Query("""
        SELECT s FROM UiState s
        WHERE s.screenKey = :screenKey
          AND (s.tenantId IS NULL OR s.tenantId = :tenantId)
          AND s.isActive = true
        ORDER BY s.tenantId NULLS LAST
    """)
    List<UiState> findByScreenForTenant(
            @Param("screenKey") String screenKey,
            @Param("tenantId") Long tenantId);

    @Query("""
        SELECT s FROM UiState s
        WHERE s.screenKey = :screenKey
          AND s.stateType = :stateType
          AND (s.tenantId IS NULL OR s.tenantId = :tenantId)
          AND s.isActive = true
        ORDER BY s.tenantId NULLS LAST
    """)
    Optional<UiState> findByScreenAndType(
            @Param("screenKey") String screenKey,
            @Param("stateType") String stateType,
            @Param("tenantId") Long tenantId);
}
