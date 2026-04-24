package com.kashi.grc.uiconfig.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UiOptionRequest {
    @NotNull  public Long   componentId;
    @NotBlank public String optionValue;
    @NotBlank public String optionLabel;
    public String  colorTag;
    public String  icon;
    public Integer sortOrder = 0;
    public boolean isActive = true;
    public String  allowedSides;
    public String  transitionsJson;
}
