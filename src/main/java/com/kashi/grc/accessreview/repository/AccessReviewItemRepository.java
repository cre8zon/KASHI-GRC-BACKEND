package com.kashi.grc.accessreview.repository;

import com.kashi.grc.accessreview.domain.AccessReviewItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AccessReviewItemRepository extends JpaRepository<AccessReviewItem, Long> {

    List<AccessReviewItem> findByCampaignId(Long campaignId);

    List<AccessReviewItem> findByTenantId(Long tenantId);

    /** A reviewer's own queue — the only list most people will ever open. */
    List<AccessReviewItem> findByTenantIdAndReviewerUserId(Long tenantId, Long reviewerUserId);

    long countByCampaignId(Long campaignId);
}
