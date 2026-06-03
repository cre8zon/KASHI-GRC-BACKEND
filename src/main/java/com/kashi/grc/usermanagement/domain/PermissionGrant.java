package com.kashi.grc.usermanagement.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * Fine-grained RBAC: maps a Role to a specific Permission with an explicit granted flag.
 *
 * This replaces the coarse role_permissions join table with a proper grant model:
 *   - granted = true  → role has this permission
 *   - granted = false → role is explicitly denied this permission (useful for "almost CISO" roles)
 *
 * Resolution order:
 *   1. PermissionGrant (role level)       ← base
 *   2. UserPermissionOverride (user level) ← wins over role
 *   3. Step UI override (step context)    ← can only restrict, never expand
 *
 * Platform Admin manages these via /admin/rbac/permission-grants
 *
 * NEW: permissionCode (denormalized) — stored at grant time for hot-path reads.
 *   WorkflowAccessService.resolvePermissions() calls findGrantsForUserRoles() which
 *   returns (permissionCode, granted) pairs via a JOIN query — no additional lookup needed.
 *   RbacAdminController.listGrants() also uses this for display without a join.
 *
 * MIGRATION (run once):
 *   ALTER TABLE permission_grants
 *     ADD COLUMN permission_code VARCHAR(100) NULL;
 *   -- Backfill existing rows:
 *   UPDATE permission_grants pg
 *     JOIN permissions p ON p.id = pg.permission_id
 *     SET pg.permission_code = p.code
 *     WHERE pg.permission_code IS NULL;
 */
@Entity
@Table(
        name = "permission_grants",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_role_permission",
                columnNames = {"role_id", "permission_id"}
        )
)
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class PermissionGrant extends BaseEntity {

    @Column(name = "role_id", nullable = false)
    private Long roleId;

    @Column(name = "permission_id", nullable = false)
    private Long permissionId;

    /**
     * Denormalized permission code — stored at grant time to avoid a JOIN in
     * WorkflowAccessService.resolvePermissions() hot path.
     * e.g. "risk.create", "audit.approve"
     * Populated by RbacAdminController.upsertGrant() which resolves the code
     * from the Permission entity before saving.
     */
    @Column(name = "permission_code", length = 100)
    private String permissionCode;

    /**
     * true  = role has this permission
     * false = role is explicitly denied (overrides inherited grants)
     */
    @Column(name = "granted", nullable = false)
    @Builder.Default
    private boolean granted = true;

    /** Who granted/denied this — for audit trail */
    @Column(name = "granted_by")
    private Long grantedBy;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}