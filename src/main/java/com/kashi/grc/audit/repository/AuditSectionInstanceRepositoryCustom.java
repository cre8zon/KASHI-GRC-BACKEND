package com.kashi.grc.audit.repository;

import com.kashi.grc.audit.domain.AuditSectionInstance;
import java.util.List;

/** Criteria API fragment for AuditSectionInstanceRepository. */
public interface AuditSectionInstanceRepositoryCustom {

    /** All descendants of a section instance at any depth (path LIKE prefix%), excluding itself. */
    List<AuditSectionInstance> findAllDescendants(Long instanceId, String pathPrefix);

    /** Distinct auditor user IDs assigned at least one section in the engagement. */
    List<Long> findDistinctAssignedAuditorIdsByEngagementId(Long engagementId);

    /** Distinct auditee user IDs assigned at least one section in the engagement. */
    List<Long> findDistinctAssignedAuditeeIdsByEngagementId(Long engagementId);

    /** Count section instances submitted (submittedAt IS NOT NULL). */
    long countSubmittedByEngagement(Long engagementId);

    /** Count all section instances in the engagement. */
    long countTotalByEngagement(Long engagementId);
}
