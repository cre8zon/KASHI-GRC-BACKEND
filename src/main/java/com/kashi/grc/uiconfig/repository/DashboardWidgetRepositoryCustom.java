package com.kashi.grc.uiconfig.repository;

import com.kashi.grc.uiconfig.domain.DashboardWidget;
import java.util.List;

/** Criteria API fragment for DashboardWidgetRepository. */
public interface DashboardWidgetRepositoryCustom {

    /** Active widgets visible to a tenant (global + tenant). ORDER BY sortOrder. */
    List<DashboardWidget> findActiveByTenant(Long tenantId);
}
