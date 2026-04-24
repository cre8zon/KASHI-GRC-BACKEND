package com.kashi.grc.uiconfig.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UiComponentRequest {
    @NotBlank public String componentKey;
    @NotBlank public String componentType;
    public String module;
    public String screen;
    public String label;
    public boolean isVisible = true;
    public String configJson;
}
