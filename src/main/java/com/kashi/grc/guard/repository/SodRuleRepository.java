package com.kashi.grc.guard.repository;

import com.kashi.grc.guard.domain.SodRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;

/**
 * Unified repository for all SoD rules — both ROLE_PAIR and PERMISSION_PAIR.
 *
 * tenantId semantics (from GlobalOrTenantEntity):
 *   NULL     = global platform rule — applies to ALL tenants
 *   non-null = tenant-specific rule — applies only to that tenant
 *
 * Every query that enforces SoD MUST include (tenantId IS NULL OR tenantId = :tenantId)
 * so global rules are always evaluated alongside tenant-specific ones.
 *
 * Callers:
 *   RoleServiceImpl       → ROLE_PAIR methods
 *   WorkflowAccessService → PERMISSION_PAIR methods
 *   RbacAdminController   → admin list/count methods
 */
@Repository
public interface SodRuleRepository extends JpaRepository<SodRule, Long> {

    // ── Summary count ────────────────────────────────────────────────────────

    /**
     * Count active SoD rules visible to a tenant: global (tenantId IS NULL) + tenant-specific.
     * Used by RbacAdminController.getSummary() for the tab badge.
     */
    @Query("""
        SELECT COUNT(s) FROM SodRule s
        WHERE s.isActive = true
          AND (s.tenantId IS NULL OR s.tenantId = :tenantId)
        """)
    long countActiveForTenant(@Param("tenantId") Long tenantId);

    // ── ROLE_PAIR queries — used by RoleServiceImpl ──────────────────────────

    /**
     * Find active ROLE_PAIR conflict between two specific roles.
     * Includes global rules (tenantId IS NULL) AND tenant-specific rules.
     * Bidirectional: (role1, role2) OR (role2, role1) both match.
     * Called by RoleServiceImpl.assignRoleToUser() before assigning each role.
     */
    @Query("""
        SELECT s FROM SodRule s
        WHERE s.ruleType = 'ROLE_PAIR'
          AND s.isActive = true
          AND (s.tenantId IS NULL OR s.tenantId = :tenantId)
          AND ((s.conflictingRole1Id = :role1 AND s.conflictingRole2Id = :role2)
            OR (s.conflictingRole1Id = :role2 AND s.conflictingRole2Id = :role1))
        """)
    List<SodRule> findConflictBetween(
            @Param("tenantId") Long tenantId,
            @Param("role1") Long role1,
            @Param("role2") Long role2);

    /**
     * Find ROLE_PAIR rules where a proposed role conflicts with any current role.
     * Includes global rules. Used for pre-check before role assignment UI confirms.
     */
    @Query("""
        SELECT s FROM SodRule s
        WHERE s.ruleType = 'ROLE_PAIR'
          AND s.isActive = true
          AND (s.tenantId IS NULL OR s.tenantId = :tenantId)
          AND ((s.conflictingRole1Id = :proposedRoleId AND s.conflictingRole2Id IN :currentRoleIds)
            OR (s.conflictingRole2Id = :proposedRoleId AND s.conflictingRole1Id IN :currentRoleIds))
        """)
    List<SodRule> findViolationsForProposedRole(
            @Param("tenantId") Long tenantId,
            @Param("proposedRoleId") Long proposedRoleId,
            @Param("currentRoleIds") Set<Long> currentRoleIds);

    /**
     * All ROLE_PAIR rules visible to a tenant (global + tenant-specific).
     * Used by SodCheckResponse building and admin listing.
     */
    @Query("""
        SELECT s FROM SodRule s
        WHERE s.ruleType = 'ROLE_PAIR'
          AND (s.tenantId IS NULL OR s.tenantId = :tenantId)
        ORDER BY s.ruleName
        """)
    List<SodRule> findByTenantId(@Param("tenantId") Long tenantId);

    /**
     * ROLE_PAIR rules filtered by severity — for SodCheckResponse building.
     * Includes global rules.
     */
    @Query("""
        SELECT s FROM SodRule s
        WHERE s.ruleType = 'ROLE_PAIR'
          AND (s.tenantId IS NULL OR s.tenantId = :tenantId)
          AND (:severity IS NULL OR s.severity = :severity)
        """)
    List<SodRule> findByTenantIdAndSeverity(
            @Param("tenantId") Long tenantId,
            @Param("severity") String severity);

    // ── PERMISSION_PAIR queries — used by WorkflowAccessService ─────────────

    /**
     * All active PERMISSION_PAIR rules for a tenant (global + tenant-specific).
     * Used by WorkflowAccessService to load all applicable SoD constraints.
     */
    @Query("""
        SELECT r FROM SodRule r
        WHERE r.ruleType = 'PERMISSION_PAIR'
          AND r.isActive = true
          AND (r.tenantId IS NULL OR r.tenantId = :tenantId)
        ORDER BY r.ruleName
        """)
    List<SodRule> findActiveByTenantId(@Param("tenantId") Long tenantId);

    /**
     * Active PERMISSION_PAIR rules for a specific entity type.
     * NULL entityTypes = applies to all. Used by WorkflowAccessService.evaluateSod().
     */
    @Query("""
        SELECT r FROM SodRule r
        WHERE r.ruleType = 'PERMISSION_PAIR'
          AND r.isActive = true
          AND (r.entityTypes IS NULL
               OR r.entityTypes LIKE CONCAT('%', :entityType, '%'))
        """)
    List<SodRule> findActiveRulesForEntityType(@Param("entityType") String entityType);

    /**
     * Check if a PERMISSION_PAIR conflict already exists between two permission codes.
     * Used by RbacAdminController to prevent duplicate rules.
     */
    @Query("""
        SELECT COUNT(r) > 0 FROM SodRule r
        WHERE r.ruleType = 'PERMISSION_PAIR'
          AND (r.tenantId IS NULL OR r.tenantId = :tenantId)
          AND ((r.permissionA = :permA AND r.permissionB = :permB)
            OR (r.permissionA = :permB AND r.permissionB = :permA))
        """)
    boolean existsConflictBetween(
            @Param("tenantId") Long tenantId,
            @Param("permA") String permissionA,
            @Param("permB") String permissionB);

    // ── Count helper ─────────────────────────────────────────────────────────

    /** Count rules specific to a tenant (excludes global rules). */
    long countByTenantId(Long tenantId);
}