package com.kashi.grc.uiconfig.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * One field inside a UiForm.
 * fieldType drives which React component renders.
 * validationRulesJson drives Zod schema built at runtime.
 * dependsOnJson enables conditional field visibility.
 */
@Entity
@Table(name = "ui_form_fields")
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class UiFormField extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "form_id", nullable = false)
    private UiForm form;

    /** Maps to the JSON body key sent to the API. e.g. 'riskClassification' */
    @Column(name = "field_key", nullable = false, length = 100)
    private String fieldKey;

    @Column(name = "field_type", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private FieldType fieldType;

    @Column(name = "label", nullable = false, length = 255)
    private String label;

    @Column(name = "placeholder", length = 255)
    private String placeholder;

    @Column(name = "helper_text", length = 500)
    private String helperText;

    @Column(name = "is_required")
    @Builder.Default
    private boolean isRequired = false;

    @Column(name = "is_visible")
    @Builder.Default
    private boolean isVisible = true;

    @Column(name = "sort_order")
    @Builder.Default
    private Integer sortOrder = 0;

    /** Links to UiComponent.componentKey for SELECT/MULTI_SELECT/RADIO */
    @Column(name = "options_component_key", length = 100)
    private String optionsComponentKey;

    /**
     * Zod validation rules as JSON.
     * {"min": 1, "max": 100, "pattern": "^[A-Z]", "minLength": 3, "maxLength": 255}
     */
    @Column(name = "validation_rules_json", columnDefinition = "JSON")
    private String validationRulesJson;

    /**
     * Conditional visibility. JSON: {"field": "industry", "operator": "eq", "value": "Healthcare"}
     * Field is shown only when the condition is true.
     */
    @Column(name = "depends_on_json", columnDefinition = "JSON")
    private String dependsOnJson;

    /** Grid width: 3=quarter, 6=half, 12=full width */
    @Column(name = "grid_cols")
    @Builder.Default
    private Integer gridCols = 12;

    /** Which step (1-based) this field belongs to in multi-step forms */
    @Column(name = "step_number")
    @Builder.Default
    private Integer stepNumber = 1;

    @Column(name = "tenant_id")
    private Long tenantId;

    public enum FieldType {
        TEXT, EMAIL, PASSWORD, NUMBER, DECIMAL,
        SELECT, MULTI_SELECT, RADIO, CHECKBOX, TOGGLE,
        TEXTAREA, DATE, DATE_RANGE, FILE, FILE_MULTI,
        RICH_TEXT, SECTION_HEADER, DIVIDER
    }
}
