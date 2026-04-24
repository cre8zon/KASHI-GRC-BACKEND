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
}
