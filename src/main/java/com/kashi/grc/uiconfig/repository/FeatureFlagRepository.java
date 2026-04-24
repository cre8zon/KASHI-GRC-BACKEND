package com.kashi.grc.uiconfig.repository;

import com.kashi.grc.uiconfig.domain.FeatureFlag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface FeatureFlagRepository extends JpaRepository<FeatureFlag, Long> {
    @Query("""
        SELECT f FROM FeatureFlag f
        WHERE (f.tenantId IS NULL OR f.tenantId = :tenantId)
          AND f.isEnabled = true
    """)
    List<FeatureFlag> findEnabledForTenant(@Param("tenantId") Long tenantId);

    Optional<FeatureFlag> findByFlagKeyAndTenantId(String flagKey, Long tenantId);
    Optional<FeatureFlag> findByFlagKeyAndTenantIdIsNull(String flagKey);
}
