package com.kashi.grc.usermanagement.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * Individual permission. Maps to the `permissions` table.
 * Example codes: risk.view, risk.create, risk.approve, vendor.view.
 *
 * NEW: module (String) — human-readable module grouping key for the admin UI.
 *   e.g. "RISK", "AUDIT", "VENDOR", "POLICY"
 *   Used by RbacAdminController to group permissions by module in the matrix view.
 *   Separate from moduleId (Long FK to system modules table, now nullable).
 *
 * CHANGED: moduleId is now nullable — new permissions created via /admin/rbac/permissions
 *   do not require a FK to the system modules table; they use the `module` string instead.
 *
 * MIGRATION (run once):
 *   ALTER TABLE permissions
 *     MODIFY COLUMN module_id BIGINT NULL,
 *     ADD COLUMN module VARCHAR(100) NULL;
 */
@Entity
@Table(name = "permissions")
@Getter
@Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Permission extends BaseEntity {

    /**
     * FK to system modules table. Nullable — new RBAC-managed permissions
     * use the `module` string field instead of this FK.
     */
    @Column(name = "module_id")
    private Long moduleId;

    /**
     * Human-readable module grouping key.
     * e.g. "RISK", "AUDIT", "VENDOR", "POLICY", "WORKFLOW"
     * Used by the admin permission matrix to group permissions by module.
     * Null = uncategorized (legacy permissions without a module assignment).
     */
    @Column(name = "module", length = 100)
    private String module;

    /** Dot-notation permission code: "risk.create", "audit.approve" */
    @Column(name = "code", nullable = false, length = 100)
    private String code;

    /** Human-readable name: "Create Risk", "Approve Audit" */
    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "resource_type", length = 100)
    private String resourceType;
}