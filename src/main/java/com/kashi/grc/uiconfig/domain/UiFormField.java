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

    @Column(name = "field_type", length = 50)
    @Convert(converter = FieldTypeConverter.class)
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

    // ── NEW COLUMNS ───────────────────────────────────────────────────────────
    // Migration SQL at bottom of this file.

    /** For CURRENCY type: ISO currency code. e.g. "USD", "EUR", "INR" */
    @Column(name = "currency_code", length = 10)
    private String currencyCode;

    /** For SLIDER / NUMBER types: minimum allowed value */
    @Column(name = "min_value")
    private Double minValue;

    /** For SLIDER / RATING types: maximum allowed value */
    @Column(name = "max_value")
    private Double maxValue;

    /** For SLIDER type: step increment. e.g. 0.5, 1, 5 */
    @Column(name = "step_value")
    private Double stepValue;

    /**
     * For LOOKUP type: which entityType to search against.
     * e.g. "USER", "VENDOR", "RISK"
     * Frontend AsyncSelect uses this to build the search URL.
     */
    @Column(name = "lookup_entity_type", length = 100)
    private String lookupEntityType;

    /**
     * For LOOKUP type: the API endpoint to call for async search.
     * e.g. "/v1/users", "/v1/vendors"
     * If null, frontend derives from lookupEntityType.
     */
    @Column(name = "lookup_api_path", length = 255)
    private String lookupApiPath;

    /**
     * For TAG type: comma-separated autocomplete suggestions.
     * e.g. "SOX,PCI-DSS,ISO27001,HIPAA,SOC2,GDPR"
     */
    @Column(name = "tag_suggestions", columnDefinition = "TEXT")
    private String tagSuggestions;

    /**
     * For TEXTAREA and JSON_EDITOR types: number of visible rows.
     * Defaults to 3 for TEXTAREA, 8 for JSON_EDITOR if null.
     */
    @Column(name = "rows_count")
    private Integer rowsCount;

    // ── FieldType converter — tolerant of empty/null/unknown DB values ─────────

    @jakarta.persistence.Converter
    public static class FieldTypeConverter
            implements jakarta.persistence.AttributeConverter<FieldType, String> {

        @Override
        public String convertToDatabaseColumn(FieldType attribute) {
            return attribute == null ? null : attribute.name();
        }

        @Override
        public FieldType convertToEntityAttribute(String dbData) {
            if (dbData == null || dbData.isBlank()) return null;
            try {
                return FieldType.valueOf(dbData.toUpperCase().trim());
            } catch (IllegalArgumentException e) {
                // Unknown value in DB — log and return TEXT as safe fallback
                org.slf4j.LoggerFactory.getLogger(UiFormField.class)
                        .warn("[UI-FORM-FIELD] Unknown field_type='{}' in DB — defaulting to TEXT", dbData);
                return FieldType.TEXT;
            }
        }
    }

    // ── FieldType enum ────────────────────────────────────────────────────────

    public enum FieldType {
        // ── Existing (unchanged) ─────────────────────────────────────────────
        TEXT, EMAIL, PASSWORD, NUMBER, DECIMAL,
        SELECT, MULTI_SELECT, RADIO, CHECKBOX, TOGGLE,
        TEXTAREA, DATE, DATE_RANGE, FILE, FILE_MULTI,
        RICH_TEXT, SECTION_HEADER, DIVIDER,

        // ── New additions ─────────────────────────────────────────────────────

        /** Formatted phone number input — renders PhoneInput component */
        PHONE,

        /** URL input with https:// validation — renders Input type="url" */
        URL,

        /** Decimal with currency prefix — renders CurrencyInput component.
         *  Uses currencyCode column (default "USD"). */
        CURRENCY,

        /** Star rating 1..maxValue — renders RatingInput component.
         *  Uses maxValue column (default 5). */
        RATING,

        /** Range slider — renders SliderInput component.
         *  Uses minValue, maxValue, stepValue columns. */
        SLIDER,

        /** Multi-line JSON textarea with format + live validation —
         *  renders JsonEditor component. Uses rowsCount column. */
        JSON_EDITOR,

        /** Search-as-you-type reference field — renders AsyncSelect/Lookup.
         *  Uses lookupEntityType and lookupApiPath columns. */
        LOOKUP,

        /** Add/remove list of text items — renders MultilineListInput */
        MULTILINE_LIST,

        /** Hex color picker with swatch preview */
        COLOR,

        /** Chip/tag input with autocomplete — renders TagInput component.
         *  Uses tagSuggestions column for autocomplete list. */
        TAG,
    }

    // ── Migration SQL ─────────────────────────────────────────────────────────
    /*
    -- New columns (all nullable — safe on existing rows):
    ALTER TABLE ui_form_fields
      ADD COLUMN currency_code       VARCHAR(10)    NULL COMMENT 'For CURRENCY: ISO code e.g. USD',
      ADD COLUMN min_value           DOUBLE         NULL COMMENT 'For SLIDER/NUMBER: minimum value',
      ADD COLUMN max_value           DOUBLE         NULL COMMENT 'For SLIDER/RATING: maximum value',
      ADD COLUMN step_value          DOUBLE         NULL COMMENT 'For SLIDER: step increment',
      ADD COLUMN lookup_entity_type  VARCHAR(100)   NULL COMMENT 'For LOOKUP: entity type to search',
      ADD COLUMN lookup_api_path     VARCHAR(255)   NULL COMMENT 'For LOOKUP: search API endpoint',
      ADD COLUMN tag_suggestions     TEXT           NULL COMMENT 'For TAG: comma-sep suggestions',
      ADD COLUMN rows_count          INT            NULL COMMENT 'For TEXTAREA/JSON_EDITOR: row count';

    -- Extend field_type enum (MySQL — add new values at end to keep ordinals stable):
    ALTER TABLE ui_form_fields
      MODIFY COLUMN field_type ENUM(
        'TEXT','EMAIL','PASSWORD','NUMBER','DECIMAL',
        'SELECT','MULTI_SELECT','RADIO','CHECKBOX','TOGGLE',
        'TEXTAREA','DATE','DATE_RANGE','FILE','FILE_MULTI',
        'RICH_TEXT','SECTION_HEADER','DIVIDER',
        'PHONE','URL','CURRENCY','RATING','SLIDER',
        'JSON_EDITOR','LOOKUP','MULTILINE_LIST','COLOR','TAG'
      ) NOT NULL DEFAULT 'TEXT';
    */
}