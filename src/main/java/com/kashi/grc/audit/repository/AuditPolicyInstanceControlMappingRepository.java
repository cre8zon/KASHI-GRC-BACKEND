package com.kashi.grc.audit.repository;
import com.kashi.grc.audit.domain.AuditPolicyInstanceControlMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Projections live in AuditPolicyInstanceControlMappingRepositoryCustom (Criteria API). */
@Repository
public interface AuditPolicyInstanceControlMappingRepository
        extends JpaRepository<AuditPolicyInstanceControlMapping, Long>,
        AuditPolicyInstanceControlMappingRepositoryCustom {

    List<AuditPolicyInstanceControlMapping> findByControlInstanceId(Long controlInstanceId);

    List<AuditPolicyInstanceControlMapping> findByPolicyInstanceId(Long policyInstanceId);

    List<AuditPolicyInstanceControlMapping> findByEngagementId(Long engagementId);

    Optional<AuditPolicyInstanceControlMapping> findByPolicyInstanceIdAndControlInstanceId(
            Long policyInstanceId, Long controlInstanceId);

    void deleteByEngagementId(Long engagementId);
}
