package com.kashi.grc.audit.repository;
import com.kashi.grc.audit.domain.AuditTestInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuditTestInstanceRepository extends JpaRepository<AuditTestInstance, Long> {

    List<AuditTestInstance> findByEngagementIdOrderByTestNameSnapshotAsc(Long engagementId);

    List<AuditTestInstance> findByEngagementIdAndTestResult(Long engagementId,
                                                            AuditTestInstance.TestResult result);

    List<AuditTestInstance> findByEngagementIdAndControlTagSnapshot(Long engagementId,
                                                                    String controlTagSnapshot);

    /** All test instances for a specific original test across all engagements */
    List<AuditTestInstance> findByOriginalTestId(Long originalTestId);

    @Query("SELECT t FROM AuditTestInstance t WHERE t.engagementId = :engId " +
            "AND t.tenantId = :tenantId ORDER BY t.testNameSnapshot ASC")
    List<AuditTestInstance> findByEngagementIdAndTenantId(@Param("engId") Long engagementId,
                                                          @Param("tenantId") Long tenantId);

    List<AuditTestInstance> findByTenantIdOrderByTestNameSnapshotAsc(Long tenantId);
}
