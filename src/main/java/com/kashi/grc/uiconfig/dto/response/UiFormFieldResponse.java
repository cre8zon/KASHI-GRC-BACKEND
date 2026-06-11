package com.kashi.grc.uiconfig.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

@Data @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UiFormFieldResponse {
    private Long    id;
    private String  fieldKey;
    private String  fieldType;
    private String  label;
    private String  placeholder;
    private String  helperText;
    private boolean isRequired;
    private Integer sortOrder;
    private String  optionsComponentKey;
    private String  validationRulesJson;
    private String  dependsOnJson;
    private Integer gridCols;
    private Integer stepNumber;
    // ── Extended field-type metadata ──────────────────────────────────────────
    /** LOOKUP: which entity to search (USER, ROLE, AUDIT_TEMPLATE, WORKFLOW…) */
    private String  lookupEntityType;
    /** LOOKUP: override API path if not derivable from lookupEntityType */
    private String  lookupApiPath;
    /** TEXTAREA / JSON_EDITOR: visible row count */
    private Integer rowsCount;
    /** SLIDER / NUMBER: minimum value */
    private Double  minValue;
    /** SLIDER / RATING: maximum value */
    private Double  maxValue;
    /** SLIDER: step increment */
    private Double  stepValue;
    /** CURRENCY: ISO code e.g. USD, INR */
    private String  currencyCode;
    /** TAG: comma-separated autocomplete suggestions */
    private String  tagSuggestions;
    /** Pre-populated value; for hidden fields, the submitted payload value */
    private String  defaultValue;
}