package com.kashi.grc.uiconfig.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * Registry of every dynamic UI component in the system.
 * Each row is a dropdown, badge set, radio group, or feature flag.
 * Adding/changing options never requires a code deploy.
 */
@Entity
@Table(name = "ui_components")
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class UiComponent extends BaseEntity {

    /** Unique key used by frontend: 'vendor_risk_classification', 'vendor_status' */
    @Column(name = "component_key", unique = true, nullable = false, length = 100)
    private String componentKey;

    @Column(name = "component_type", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private ComponentType componentType;

    /** Which module owns this component: 'TPRM', 'USER_MGMT', 'AUDIT' */
    @Column(name = "module", length = 100)
    private String module;

    /** Which screen(s) use this: 'vendor_list', 'vendor_detail' */
    @Column(name = "screen", length = 100)
    private String screen;

    @Column(name = "label", length = 255)
    private String label;

    @Column(name = "is_visible")
    @Builder.Default
    private boolean isVisible = true;

    /** NULL = global; set for tenant-level overrides */
    @Column(name = "tenant_id")
    private Long tenantId;

    /** Extra config as JSON: default value, placeholder, tooltip, etc. */
    @Column(name = "config_json", columnDefinition = "JSON")
    private String configJson;

    public enum ComponentType {
        DROPDOWN, MULTI_SELECT, BADGE, RADIO, CHECKBOX_GROUP, TABLE_COLUMNS, FEATURE_FLAG, STATUS_FLOW
    }
}
