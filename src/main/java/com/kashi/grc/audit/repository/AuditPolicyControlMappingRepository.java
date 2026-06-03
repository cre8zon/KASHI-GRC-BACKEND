package com.kashi.grc.audit.repository;
import com.kashi.grc.audit.domain.AuditPolicyControlMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AuditPolicyControlMappingRepository
        extends JpaRepository<AuditPolicyControlMapping, Long> {

    List<AuditPolicyControlMapping> findByPolicyId(Long policyId);

    List<AuditPolicyControlMapping> findByControlId(Long controlId);

    Optional<AuditPolicyControlMapping> findByPolicyIdAndControlId(Long policyId, Long controlId);

    void deleteByPolicyId(Long policyId);

    void deleteByControlId(Long controlId);

    /** All control IDs mapped to a policy — for bulk snapshot at instantiation */
    @Query("SELECT m.controlId FROM AuditPolicyControlMapping m WHERE m.policyId = :policyId")
    List<Long> findControlIdsByPolicyId(@Param("policyId") Long policyId);
}
