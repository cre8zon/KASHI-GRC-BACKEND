package com.kashi.grc.uiconfig.repository;

import com.kashi.grc.uiconfig.domain.UiComponent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

/**
 * findByScreenForTenant lives in UiComponentRepositoryCustom and is
 * implemented via the JPA Criteria API in UiComponentRepositoryImpl.
 */
@Repository
public interface UiComponentRepository
        extends JpaRepository<UiComponent, Long>, UiComponentRepositoryCustom {

    Optional<UiComponent> findByComponentKey(String componentKey);

    List<UiComponent> findByModuleAndIsVisibleTrue(String module);
}
