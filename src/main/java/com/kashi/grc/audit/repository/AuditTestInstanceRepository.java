package com.kashi.grc.audit.repository;
import com.kashi.grc.audit.domain.AuditTestInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** findByEngagementIdAndTenantId lives in the Custom fragment (Criteria API). */
@Repository
public interface AuditTestInstanceRepository
        extends JpaRepository<AuditTestInstance, Long>, AuditTestInstanceRepositoryCustom {

    List<AuditTestInstance> findByEngagementIdOrderByTestNameSnapshotAsc(Long engagementId);

    List<AuditTestInstance> findByEngagementIdAndTestResult(Long engagementId,
                                                            AuditTestInstance.TestResult result);

    List<AuditTestInstance> findByEngagementIdAndControlTagSnapshot(Long engagementId,
                                                                    String controlTagSnapshot);

    List<AuditTestInstance> findByOriginalTestId(Long originalTestId);

    List<AuditTestInstance> findByTenantIdOrderByTestNameSnapshotAsc(Long tenantId);

    // Indexed tenant+tag query used by AuditTestEvidenceMatcher
    List<AuditTestInstance> findByTenantIdAndControlTagSnapshot(Long tenantId, String controlTagSnapshot);
}
