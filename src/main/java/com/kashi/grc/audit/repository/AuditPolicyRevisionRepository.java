package com.kashi.grc.audit.repository;

import com.kashi.grc.audit.domain.AuditPolicyRevision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AuditPolicyRevisionRepository extends JpaRepository<AuditPolicyRevision, Long> {

    List<AuditPolicyRevision> findByPolicyIdOrderByRevisionNoDesc(Long policyId);

    Optional<AuditPolicyRevision> findTopByPolicyIdOrderByRevisionNoDesc(Long policyId);

    Optional<AuditPolicyRevision> findByIdAndPolicyId(Long id, Long policyId);
}
