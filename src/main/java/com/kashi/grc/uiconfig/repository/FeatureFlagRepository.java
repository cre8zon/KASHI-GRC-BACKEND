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
}
