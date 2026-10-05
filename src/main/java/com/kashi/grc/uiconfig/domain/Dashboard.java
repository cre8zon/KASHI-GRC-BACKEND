package com.kashi.grc.uiconfig.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * A dashboard: a named, permissioned collection of widgets.
 *
 * ── WHY THIS EXISTS ───────────────────────────────────────────────────────
 * dashboard_widgets had no grouping column, so every widget rendered on one
 * global page. "A dashboard per module" could not be expressed, and neither
 * could a dashboard a user builds for themselves.
 *
 * ── EVERYTHING IS A ROW, ON PURPOSE ───────────────────────────────────────
 * The point of this table is that opening dashboard authoring to users later
 * should be a UI over these columns, not another schema change. So which
 * dashboard exists, who may see it, which widgets it holds, where their data
 * comes from, when a number turns red and where clicking leads are all data.
 * Nothing about a dashboard should require a deploy.
 */
@Entity
@Table(
        name = "dashboards",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_dashboard_key_tenant",
                        columnNames = {"dashboard_key", "tenant_id"})
        },
        indexes = {
                @Index(name = "idx_dash_entity", columnList = "entity_type,is_active"),
                @Index(name = "idx_dash_scope",  columnList = "scope,is_active"),
                @Index(name = "idx_dash_owner",  columnList = "owner_user_id"),
        }
)
@Getter @Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Dashboard extends BaseEntity {

    @Column(name = "dashboard_key", nullable = false, length = 100)
    private String dashboardKey;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(length = 50)
    private String icon;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Scope scope = Scope.MODULE;

    /** Matches module_blueprints.entity_type, so a module finds its own without a lookup. */
    @Column(name = "entity_type", length = 50)
    private String entityType;

    /**
     * Set only for PERSONAL. A user's own dashboard is invisible to everyone
     * else regardless of the permission columns — ownership is checked before
     * them, not alongside them.
     */
    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Column(name = "is_default", nullable = false)
    @Builder.Default
    private boolean isDefault = false;

    @Column(name = "allowed_sides_json", length = 255)
    private String allowedSidesJson;

    @Column(name = "required_permission", length = 100)
    private String requiredPermission;

    /**
     * The SAME shape as ui_layouts.role_access_json, resolved by the same
     * isItemAllowed helper with scope "widgets":
     *
     *   {"ORGANIZATION": true, "33": {"widgets": {"per_unscreened": false}}}
     *
     * Reused rather than reinvented: a second role model for dashboards would
     * drift from the first within a month, and an administrator would have to
     * learn two ways of saying the same thing.
     */
    @Column(name = "role_access_json", columnDefinition = "TEXT")
    private String roleAccessJson;

    @Column(name = "grid_cols", nullable = false)
    @Builder.Default
    private Integer gridCols = 12;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;

    /** NULL = platform-provided, visible to every tenant. Same convention as ui_layouts. */
    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "created_by")
    private Long createdBy;

    public enum Scope {
        /** The landing dashboard. */
        GLOBAL,
        /** A tab on one module's list screen. */
        MODULE,
        /** Built by a user, visible only to them. */
        PERSONAL
    }

    @Transient
    public boolean isPlatformProvided() { return tenantId == null; }

    /**
     * Can this caller see it at all — before permissions are consulted.
     *
     * Ownership is a harder gate than permission: somebody's personal
     * dashboard is theirs, and a colleague holding every permission in the
     * system still has no business reading it.
     */
    @Transient
    public boolean isVisibleTo(Long userId, Long callerTenantId) {
        if (scope == Scope.PERSONAL) return ownerUserId != null && ownerUserId.equals(userId);
        return tenantId == null || tenantId.equals(callerTenantId);
    }
}