package com.kashi.grc.audit.repository;

import java.util.List;

/** Criteria API fragment for AuditControlInstanceTestMappingRepository. */
public interface AuditControlInstanceTestMappingRepositoryCustom {

    /** Required test instance IDs for a control — result derivation. */
    List<Long> findRequiredTestInstanceIdsByControlInstanceId(Long controlId);

    /** Control instance IDs affected by a test — bulk re-evaluation. */
    List<Long> findControlInstanceIdsByTestInstanceId(Long testId);

    /** Batch: control instance id → number of tests mapped to it, for one engagement. */
    java.util.Map<Long, Long> countTestsByControlForEngagement(Long engagementId);
}