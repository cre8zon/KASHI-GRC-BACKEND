package com.kashi.grc.asset.repository;

import com.kashi.grc.asset.domain.Asset;

import java.time.LocalDate;
import java.util.List;

public interface AssetRepositoryCustom {

    /** Next per-tenant, per-calendar-year sequence for asset refs. */
    long nextAssetRefSequence(Long tenantId);

    /** [status, count] for a tenant's live assets. */
    List<Object[]> countByStatusForTenant(Long tenantId);

    /** [assetType, count] for a tenant's live assets. */
    List<Object[]> countByTypeForTenant(Long tenantId);

    /** [criticality, count] for a tenant's live assets. */
    List<Object[]> countByCriticalityForTenant(Long tenantId);

    /**
     * Assets past their vendor end-of-life and still in service.
     * This is the evidence behind RSK-L-098 / VUL-02.1, and the reason
     * end_of_life_date exists at all.
     */
    List<Asset> findPastEndOfLife(Long tenantId, LocalDate asOf);

    /** Live assets whose review date has passed. */
    List<Asset> findOverdueForReview(Long tenantId, LocalDate asOf);
}
