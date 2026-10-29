package com.kashi.grc.usermanagement.repository;

import com.kashi.grc.usermanagement.domain.Role;
import com.kashi.grc.usermanagement.domain.RoleSide;

import java.util.List;

/** Criteria API fragment for RoleRepository. */
public interface RoleRepositoryCustom {

    /** Roles visible to a tenant (global + tenant). */
    List<Role> findAllForTenant(Long tenantId);

    /** Same, optionally filtered by side (null side = all sides). */
    List<Role> findAllForTenantBySide(Long tenantId, RoleSide side);

    /**
     * Same, but lets RBAC admin screens see SUSPENDED roles too.
     * Assignment paths must use the 2-arg version, which excludes them.
     */
    List<Role> findAllForTenantBySide(Long tenantId, RoleSide side, boolean includeSuspended);

    /** Number of users holding a role (join through User.roles). */
    long countUsersWithRole(Long roleId);

    /**
     * Active users of one tenant holding each of the given roles: rows of
     * {roleId, userCount}. Roles nobody holds are absent. Used by the workflow
     * catalogue to say whether a tenant can staff every step.
     */
    List<Object[]> countActiveUsersByRoleInTenant(Long tenantId, java.util.Collection<Long> roleIds);

    /**
     * Active roles visible to a tenant that hold any of the given permission
     * codes (through role_permissions): rows of {roleId, roleName, side, code}.
     * SYSTEM roles are left out for a non-system tenant, as everywhere else.
     * Used by the module guide's "who can do what".
     */
    List<Object[]> findRolesHoldingPermissions(Long tenantId, java.util.Collection<String> permissionCodes);
}