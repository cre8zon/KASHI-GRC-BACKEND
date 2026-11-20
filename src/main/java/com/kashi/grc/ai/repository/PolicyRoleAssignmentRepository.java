package com.kashi.grc.ai.repository;

import com.kashi.grc.ai.domain.PolicyRoleAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PolicyRoleAssignmentRepository extends JpaRepository<PolicyRoleAssignment, Long> {

    /** Every assignment, current and past — the register and its history. */
    List<PolicyRoleAssignment> findByTenantIdOrderByRoleTitleAscEffectiveFromDesc(Long tenantId);

    /** Current holders only. */
    List<PolicyRoleAssignment> findByTenantIdAndEffectiveToIsNull(Long tenantId);

    Optional<PolicyRoleAssignment> findFirstByTenantIdAndRoleKeyAndEffectiveToIsNull(Long tenantId, String roleKey);

    Optional<PolicyRoleAssignment> findFirstByTenantIdAndRoleKeyOrderByEffectiveFromDesc(Long tenantId, String roleKey);
}
