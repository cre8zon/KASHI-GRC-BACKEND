package com.kashi.grc.asset.repository;

import com.kashi.grc.asset.domain.RiskAssetLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface RiskAssetLinkRepository extends JpaRepository<RiskAssetLink, Long> {

    List<RiskAssetLink> findByAssetIdAndTenantId(Long assetId, Long tenantId);

    List<RiskAssetLink> findByRiskIdAndTenantId(Long riskId, Long tenantId);

    List<RiskAssetLink> findByAssetIdInAndTenantId(Collection<Long> assetIds, Long tenantId);

    Optional<RiskAssetLink> findByRiskIdAndAssetId(Long riskId, Long assetId);

    boolean existsByRiskIdAndAssetId(Long riskId, Long assetId);

    long countByAssetId(Long assetId);
}
