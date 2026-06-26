package com.kashi.grc.audit.repository;
import com.kashi.grc.audit.domain.AuditEngagement;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List; import java.util.Optional;

@Repository
public interface AuditEngagementRepository extends JpaRepository<AuditEngagement, Long>,
        JpaSpecificationExecutor<AuditEngagement> {
    List<AuditEngagement> findByProjectId(Long projectId);

    List<AuditEngagement> findByProjectInstanceId(Long projectInstanceId);
    boolean existsByTenantIdAndNameAndTemplateIdAndCreatedAtAfter(Long tenantId, String name, Long templateId, java.time.LocalDateTime after);
    List<AuditEngagement> findByTenantId(Long tenantId);
    Optional<AuditEngagement> findByTenantIdAndWorkflowInstanceId(Long tenantId, Long workflowInstanceId);

    @Query("SELECT COUNT(e) FROM AuditEngagement e WHERE e.projectId = :projectId AND e.status != 'CANCELLED'")
    long countActiveByProjectId(@Param("projectId") Long projectId);

    @Query("SELECT e.status, COUNT(e) FROM AuditEngagement e WHERE e.tenantId = :tenantId GROUP BY e.status")
    List<Object[]> countByStatusForTenant(@Param("tenantId") Long tenantId);

    @Query(value = "SELECT COUNT(*) + 1 FROM audit_engagements WHERE tenant_id = :tenantId AND YEAR(created_at) = YEAR(NOW())", nativeQuery = true)
    long nextEngagementRefSequence(@Param("tenantId") Long tenantId);
}