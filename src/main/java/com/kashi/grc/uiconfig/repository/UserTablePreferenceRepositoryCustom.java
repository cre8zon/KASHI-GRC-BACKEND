package com.kashi.grc.uiconfig.repository;

import com.kashi.grc.uiconfig.domain.UserTablePreference;

import java.util.Optional;

/** Lookups for one user's table layouts. Criteria API, see the Impl. */
public interface UserTablePreferenceRepositoryCustom {

    /** This user's saved layout for one table in one organisation, if any. */
    Optional<UserTablePreference> findOne(Long userId, Long tenantId, String tableKey);

    /** Remove it (back to the table's default). Returns rows deleted. */
    int deleteOne(Long userId, Long tenantId, String tableKey);
}
