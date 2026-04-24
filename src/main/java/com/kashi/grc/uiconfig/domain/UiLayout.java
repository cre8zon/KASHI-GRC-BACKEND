package com.kashi.grc.uiconfig.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * Table/list column definitions per screen.
 * Add, remove, or reorder columns without code changes.
 * columnsJson: [{key, label, sortable, width, type, componentKey}]
 * filtersJson: [{key, label, type, componentKey}]
 * roleAccess:  {"ORGANIZATION": true, "VENDOR": false}
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

    /** JSON: [{key, label, sortable, width, type, componentKey, hidden}] */
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

    @Column(name = "tenant_id")
    private Long tenantId;
}
