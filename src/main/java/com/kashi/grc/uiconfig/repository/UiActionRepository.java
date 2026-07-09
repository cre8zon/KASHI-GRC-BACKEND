package com.kashi.grc.uiconfig.repository;

import com.kashi.grc.uiconfig.domain.UiAction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * List/filter/sort methods live in UiActionRepositoryCustom and are
 * implemented via the JPA Criteria API in UiActionRepositoryImpl.
 */
@Repository
public interface UiActionRepository
        extends JpaRepository<UiAction, Long>, UiActionRepositoryCustom {
}
