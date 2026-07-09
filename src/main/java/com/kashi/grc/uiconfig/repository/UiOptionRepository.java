package com.kashi.grc.uiconfig.repository;

import com.kashi.grc.uiconfig.domain.UiOption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

/**
 * Tenant-overlay list methods live in UiOptionRepositoryCustom and are
 * implemented via the JPA Criteria API in UiOptionRepositoryImpl.
 */
@Repository
public interface UiOptionRepository
        extends JpaRepository<UiOption, Long>, UiOptionRepositoryCustom {

    List<UiOption> findByComponentIdAndIsActiveTrueOrderBySortOrder(Long componentId);
}
