package com.kashi.grc.uiconfig.repository;

import com.kashi.grc.uiconfig.domain.UiOption;
import java.util.Collection;
import java.util.List;

/** Criteria API fragment for UiOptionRepository. */
public interface UiOptionRepositoryCustom {

    /** Active options for one component key (tenant-overlay). ORDER BY sortOrder. */
    List<UiOption> findByComponentKeyAndTenant(String componentKey, Long tenantId);

    /**
     * Batch fetch options for all component keys in one query.
     * Replaces the N+1 loop in UiConfigServiceImpl.getScreenConfig().
     * ORDER BY component.componentKey, sortOrder.
     */
    List<UiOption> findByComponentKeysAndTenant(Collection<String> componentKeys, Long tenantId);
}
