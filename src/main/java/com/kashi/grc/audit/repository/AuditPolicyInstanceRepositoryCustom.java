package com.kashi.grc.audit.repository;

import com.kashi.grc.audit.domain.AuditPolicyInstance;
import java.util.List;

/** Criteria API fragment for AuditPolicyInstanceRepository. */
public interface AuditPolicyInstanceRepositoryCustom {

    /** Policy instances for an engagement + tenant, ORDER BY titleSnapshot. */
    List<AuditPolicyInstance> findByEngagementIdAndTenantId(Long engagementId, Long tenantId);

    /**
     * Phase 3: policy instances for a tenant whose frozen expanded tag set
     * contains the evidence tag, OR (legacy) whose control_tags_snapshot does.
     * Replaces the findAll() + in-memory filter — indexed and tenant-scoped.
     */
    List<AuditPolicyInstance> findByTenantAndExpandedTag(Long tenantId, String tag);
}