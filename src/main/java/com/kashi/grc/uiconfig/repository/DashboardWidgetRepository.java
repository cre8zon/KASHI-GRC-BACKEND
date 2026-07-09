package com.kashi.grc.uiconfig.repository;

import com.kashi.grc.uiconfig.domain.DashboardWidget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * findActiveByTenant lives in DashboardWidgetRepositoryCustom and is
 * implemented via the JPA Criteria API in DashboardWidgetRepositoryImpl.
 */
@Repository
public interface DashboardWidgetRepository
        extends JpaRepository<DashboardWidget, Long>, DashboardWidgetRepositoryCustom {
}
