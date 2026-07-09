package com.kashi.grc.issue.repository;

import com.kashi.grc.issue.domain.Issue;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Criteria API fragment for IssueRepository (Impl-suffix convention),
 * following the AssessmentResponseRepositoryCustom / -Impl pattern.
 */
public interface IssueRepositoryCustom {

    /**
     * Next per-tenant, per-calendar-year sequence number for issue refs.
     * Replaces the former native SQL COUNT(*)+1 with YEAR(created_at)=YEAR(NOW());
     * the Criteria version uses a sargable [Jan 1, Jan 1 next year) range.
     */
    long nextIssueRefSequence(Long tenantId);

    /**
     * Issues past their dueAt deadline, not resolved/closed, not yet flagged
     * sla_breached. Used by IssueService.runSlaEscalation().
     */
    List<Issue> findBreachedIssues(LocalDateTime now);

    /**
     * Re-escalation: issues already breached where we haven't escalated in the
     * last 24h (daily nudge to manager).
     */
    List<Issue> findActiveBreachedForReescalation(LocalDateTime cutoff);

    /** Dashboard: [status, count] rows grouped by status for a tenant. */
    List<Object[]> countByStatusForTenant(Long tenantId);

    /** Dashboard: [severity, count] rows for open issues (not CLOSED/DUPLICATE). */
    List<Object[]> countOpenBySeverityForTenant(Long tenantId);

    /** Dashboard: count of SLA-breached issues still open (not CLOSED/DUPLICATE). */
    long countSlaBreachedForTenant(Long tenantId);

    /**
     * Bulk close: sets status, closedAt, closedBy on one issue (tenant-scoped).
     * Returns number of rows updated (0 if id/tenant mismatch).
     * Caller must be @Transactional — same requirement as the former
     * @Modifying JPQL UPDATE this replaces.
     */
    int closeIssue(Long id, Long tenantId, Issue.Status status, LocalDateTime now, Long userId);
}
