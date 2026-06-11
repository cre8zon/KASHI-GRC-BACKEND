package com.kashi.grc.uiconfig.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Request DTO for creating / updating a UiFormField.
 *
 * FIX (2026-05-15):
 *  - Added missing new-column fields so the Screen Designer inspector can persist them:
 *    currencyCode, minValue, maxValue, stepValue, lookupEntityType, lookupApiPath,
 *    tagSuggestions, rowsCount.
 *  - Changed isRequired / isVisible from primitive boolean to Boolean wrapper so that
 *    partial PUT requests don't accidentally reset them to false.
 */
@Data
public class UiFormFieldRequest {
    @NotNull  public Long    formId;
    @NotBlank public String  fieldKey;
    @NotBlank public String  fieldType;
    public String  label;        // optional — DIVIDER and SECTION_HEADER have no label
    public String  placeholder;
    public String  helperText;

    // FIX: Boolean wrapper — null means "not provided in this request" (safe partial update)
    public Boolean isRequired = false;
    public Boolean isVisible  = true;

    public Integer sortOrder  = 0;
    public String  optionsComponentKey;
    public String  validationRulesJson;
    public String  dependsOnJson;
    public Integer gridCols   = 12;
    public Integer stepNumber = 1;

    // ── New fields (added to match UiFormField entity) ────────────────────────

    /** For CURRENCY type: ISO currency code. e.g. "USD", "EUR", "INR" */
    public String  currencyCode;

    /** For SLIDER / NUMBER types: minimum allowed value */
    public Double  minValue;

    /** For SLIDER / RATING types: maximum allowed value */
    public Double  maxValue;

    /** For SLIDER type: step increment. e.g. 0.5, 1, 5 */
    public Double  stepValue;

    /**
     * For LOOKUP type: which entityType to search against.
     * e.g. "USER", "VENDOR", "RISK"
     */
    public String  lookupEntityType;

    /**
     * For LOOKUP type: the API endpoint to call for async search.
     * e.g. "/v1/users", "/v1/vendors"
     */
    public String  lookupApiPath;

    /**
     * For TAG type: comma-separated autocomplete suggestions.
     * e.g. "SOX,PCI-DSS,ISO27001,HIPAA,SOC2,GDPR"
     */
    public String  tagSuggestions;

    /**
     * For TEXTAREA and JSON_EDITOR types: number of visible rows.
     */
    public Integer rowsCount;
    /** Pre-populated value; for hidden fields, the submitted payload value */
    public String  defaultValue;
}