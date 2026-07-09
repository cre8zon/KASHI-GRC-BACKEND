package com.kashi.grc.uiconfig.repository;

import com.kashi.grc.uiconfig.domain.FeatureFlag;
import java.util.List;

/** Criteria API fragment for FeatureFlagRepository. */
public interface FeatureFlagRepositoryCustom {

    /** Enabled flags visible to a tenant: global (tenantId IS NULL) + tenant-specific. */
    List<FeatureFlag> findEnabledForTenant(Long tenantId);
}
