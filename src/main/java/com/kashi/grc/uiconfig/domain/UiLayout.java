package com.kashi.grc.uiconfig.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * Table/list column definitions per screen.
 * Add, remove, or reorder columns without code changes.
 * columnsJson: [{key, label, sortable, width, type, componentKey, monoFont, isPrimary}]
 * filtersJson: [{key, label, type, componentKey, placeholder}]
 * roleAccess:  {"ORGANIZATION": true, "VENDOR": false}
 * tabsJson:    [{key, label}] — configurable tab list for DETAIL screens
 *              falls back to hardcoded defaults when null/empty
 */
@Entity
@Table(name = "ui_layouts")
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class UiLayout extends BaseEntity {

    @Column(name = "layout_key", unique = true, nullable = false, length = 100)
    private String layoutKey;

    @Column(name = "screen", length = 100)
    private String screen;

    @Column(name = "title", length = 255)
    private String title;

    /** JSON: [{key, label, sortable, width, type, componentKey, hidden, monoFont, isPrimary}] */
    @Column(name = "columns_json", nullable = false, columnDefinition = "JSON")
    private String columnsJson;

    /** JSON: [{key, label, type, componentKey, placeholder}] */
    @Column(name = "filters_json", columnDefinition = "JSON")
    private String filtersJson;

    /** JSON: {"ORGANIZATION": true, "VENDOR": false, "SYSTEM": true} */
    @Column(name = "role_access_json", columnDefinition = "JSON")
    private String roleAccessJson;

    /** Whether this layout supports row-level selection */
    @Column(name = "selectable")
    @Builder.Default
    private boolean selectable = false;

    /** Whether this layout supports drag-to-reorder rows */
    @Column(name = "reorderable")
    @Builder.Default
    private boolean reorderable = false;

    /**
     * How the detail screen renders: FULL_PAGE (default), DRAWER, SIDE_PANEL.
     * Stored as a plain string — no enum needed; renderer reads it directly.
     * Only meaningful for screens of type DETAIL.
     */
    @Column(name = "layout_mode", length = 20)
    @Builder.Default
    private String layoutMode = "FULL_PAGE";

    /**
     * JSON array of tab definitions for DETAIL screens.
     * Format: [{key: "overview", label: "Overview"}, {key: "tests", label: "Tests"}, ...]
     * When null or empty, the frontend falls back to its hardcoded default tab list.
     * Only meaningful for screens of type DETAIL.
     *
     * ddl-auto=update will add this column to the existing ui_layouts table automatically.
     */
    @Column(name = "tabs_json", columnDefinition = "JSON")
    private String tabsJson;

    @Column(name = "tenant_id")
    private Long tenantId;
}