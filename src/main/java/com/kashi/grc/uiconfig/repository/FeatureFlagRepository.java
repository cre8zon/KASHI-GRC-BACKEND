package com.kashi.grc.uiconfig.repository;

import com.kashi.grc.uiconfig.domain.FeatureFlag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

/**
 * findEnabledForTenant lives in FeatureFlagRepositoryCustom and is implemented
 * via the JPA Criteria API in FeatureFlagRepositoryImpl.
 */
@Repository
public interface FeatureFlagRepository
        extends JpaRepository<FeatureFlag, Long>, FeatureFlagRepositoryCustom {

    Optional<FeatureFlag> findByFlagKeyAndTenantId(String flagKey, Long tenantId);
    Optional<FeatureFlag> findByFlagKeyAndTenantIdIsNull(String flagKey);

    /** Catalogue: the global feature definitions (tenant_id IS NULL). */
    java.util.List<FeatureFlag> findByTenantIdIsNull();

    /** All explicit entitlement rows for one tenant. */
    java.util.List<FeatureFlag> findByTenantId(Long tenantId);

    /** Every row (any tenant, incl. soft-deleted) for a key — for lifecycle ops. */
    java.util.List<FeatureFlag> findByFlagKey(String flagKey);

    /** Active (non-soft-deleted) tenant rows for a key. */
    java.util.List<FeatureFlag> findByFlagKeyAndTenantIdIsNotNullAndDeletedAtIsNull(String flagKey);
}