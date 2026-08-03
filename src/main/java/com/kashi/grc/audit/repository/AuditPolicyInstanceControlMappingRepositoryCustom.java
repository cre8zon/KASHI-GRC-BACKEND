package com.kashi.grc.audit.repository;

import java.util.List;

/** Criteria API fragment for AuditPolicyInstanceControlMappingRepository. */
public interface AuditPolicyInstanceControlMappingRepositoryCustom {

    /** All control instance IDs covered by a policy instance. */
    List<Long> findControlInstanceIdsByPolicyInstanceId(Long policyInstanceId);

    /** All policy instance IDs covering a control instance. */
    List<Long> findPolicyInstanceIdsByControlInstanceId(Long controlInstanceId);

    /** Batch: control-instance IDs in an engagement that have >=1 policy mapped. */
    java.util.Set<Long> controlIdsWithPolicyForEngagement(Long engagementId);
}