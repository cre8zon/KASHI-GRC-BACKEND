package com.kashi.grc.audit.repository;
import com.kashi.grc.audit.domain.AuditPolicyInstanceControlMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AuditPolicyInstanceControlMappingRepository
        extends JpaRepository<AuditPolicyInstanceControlMapping, Long> {

    List<AuditPolicyInstanceControlMapping> findByControlInstanceId(Long controlInstanceId);

    List<AuditPolicyInstanceControlMapping> findByPolicyInstanceId(Long policyInstanceId);

    List<AuditPolicyInstanceControlMapping> findByEngagementId(Long engagementId);

    Optional<AuditPolicyInstanceControlMapping> findByPolicyInstanceIdAndControlInstanceId(
            Long policyInstanceId, Long controlInstanceId);

    /** All control instance IDs covered by a policy instance */
    @Query("SELECT m.controlInstanceId FROM AuditPolicyInstanceControlMapping m " +
            "WHERE m.policyInstanceId = :policyInstanceId")
    List<Long> findControlInstanceIdsByPolicyInstanceId(@Param("policyInstanceId") Long policyInstanceId);

    /** All policy instance IDs covering a control instance */
    @Query("SELECT m.policyInstanceId FROM AuditPolicyInstanceControlMapping m " +
            "WHERE m.controlInstanceId = :controlInstanceId")
    List<Long> findPolicyInstanceIdsByControlInstanceId(@Param("controlInstanceId") Long controlInstanceId);

    void deleteByEngagementId(Long engagementId);
}
