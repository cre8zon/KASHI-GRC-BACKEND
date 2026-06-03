package com.kashi.grc.issue.repository;

import com.kashi.grc.issue.domain.Issue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface IssueRepository extends JpaRepository<Issue, Long>,
        JpaSpecificationExecutor<Issue> {

    // ── External deduplication ─────────────────────────────────────────────────

    Optional<Issue> findByTenantIdAndExternalSourceAndExternalId(
            Long tenantId, String externalSource, String externalId);

    boolean existsByTenantIdAndExternalSourceAndExternalId(
            Long tenantId, String externalSource, String externalId);

    // ── SLA escalation scheduler ───────────────────────────────────────────────

    /**
     * Returns all issues that are past their dueAt deadline, not yet resolved/closed,
     * and not already marked as sla_breached. Used by IssueService.runSlaEscalation().
     */
    @Query("""
        SELECT i FROM Issue i
        WHERE i.tenantId IS NOT NULL
          AND i.dueAt IS NOT NULL
          AND i.dueAt < :now
          AND i.slaBreached = false
          AND i.status NOT IN (
              com.kashi.grc.issue.domain.Issue$Status.RESOLVED,
              com.kashi.grc.issue.domain.Issue$Status.ACCEPTED_RISK,
              com.kashi.grc.issue.domain.Issue$Status.CLOSED,
              com.kashi.grc.issue.domain.Issue$Status.DUPLICATE
          )
    """)
    List<Issue> findBreachedIssues(@Param("now") LocalDateTime now);

    /**
     * Re-escalation: issues already breached where we haven't escalated
     * in the last 24h (daily re-escalation nudge to manager).
     */
    @Query("""
        SELECT i FROM Issue i
        WHERE i.tenantId IS NOT NULL
          AND i.slaBreached = true
          AND (i.lastEscalatedAt IS NULL OR i.lastEscalatedAt < :cutoff)
          AND i.status NOT IN (
              com.kashi.grc.issue.domain.Issue$Status.RESOLVED,
              com.kashi.grc.issue.domain.Issue$Status.ACCEPTED_RISK,
              com.kashi.grc.issue.domain.Issue$Status.CLOSED,
              com.kashi.grc.issue.domain.Issue$Status.DUPLICATE
          )
    """)
    List<Issue> findActiveBreachedForReescalation(@Param("cutoff") LocalDateTime cutoff);

    // ── Dashboard / analytics queries ──────────────────────────────────────────

    @Query("""
        SELECT i.status, COUNT(i)
        FROM Issue i
        WHERE i.tenantId = :tenantId
        GROUP BY i.status
    """)
    List<Object[]> countByStatusForTenant(@Param("tenantId") Long tenantId);

    @Query("""
        SELECT i.severity, COUNT(i)
        FROM Issue i
        WHERE i.tenantId = :tenantId
          AND i.status NOT IN (
              com.kashi.grc.issue.domain.Issue$Status.CLOSED,
              com.kashi.grc.issue.domain.Issue$Status.DUPLICATE
          )
        GROUP BY i.severity
    """)
    List<Object[]> countOpenBySeverityForTenant(@Param("tenantId") Long tenantId);

    @Query("""
        SELECT COUNT(i) FROM Issue i
        WHERE i.tenantId = :tenantId
          AND i.slaBreached = true
          AND i.status NOT IN (
              com.kashi.grc.issue.domain.Issue$Status.CLOSED,
              com.kashi.grc.issue.domain.Issue$Status.DUPLICATE
          )
    """)
    long countSlaBreachedForTenant(@Param("tenantId") Long tenantId);

    // ── Issue ref sequence (per tenant, per year) ──────────────────────────────

    @Query(value = """
        SELECT COUNT(*) + 1
        FROM issues
        WHERE tenant_id = :tenantId
          AND YEAR(created_at) = YEAR(NOW())
    """, nativeQuery = true)
    long nextIssueRefSequence(@Param("tenantId") Long tenantId);

    // ── Workflow linkage ───────────────────────────────────────────────────────

    Optional<Issue> findByTenantIdAndWorkflowInstanceId(Long tenantId, Long workflowInstanceId);

    // ── Bulk status update (for automated close after workflow completes) ──────

    @Modifying
    @Query("""
        UPDATE Issue i SET i.status = :status, i.closedAt = :now, i.closedBy = :userId
        WHERE i.id = :id AND i.tenantId = :tenantId
    """)
    int closeIssue(@Param("id") Long id,
                   @Param("tenantId") Long tenantId,
                   @Param("status") Issue.Status status,
                   @Param("now") LocalDateTime now,
                   @Param("userId") Long userId);
}