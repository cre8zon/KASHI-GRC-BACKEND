package com.kashi.grc.uiconfig.repository;

import com.kashi.grc.uiconfig.domain.UiState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * All lookups live in UiStateRepositoryCustom and are implemented via the
 * JPA Criteria API in UiStateRepositoryImpl.
 */
@Repository
public interface UiStateRepository
        extends JpaRepository<UiState, Long>, UiStateRepositoryCustom {
}
