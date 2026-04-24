package com.kashi.grc.uiconfig.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UiStateRequest {
    @NotBlank public String screenKey;
    @NotBlank public String stateType;
    @NotBlank public String title;
    public String description;
    public String icon;
    public String colorTag = "gray";
    public String ctaLabel;
    public String ctaAction;
    public String secondaryCtaLabel;
    public String secondaryCtaAction;
    public boolean isActive = true;
}
