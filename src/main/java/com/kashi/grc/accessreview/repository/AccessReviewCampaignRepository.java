package com.kashi.grc.accessreview.repository;

import com.kashi.grc.accessreview.domain.AccessReviewCampaign;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AccessReviewCampaignRepository extends JpaRepository<AccessReviewCampaign, Long> {

    boolean existsByCampaignRefAndTenantId(String campaignRef, Long tenantId);

    @Query("SELECT COALESCE(MAX(c.id), 0) + 1 FROM AccessReviewCampaign c WHERE c.tenantId = :tenantId")
    long nextRefSequence(@Param("tenantId") Long tenantId);

    List<AccessReviewCampaign> findByTenantIdAndIsDeletedFalse(Long tenantId);
}
