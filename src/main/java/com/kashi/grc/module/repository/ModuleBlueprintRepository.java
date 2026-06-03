package com.kashi.grc.module.repository;

import com.kashi.grc.module.domain.ModuleBlueprint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for ModuleBlueprint.
 *
 * Lookup priority in ModuleBlueprintController.getByEntityType():
 *   1. Tenant-specific blueprint (tenantId = current tenant) — customised per org
 *   2. Global blueprint (tenantId IS NULL) — platform-wide default
 *
 * This two-level lookup allows platform defaults to be overridden per tenant
 * without forking the entire blueprint.
 */
@Repository
public interface ModuleBlueprintRepository extends JpaRepository<ModuleBlueprint, Long> {

    /**
     * Tenant-specific blueprint for an entityType.
     * Case-insensitive so callers can pass "risk" or "RISK" interchangeably.
     */
    Optional<ModuleBlueprint> findByEntityTypeIgnoreCaseAndTenantId(
            String entityType, Long tenantId);

    /**
     * Global (platform-wide) blueprint for an entityType.
     * tenantId IS NULL marks a global blueprint.
     */
    Optional<ModuleBlueprint> findByEntityTypeIgnoreCaseAndTenantIdIsNull(
            String entityType);

    /**
     * All blueprints visible to a tenant: tenant-specific + global.
     * Used for listing in admin UI — returns both owned and inherited blueprints.
     */
    List<ModuleBlueprint> findByTenantIdOrTenantIdIsNull(Long tenantId);

    /**
     * Only active blueprints for a tenant — used by Universal Module Page nav rendering.
     */
    List<ModuleBlueprint> findByTenantIdAndIsActiveTrue(Long tenantId);

    /**
     * Check for duplicate entityType within a tenant before creating.
     */
    boolean existsByEntityTypeIgnoreCaseAndTenantId(String entityType, Long tenantId);
}