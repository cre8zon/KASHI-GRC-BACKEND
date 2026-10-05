package com.kashi.grc.evidence.repository;

import com.kashi.grc.evidence.domain.EvidenceLink;

import java.util.List;

/** Criteria API fragment for EvidenceLinkRepository. */
public interface EvidenceLinkRepositoryCustom {

    /**
     * Expire all live links (PENDING_REVIEW / ACCEPTED) for an evidence record
     * (CriteriaUpdate). Caller must be @Transactional.
     */
    int expireByEvidenceRecordId(Long recordId);

    /** Auto-linked links awaiting human review, newest first. */
    List<EvidenceLink> findPendingReviewForTenant(Long tenantId);

    /** Count ACCEPTED links against an entity. */
    long countAcceptedForEntity(String entityType, Long entityId);

    /**
     * Batch: of the given entity IDs, return the subset that have AT LEAST ONE
     * evidence link of ANY status (PENDING_REVIEW or ACCEPTED). One query for a
     * whole control list — avoids N per-control lookups.
     */
    java.util.Set<Long> entityIdsWithAnyLink(String entityType, java.util.List<Long> entityIds);

    /**
     * Batch: of the given entity IDs, the subset with AT LEAST ONE LIVE link —
     * PENDING_REVIEW, ACCEPTED or AUTOMATION_VERIFIED. Unlike
     * {@link #entityIdsWithAnyLink}, rejected and expired links do not count:
     * "has evidence" must not stay true after the only evidence was rejected.
     */
    java.util.Set<Long> entityIdsWithLiveLink(String entityType, java.util.List<Long> entityIds);

    /** Control-instance links whose evidence is also linked to the given test instance. */
    List<EvidenceLink> findControlEvidenceUsedByTest(Long testInstanceId, Long tenantId);

    /** Test-instance links using a specific evidence record. */
    List<EvidenceLink> findTestsUsingControlEvidence(Long evidenceRecordId, Long tenantId);
}