package com.kashi.grc.audit.repository;

import java.util.List;

/**
 * Criteria API fragment for AuditEngagementRepository (Impl-suffix convention).
 */
public interface AuditEngagementRepositoryCustom {

    /**
     * Next per-tenant, per-calendar-year sequence number for engagement refs
     * (e.g. ENG-2026-0007). Replaces the former native COUNT(*)+1 with
     * YEAR(created_at)=YEAR(NOW()); the Criteria version uses a sargable
     * [Jan 1, Jan 1 next year) range.
     */
    long nextEngagementRefSequence(Long tenantId);

    /** Count non-cancelled engagements under a project. */
    long countActiveByProjectId(Long projectId);

    /** Dashboard: [status, count] rows grouped by status for a tenant. */
    List<Object[]> countByStatusForTenant(Long tenantId);
}
