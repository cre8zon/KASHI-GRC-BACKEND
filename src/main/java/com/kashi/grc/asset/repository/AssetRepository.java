package com.kashi.grc.asset.repository;

import com.kashi.grc.asset.domain.Asset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Derived-name queries only. Dynamic predicates live in AssetRepositoryCustom
 * via the Criteria API — no @Query anywhere in this project.
 */
@Repository
public interface AssetRepository extends JpaRepository<Asset, Long>,
        JpaSpecificationExecutor<Asset>,
        AssetRepositoryCustom {

    Optional<Asset> findByIdAndTenantIdAndIsDeletedFalse(Long id, Long tenantId);

    boolean existsByAssetRefAndTenantId(String assetRef, Long tenantId);

    List<Asset> findByTenantIdAndParentAssetIdAndIsDeletedFalse(Long tenantId, Long parentAssetId);

    /** Batch child counts for the tree — one query for a whole page. */
    List<Asset> findByTenantIdAndParentAssetIdInAndIsDeletedFalse(Long tenantId, Collection<Long> parentIds);

    List<Asset> findByTenantIdAndVendorIdAndIsDeletedFalse(Long tenantId, Long vendorId);
}
