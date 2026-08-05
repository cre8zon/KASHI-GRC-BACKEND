package com.kashi.grc.audit.repository;

import com.kashi.grc.audit.domain.AuditSectionInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Derived-name queries only. All former @Query methods live in
 * AuditSectionInstanceRepositoryCustom (JPA Criteria API).
 */
@Repository
public interface AuditSectionInstanceRepository
        extends JpaRepository<AuditSectionInstance, Long>, AuditSectionInstanceRepositoryCustom {

    // ── Full tree retrieval ───────────────────────────────────────────────────

    List<AuditSectionInstance> findByEngagementIdOrderByPathAscOrderNoAsc(Long engagementId);

    long countByEngagementId(Long engagementId);

    /**
     * Used by checkAndFireEngagementsOnboardedGate/checkAndFireEvidenceOwnersAssignedGate
     * to check "is everything assigned yet" WITHOUT fetching every section row in
     * the engagement (which could be 100+ full entities) just to check a single
     * boolean. A COUNT query lets the DB answer directly instead of transferring
     * and hydrating every row so Java can iterate allMatch() over them.
     */
    long countByEngagementIdAndAssignedAuditorIdIsNull(Long engagementId);

    long countByEngagementIdAndAuditeeAssignedUserIdIsNull(Long engagementId);

    List<AuditSectionInstance> findByEngagementIdAndParentInstanceIdIsNullOrderByOrderNoAsc(Long engagementId);

    List<AuditSectionInstance> findByParentInstanceIdOrderByOrderNoAsc(Long parentInstanceId);

    // ── Assignment queries ────────────────────────────────────────────────────

    List<AuditSectionInstance> findByEngagementIdAndAssignedAuditorId(Long engagementId, Long auditorId);

    List<AuditSectionInstance> findByEngagementIdAndAuditeeAssignedUserId(Long engagementId, Long auditeeUserId);

    // ── Depth-scoped retrieval — AuditSectionItemRegistrar (Steps 2 and 3) ────

    List<AuditSectionInstance> findByEngagementIdAndDepthOrderByPathAscOrderNoAsc(
            Long engagementId, int depth);

    // ── Template linkage ──────────────────────────────────────────────────────

    List<AuditSectionInstance> findByTemplateInstanceId(Long templateInstanceId);

    Optional<AuditSectionInstance> findByEngagementIdAndOriginalSectionId(Long engagementId, Long originalSectionId);
}