package com.kashi.grc.audit.repository;
import com.kashi.grc.audit.domain.AuditPolicyInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** findByEngagementIdAndTenantId lives in the Custom fragment (Criteria API). */
@Repository
public interface AuditPolicyInstanceRepository
        extends JpaRepository<AuditPolicyInstance, Long>, AuditPolicyInstanceRepositoryCustom {

    List<AuditPolicyInstance> findByEngagementIdOrderByTitleSnapshotAsc(Long engagementId);

    List<AuditPolicyInstance> findByEngagementIdAndReviewResult(Long engagementId,
                                                                AuditPolicyInstance.ReviewResult reviewResult);

    List<AuditPolicyInstance> findByOriginalPolicyId(Long originalPolicyId);

    /** How many engagements hold an instance of this library policy. Guards
     *  deletion: original_policy_id is an indexed column, NOT a foreign key, so
     *  nothing at the database level stops a delete from stranding them. */
    long countByOriginalPolicyId(Long originalPolicyId);

    List<AuditPolicyInstance> findByTenantIdOrderByTitleSnapshotAsc(Long tenantId);
}