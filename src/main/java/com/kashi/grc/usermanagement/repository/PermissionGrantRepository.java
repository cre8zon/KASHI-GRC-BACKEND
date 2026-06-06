package com.kashi.grc.usermanagement.repository;

import com.kashi.grc.usermanagement.domain.PermissionGrant;
import com.kashi.grc.usermanagement.domain.Permission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PermissionGrantRepository extends JpaRepository<PermissionGrant, Long> {

    /** All grants for a specific role — for role matrix admin view */
    List<PermissionGrant> findByRoleId(Long roleId);

    /**
     * All grants for a role, joined with permission code for display.
     * Returns PermissionGrant enriched — permissionCode must be denormalized
     * on PermissionGrant (add column permission_code VARCHAR(100)) OR
     * use a projection. Using entity + in-memory join for simplicity.
     */
    @Query("SELECT g FROM PermissionGrant g WHERE g.roleId = :roleId")
    List<PermissionGrant> findByRoleIdWithPermission(@Param("roleId") Long roleId);

    /** Find existing grant for role+permission pair — for upsert */
    Optional<PermissionGrant> findByRoleIdAndPermissionId(Long roleId, Long permissionId);

    /** Batch: all grants for a set of roles — used by WorkflowAccessService */
    @Query("SELECT g FROM PermissionGrant g WHERE g.roleId IN :roleIds AND g.granted = true")
    List<PermissionGrant> findActiveGrantsByRoleIds(@Param("roleIds") List<Long> roleIds);

    /**
     * Returns (permissionCode, granted) pairs for all roles in the given set.
     *
     * Used by WorkflowAccessService.resolvePermissions() to merge PermissionGrant rows
     * into the user's effective permission set.
     *
     * Returns Object[] rows: [0] = permission_code (String), [1] = granted (Boolean).
     * Caller iterates: if granted → add to set; if !granted → remove from set.
     *
     * Uses the denormalized permission_code column on PermissionGrant for a single-table
     * query — avoids a JOIN with the permissions table on the hot path.
     */
    /**
     * Returns (permissionCode, granted) for all role grants.
     * Uses denormalized permissionCode when available, falls back to JOIN
     * with permissions table for older rows where permissionCode was not populated.
     */
    @Query("""
        SELECT COALESCE(g.permissionCode, p.code), g.granted
        FROM PermissionGrant g
        LEFT JOIN Permission p ON p.id = g.permissionId
        WHERE g.roleId IN :roleIds
          AND (g.permissionCode IS NOT NULL OR p.code IS NOT NULL)
        """)
    List<Object[]> findGrantsForUserRoles(@Param("roleIds") List<Long> roleIds);

    /** Remove all grants for a permission when it is deleted */
    @Modifying
    @Query("DELETE FROM PermissionGrant g WHERE g.permissionId = :permissionId")
    void deleteByPermissionId(@Param("permissionId") Long permissionId);

    boolean existsByRoleIdAndPermissionId(Long roleId, Long permissionId);

    List<PermissionGrant> findByPermissionId(Long permissionId);
}