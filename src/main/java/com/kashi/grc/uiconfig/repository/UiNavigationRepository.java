package com.kashi.grc.uiconfig.repository;

import com.kashi.grc.uiconfig.domain.UiNavigation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface UiNavigationRepository extends JpaRepository<UiNavigation, Long> {

    @Query("""
        SELECT n FROM UiNavigation n
        WHERE (n.tenantId IS NULL OR n.tenantId = :tenantId)
        ORDER BY n.sortOrder
    """)
    List<UiNavigation> findAllForTenant(@Param("tenantId") Long tenantId);

    Optional<UiNavigation> findByNavKey(String navKey);
}
