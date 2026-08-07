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
}