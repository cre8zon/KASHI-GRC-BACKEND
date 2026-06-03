package com.kashi.grc.audit.repository;
import com.kashi.grc.audit.domain.AuditPolicyInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuditPolicyInstanceRepository extends JpaRepository<AuditPolicyInstance, Long> {

    List<AuditPolicyInstance> findByEngagementIdOrderByTitleSnapshotAsc(Long engagementId);

    List<AuditPolicyInstance> findByEngagementIdAndReviewResult(Long engagementId,
                                                                AuditPolicyInstance.ReviewResult reviewResult);

    List<AuditPolicyInstance> findByOriginalPolicyId(Long originalPolicyId);

    @Query("SELECT p FROM AuditPolicyInstance p WHERE p.engagementId = :engId " +
            "AND p.tenantId = :tenantId ORDER BY p.titleSnapshot ASC")
    List<AuditPolicyInstance> findByEngagementIdAndTenantId(@Param("engId") Long engagementId,
                                                            @Param("tenantId") Long tenantId);

    List<AuditPolicyInstance> findByTenantIdOrderByTitleSnapshotAsc(Long tenantId);
}
