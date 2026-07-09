package com.kashi.grc.audit.repository;

import java.util.List;

/** Criteria API fragment for AuditPolicyControlMappingRepository. */
public interface AuditPolicyControlMappingRepositoryCustom {

    /** All control IDs mapped to a policy — bulk snapshot at instantiation. */
    List<Long> findControlIdsByPolicyId(Long policyId);
}
