package com.kashi.grc.audit.repository;

import com.kashi.grc.audit.domain.AuditControlInstance;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Criteria API fragment for AuditControlInstanceRepository (Impl-suffix convention). */
public interface AuditControlInstanceRepositoryCustom {

    /** Controls in a section subtree (sectionPath LIKE prefix%), tree-ordered. */
    List<AuditControlInstance> findByEngagementIdAndSectionPathStartingWith(
            Long engagementId, String pathPrefix);

    /** Controls whose section instance derives from a given library section. */
    List<AuditControlInstance> findBySectionInstanceId_OriginalSectionId(Long originalSectionId);

    /**
     * Controls under sections assigned to the given auditor. Fallback in
     * AuditControlSectionItemRegistrar when controls have no direct
     * assignedAuditorId (section assignment no longer cascades to controls).
     */
    List<AuditControlInstance> findByEngagementIdAndSectionAuditorId(Long engagementId, Long auditorId);

    /** Distinct auditee user IDs assigned at least one control in the engagement. */
    List<Long> findDistinctAssignedAuditeeIdsByEngagementId(Long engagementId);

    /**
     * Controls approaching/past their evidence due date with unsubmitted evidence.
     * Used by AuditEvidenceReminderScheduler (daily 08:00); deadline = today+3d.
     */
    List<AuditControlInstance> findDueForEvidenceReminder(LocalDate deadline);

    /**
     * All control instances tenant-wide carrying a controlTagSnapshot.
     * Returns [{id, assignedAuditorId}] maps — same shape as the former
     * JPQL "SELECT new map(...)" — for EvidenceReuseEngine.propagate().
     */
    List<Map<String, Object>> findByTenantIdAndControlTagSnapshot(Long tenantId, String tag);

    /** Count controls with a non-null result other than NOT_TESTED. */
    long countTestedByEngagement(Long engagementId);

    /** [testResult, count] rows grouped by result for an engagement. */
    List<Object[]> countByResultForEngagement(Long engagementId);

    /** Count controls flagged findingLinked = true. */
    long countFindingsLinkedByEngagement(Long engagementId);

    /** Count evaluated controls (testResult != NOT_TESTED) — syncEngagementScore(). */
    long countTestedByEngagementId(Long engagementId);
}
