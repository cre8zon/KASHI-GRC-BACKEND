package com.kashi.grc.integration.repository;

/** Criteria API fragment for EngagementIntegrationSnapshotRepository. */
public interface EngagementIntegrationSnapshotRepositoryCustom {

    /** Deactivate all snapshots when an engagement closes (CriteriaUpdate). Caller must be @Transactional. */
    int deactivateByEngagementId(Long engagementId, Long tenantId);

    /** Count snapshots with lastResult = 'PASS'. */
    long countPassingByEngagementId(Long engagementId, Long tenantId);

    /** Count snapshots with lastResult = 'FAIL'. */
    long countFailingByEngagementId(Long engagementId, Long tenantId);

    /** Count snapshots with lastResult = 'NOT_RUN'. */
    long countNeverRunByEngagementId(Long engagementId, Long tenantId);
}
