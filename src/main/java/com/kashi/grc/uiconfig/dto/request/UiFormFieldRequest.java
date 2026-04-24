package com.kashi.grc.uiconfig.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UiFormFieldRequest {
    @NotNull  public Long   formId;
    @NotBlank public String fieldKey;
    @NotBlank public String fieldType;
    @NotBlank public String label;
    public String  placeholder;
    public String  helperText;
    public boolean isRequired;
    public boolean isVisible = true;
    public Integer sortOrder = 0;
    public String  optionsComponentKey;
    public String  validationRulesJson;
    public String  dependsOnJson;
    public Integer gridCols = 12;
    public Integer stepNumber = 1;
}
