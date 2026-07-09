package com.kashi.grc.uiconfig.repository;

import com.kashi.grc.uiconfig.domain.UiAction;
import java.util.List;

/**
 * Criteria API fragment for UiActionRepository (Impl-suffix convention).
 * Replaces the former JPQL @Query methods — tenant-overlay pattern:
 * global rows (tenantId IS NULL) plus the tenant's own rows.
 */
public interface UiActionRepositoryCustom {

    /** Active actions for a screen, global + tenant, ORDER BY sortOrder. */
    List<UiAction> findByScreenAndTenant(String screenKey, Long tenantId);

    /** ALL actions for a screen (active + inactive) — Screen Designer admin. */
    List<UiAction> findAllByScreenAndTenant(String screenKey, Long tenantId);

    /** ALL actions across screens — Screen Designer registry. ORDER BY screenKey, sortOrder. */
    List<UiAction> findAllByTenant(Long tenantId);
}
