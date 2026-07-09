package com.kashi.grc.audit.repository;

import com.kashi.grc.audit.domain.AuditTestInstance;
import java.util.List;

/** Criteria API fragment for AuditTestInstanceRepository. */
public interface AuditTestInstanceRepositoryCustom {

    /** Test instances for an engagement + tenant, ORDER BY testNameSnapshot. */
    List<AuditTestInstance> findByEngagementIdAndTenantId(Long engagementId, Long tenantId);
}
