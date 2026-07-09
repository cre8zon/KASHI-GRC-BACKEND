package com.kashi.grc.uiconfig.repository;

import com.kashi.grc.uiconfig.domain.UiLayout;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

/**
 * List methods live in UiLayoutRepositoryCustom and are implemented via the
 * JPA Criteria API in UiLayoutRepositoryImpl.
 */
@Repository
public interface UiLayoutRepository
        extends JpaRepository<UiLayout, Long>, UiLayoutRepositoryCustom {

    Optional<UiLayout> findByLayoutKeyAndTenantIdIsNull(String layoutKey);

    Optional<UiLayout> findByLayoutKeyAndTenantId(String layoutKey, Long tenantId);
}
