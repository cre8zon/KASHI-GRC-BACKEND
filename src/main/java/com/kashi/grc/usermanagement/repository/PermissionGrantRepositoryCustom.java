package com.kashi.grc.usermanagement.repository;

import com.kashi.grc.usermanagement.domain.PermissionGrant;

import java.util.List;

/** Criteria API fragment for PermissionGrantRepository. */
public interface PermissionGrantRepositoryCustom {

    /** All grants for a role (former JPQL was a plain roleId filter). */
    List<PermissionGrant> findByRoleIdWithPermission(Long roleId);

    /** Granted=true grants across a set of roles. */
    List<PermissionGrant> findActiveGrantsByRoleIds(List<Long> roleIds);

    /**
     * Hot-path RBAC read: [permissionCode, granted] pairs for a user's roles.
     * permissionCode is the denormalized column; where it is NULL the code is
     * resolved from the Permission row (former LEFT JOIN + COALESCE — here a
     * scalar subquery, equivalent since permissionId maps to at most one row).
     */
    List<Object[]> findGrantsForUserRoles(List<Long> roleIds);

    /** Bulk delete grants for a permission (CriteriaDelete). Caller must be @Transactional. */
    void deleteByPermissionId(Long permissionId);
}
