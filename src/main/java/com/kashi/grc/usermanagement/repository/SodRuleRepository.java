package com.kashi.grc.usermanagement.repository;

import com.kashi.grc.usermanagement.domain.SodRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;

public interface SodRuleRepository extends JpaRepository<SodRule, Long> {

    List<SodRule> findByTenantId(Long tenantId);

    @Query("SELECT s FROM SodRule s WHERE s.tenantId = :tenantId AND (:severity IS NULL OR s.severity = :severity)")
    List<SodRule> findByTenantIdAndSeverity(@Param("tenantId") Long tenantId, @Param("severity") String severity);

    @Query("""
        SELECT s FROM SodRule s
        WHERE s.tenantId = :tenantId
        AND ((s.conflictingRole1Id = :role1 AND s.conflictingRole2Id = :role2)
          OR (s.conflictingRole1Id = :role2 AND s.conflictingRole2Id = :role1))
        """)
    List<SodRule> findConflictBetween(
        @Param("tenantId") Long tenantId,
        @Param("role1") Long role1,
        @Param("role2") Long role2);

    /** Find rules where proposed role conflicts with any of the user's current roles */
    @Query("""
        SELECT s FROM SodRule s
        WHERE (s.conflictingRole1Id = :proposedRoleId AND s.conflictingRole2Id IN :currentRoleIds)
           OR (s.conflictingRole2Id = :proposedRoleId AND s.conflictingRole1Id IN :currentRoleIds)
        """)
    List<SodRule> findViolationsForProposedRole(
        @Param("proposedRoleId") Long proposedRoleId,
        @Param("currentRoleIds") Set<Long> currentRoleIds);

    long countByTenantId(Long tenantId);
}
