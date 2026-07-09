package com.kashi.grc.uiconfig.repository;

import com.kashi.grc.uiconfig.domain.UiNavigation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

/**
 * findAllForTenant lives in UiNavigationRepositoryCustom and is implemented
 * via the JPA Criteria API in UiNavigationRepositoryImpl.
 */
@Repository
public interface UiNavigationRepository
        extends JpaRepository<UiNavigation, Long>, UiNavigationRepositoryCustom {

    Optional<UiNavigation> findByNavKey(String navKey);
}
