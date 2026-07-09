package com.kashi.grc.audit.repository;
import com.kashi.grc.audit.domain.AuditPolicyControlMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Projection lives in AuditPolicyControlMappingRepositoryCustom (Criteria API). */
@Repository
public interface AuditPolicyControlMappingRepository
        extends JpaRepository<AuditPolicyControlMapping, Long>,
        AuditPolicyControlMappingRepositoryCustom {

    List<AuditPolicyControlMapping> findByPolicyId(Long policyId);

    List<AuditPolicyControlMapping> findByControlId(Long controlId);

    Optional<AuditPolicyControlMapping> findByPolicyIdAndControlId(Long policyId, Long controlId);

    void deleteByPolicyId(Long policyId);

    void deleteByControlId(Long controlId);
}
