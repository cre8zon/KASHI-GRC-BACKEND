package com.kashi.grc.uiconfig.repository;

import com.kashi.grc.uiconfig.domain.UiLayout;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface UiLayoutRepository extends JpaRepository<UiLayout, Long> {
    Optional<UiLayout> findByLayoutKeyAndTenantIdIsNull(String layoutKey);
    Optional<UiLayout> findByLayoutKeyAndTenantId(String layoutKey, Long tenantId);
}
