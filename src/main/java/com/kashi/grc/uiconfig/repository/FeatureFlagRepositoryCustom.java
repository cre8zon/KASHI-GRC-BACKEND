package com.kashi.grc.uiconfig.repository;

import com.kashi.grc.uiconfig.domain.FeatureFlag;
import java.util.List;
import java.util.Set;

/** Criteria API fragment for FeatureFlagRepository. */
public interface FeatureFlagRepositoryCustom {

    /** Enabled flags visible to a tenant: global (tenantId IS NULL) + tenant-specific.
     *  Legacy — retained for callers that want the raw enabled rows. Prefer
     *  resolveEnabledFeaturesForTenant, which applies GLOBAL/LICENSED mode. */
    List<FeatureFlag> findEnabledForTenant(Long tenantId);

    /**
     * Resolve the set of feature keys ENABLED for a tenant, honouring mode and
     * soft-delete. Per feature (ignoring rows with deletedAt != null):
     *   GLOBAL mode   → granted iff the global row is enabled;
     *   LICENSED mode → granted iff an active tenant row exists and is enabled.
     * The invariant (a feature is global XOR licensed, never both active) means
     * there is never a conflict to arbitrate.
     */
    Set<String> resolveEnabledFeaturesForTenant(Long tenantId);
}