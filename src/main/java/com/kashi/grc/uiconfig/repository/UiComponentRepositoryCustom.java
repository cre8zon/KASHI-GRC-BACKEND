package com.kashi.grc.uiconfig.repository;

import com.kashi.grc.uiconfig.domain.UiComponent;
import java.util.List;

/** Criteria API fragment for UiComponentRepository. */
public interface UiComponentRepositoryCustom {

    /**
     * Components for a screen PLUS all global components (screen IS NULL or '').
     * Global components are option lists (DROPDOWN, BADGE) shared by multiple
     * screens — without the IS NULL / '' clause they'd be invisible to the
     * screen config resolver. Tenant-overlay applies; visible only.
     * ORDER BY componentKey.
     */
    List<UiComponent> findByScreenForTenant(String screen, Long tenantId);
}
