package com.kashi.grc.uiconfig.domain;

import com.kashi.grc.common.domain.GlobalOrTenantEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * Defines every sidebar/nav item in the application.
 * tenantId = null means global (shown to all tenants).
 * Role/side/level columns drive visibility per user type.
 * Insert a row to add a new menu item — zero code deploy.
 */
@Entity
@Table(name = "ui_navigation")
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class UiNavigation extends GlobalOrTenantEntity {

    /** Unique key referenced by frontend route config. e.g. 'vendor_list' */
    @Column(name = "nav_key", unique = true, nullable = false, length = 100)
    private String navKey;

    @Column(name = "label", nullable = false, length = 255)
    private String label;

    /** Lucide icon name. e.g. 'ShieldCheck', 'Users', 'BarChart2' */
    @Column(name = "icon", length = 100)
    private String icon;

    /** Frontend route. e.g. '/tprm/vendors' */
    @Column(name = "route", nullable = false, length = 255)
    private String route;

    /** NULL = top-level item. Set to parent navKey for sub-items. */
    @Column(name = "parent_key", length = 100)
    private String parentKey;

    @Column(name = "sort_order")
    private Integer sortOrder;

    /** e.g. 'TPRM', 'USER_MGMT', 'AUDIT', 'WORKFLOW', 'SETTINGS' */
    @Column(name = "module", length = 100)
    private String module;

    /**
     * Which role sides can see this item.
     * Stored as comma-separated string: 'ORGANIZATION,SYSTEM'
     * NULL = all sides can see it.
     */
    @Column(name = "allowed_sides", length = 255)
    private String allowedSides;

    /**
     * Minimum role level required. NULL = any level.
     * e.g. 'L1' means only L1 and above (L1 is highest in your schema).
     */
    @Column(name = "min_level", length = 10)
    private String minLevel;

    /** Specific permission code required. NULL = no permission check. */
    @Column(name = "required_permission", length = 255)
    private String requiredPermission;

    @Column(name = "is_active")
    @Builder.Default
    private boolean isActive = true;

    /** Badge count endpoint — frontend polls this to show notification dot */
    @Column(name = "badge_count_endpoint", length = 255)
    private String badgeCountEndpoint;
}
