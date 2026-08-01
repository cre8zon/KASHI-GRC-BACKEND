package com.kashi.grc.audit.repository;

import com.kashi.grc.audit.domain.AuditTestInstance;
import java.util.List;

/** Criteria API fragment for AuditTestInstanceRepository. */
public interface AuditTestInstanceRepositoryCustom {

    /** Test instances for an engagement + tenant, ORDER BY testNameSnapshot. */
    List<AuditTestInstance> findByEngagementIdAndTenantId(Long engagementId, Long tenantId);

    /**
     * Phase 3: MANUAL test instances for a tenant whose frozen expanded tag set
     * contains the evidence tag, OR (legacy) whose control_tag_snapshot equals it.
     * AUTOMATED tests are excluded here — they route by checkKey, never by tag.
     */
    List<AuditTestInstance> findManualByTenantAndExpandedTag(Long tenantId, String tag);
}