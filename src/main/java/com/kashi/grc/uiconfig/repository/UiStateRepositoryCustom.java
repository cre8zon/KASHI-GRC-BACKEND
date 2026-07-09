package com.kashi.grc.uiconfig.repository;

import com.kashi.grc.uiconfig.domain.UiState;
import java.util.List;
import java.util.Optional;

/** Criteria API fragment for UiStateRepository. */
public interface UiStateRepositoryCustom {

    /**
     * Active states for a screen (tenant-overlay).
     * Tenant-specific rows sort BEFORE global rows (former ORDER BY tenantId NULLS LAST)
     * so callers that take the first match get the tenant override.
     */
    List<UiState> findByScreenForTenant(String screenKey, Long tenantId);

    /**
     * Single state for screen + type; tenant-specific row preferred over global.
     *
     * NOTE: the former JPQL returned Optional but could match BOTH a global row
     * and a tenant override — Spring Data then threw
     * IncorrectResultSizeDataAccessException. This implementation applies
     * setMaxResults(1) after the same ordering, so the tenant override wins
     * deterministically instead of crashing. Single-row behaviour is unchanged.
     */
    Optional<UiState> findByScreenAndType(String screenKey, String stateType, Long tenantId);
}
