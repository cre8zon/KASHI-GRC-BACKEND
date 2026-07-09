package com.kashi.grc.guard.repository;

import com.kashi.grc.guard.domain.SodRule;

import java.util.List;
import java.util.Set;

/**
 * Criteria API fragment for SodRuleRepository (Impl-suffix convention).
 *
 * tenantId semantics (GlobalOrTenantEntity):
 *   NULL     = global platform rule — applies to ALL tenants
 *   non-null = tenant-specific rule
 * Every method includes (tenantId IS NULL OR tenantId = :tenantId) unless noted.
 *
 * Callers:
 *   RoleServiceImpl       → ROLE_PAIR methods
 *   WorkflowAccessService → PERMISSION_PAIR methods
 *   RbacAdminController   → admin list/count methods
 */
public interface SodRuleRepositoryCustom {

    /** Count active rules visible to a tenant — RbacAdminController.getSummary() tab badge. */
    long countActiveForTenant(Long tenantId);

    /** Active ROLE_PAIR conflict between two roles, bidirectional. */
    List<SodRule> findConflictBetween(Long tenantId, Long role1, Long role2);

    /** ROLE_PAIR rules where a proposed role conflicts with any current role. */
    List<SodRule> findViolationsForProposedRole(Long tenantId, Long proposedRoleId, Set<Long> currentRoleIds);

    /** All ROLE_PAIR rules visible to a tenant. ORDER BY ruleName. */
    List<SodRule> findByTenantId(Long tenantId);

    /** ROLE_PAIR rules, optionally filtered by severity (null = all). */
    List<SodRule> findByTenantIdAndSeverity(Long tenantId, String severity);

    /** All active PERMISSION_PAIR rules for a tenant. ORDER BY ruleName. */
    List<SodRule> findActiveByTenantId(Long tenantId);

    /** Active PERMISSION_PAIR rules for an entity type (NULL entityTypes = applies to all). */
    List<SodRule> findActiveRulesForEntityType(String entityType);

    /** Whether a PERMISSION_PAIR conflict already exists between two permission codes (bidirectional). */
    boolean existsConflictBetween(Long tenantId, String permissionA, String permissionB);
}
