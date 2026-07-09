package com.kashi.grc.issue.repository;

import com.kashi.grc.issue.domain.Issue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Derived-name queries only. All former @Query methods (JPQL and native)
 * live in IssueRepositoryCustom and are implemented via the JPA Criteria API
 * in IssueRepositoryImpl:
 *   nextIssueRefSequence, findBreachedIssues, findActiveBreachedForReescalation,
 *   countByStatusForTenant, countOpenBySeverityForTenant,
 *   countSlaBreachedForTenant, closeIssue.
 */
@Repository
public interface IssueRepository extends JpaRepository<Issue, Long>,
        JpaSpecificationExecutor<Issue>,
        IssueRepositoryCustom {

    // ── External deduplication ─────────────────────────────────────────────────

    Optional<Issue> findByTenantIdAndExternalSourceAndExternalId(
            Long tenantId, String externalSource, String externalId);

    boolean existsByTenantIdAndExternalSourceAndExternalId(
            Long tenantId, String externalSource, String externalId);

    // ── Workflow linkage ───────────────────────────────────────────────────────

    Optional<Issue> findByTenantIdAndWorkflowInstanceId(Long tenantId, Long workflowInstanceId);
}
