package com.kashi.grc.uiconfig.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * Each row is one option inside a UiComponent (dropdown item, badge state, etc.).
 * Change a label, add a status, reorder options — zero code deploy.
 */
@Entity
@Table(name = "ui_options")
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class UiOption extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "component_id", nullable = false)
    private UiComponent component;

    /** Stored value sent to/from API. e.g. 'HIGH', 'ACTIVE', 'SUBMITTED' */
    @Column(name = "option_value", nullable = false, length = 100)
    private String optionValue;

    /** Human-readable label shown in UI. e.g. 'High Risk', 'Active' */
    @Column(name = "option_label", nullable = false, length = 255)
    private String optionLabel;

    /**
     * Semantic color token. Frontend maps to Tailwind classes.
     * Values: 'red', 'amber', 'green', 'blue', 'purple', 'gray', 'indigo'
     */
    @Column(name = "color_tag", length = 30)
    private String colorTag;

    /** Icon name for this option (optional). e.g. 'AlertTriangle' */
    @Column(name = "icon", length = 100)
    private String icon;

    @Column(name = "sort_order")
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "is_active")
    @Builder.Default
    private boolean isActive = true;

    /** NULL = global; set for tenant-specific option visibility */
    @Column(name = "tenant_id")
    private Long tenantId;

    /** Which role sides can see/use this option. NULL = all. */
    @Column(name = "allowed_sides", length = 255)
    private String allowedSides;

    /** Next allowed statuses for status-flow components. JSON array: '["APPROVED","REJECTED"]' */
    @Column(name = "transitions_json", columnDefinition = "JSON")
    private String transitionsJson;
}
