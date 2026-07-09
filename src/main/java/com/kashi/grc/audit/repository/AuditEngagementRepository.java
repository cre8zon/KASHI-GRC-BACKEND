package com.kashi.grc.audit.repository;
import com.kashi.grc.audit.domain.AuditEngagement;
import org.springframework.data.jpa.repository.*;
import org.springframework.stereotype.Repository;
import java.util.List; import java.util.Optional;

@Repository
public interface AuditEngagementRepository extends JpaRepository<AuditEngagement, Long>,
        JpaSpecificationExecutor<AuditEngagement>,
        AuditEngagementRepositoryCustom {
    List<AuditEngagement> findByProjectId(Long projectId);

    List<AuditEngagement> findByProjectInstanceId(Long projectInstanceId);
    boolean existsByTenantIdAndNameAndTemplateIdAndCreatedAtAfter(Long tenantId, String name, Long templateId, java.time.LocalDateTime after);
    List<AuditEngagement> findByTenantId(Long tenantId);
    Optional<AuditEngagement> findByTenantIdAndWorkflowInstanceId(Long tenantId, Long workflowInstanceId);

    // nextEngagementRefSequence(tenantId) is declared in
    // AuditEngagementRepositoryCustom and implemented via the JPA Criteria API
    // in AuditEngagementRepositoryImpl. It replaced the former native query:
    //   SELECT COUNT(*) + 1 FROM audit_engagements
    //   WHERE tenant_id = :tenantId AND YEAR(created_at) = YEAR(NOW())
}