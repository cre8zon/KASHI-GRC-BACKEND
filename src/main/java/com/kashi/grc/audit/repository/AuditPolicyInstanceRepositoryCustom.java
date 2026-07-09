package com.kashi.grc.audit.repository;

import com.kashi.grc.audit.domain.AuditPolicyInstance;
import java.util.List;

/** Criteria API fragment for AuditPolicyInstanceRepository. */
public interface AuditPolicyInstanceRepositoryCustom {

    /** Policy instances for an engagement + tenant, ORDER BY titleSnapshot. */
    List<AuditPolicyInstance> findByEngagementIdAndTenantId(Long engagementId, Long tenantId);
}
