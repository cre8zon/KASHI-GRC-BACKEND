package com.kashi.grc.uiconfig.repository;

import com.kashi.grc.uiconfig.domain.UiNavigation;
import java.util.List;

/** Criteria API fragment for UiNavigationRepository. */
public interface UiNavigationRepositoryCustom {

    /** All nav items visible to a tenant (global + tenant). ORDER BY sortOrder. */
    List<UiNavigation> findAllForTenant(Long tenantId);
}
