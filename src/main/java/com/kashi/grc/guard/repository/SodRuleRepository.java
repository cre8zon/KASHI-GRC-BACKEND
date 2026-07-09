package com.kashi.grc.guard.repository;

import com.kashi.grc.guard.domain.SodRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Unified repository for all SoD rules — both ROLE_PAIR and PERMISSION_PAIR.
 *
 * tenantId semantics (from GlobalOrTenantEntity):
 *   NULL     = global platform rule — applies to ALL tenants
 *   non-null = tenant-specific rule — applies only to that tenant
 *
 * All query logic lives in SodRuleRepositoryCustom and is implemented via
 * the JPA Criteria API in SodRuleRepositoryImpl (no @Query annotations).
 *
 * Callers:
 *   RoleServiceImpl       → ROLE_PAIR methods
 *   WorkflowAccessService → PERMISSION_PAIR methods
 *   RbacAdminController   → admin list/count methods
 */
@Repository
public interface SodRuleRepository
        extends JpaRepository<SodRule, Long>, SodRuleRepositoryCustom {

    /** Count rules specific to a tenant (excludes global rules). */
    long countByTenantId(Long tenantId);
}
