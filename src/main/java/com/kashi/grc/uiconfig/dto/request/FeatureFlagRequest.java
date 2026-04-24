package com.kashi.grc.uiconfig.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class FeatureFlagRequest {
    @NotBlank public String  flagKey;
    public boolean isEnabled;
    public String  description;
    public String  allowedSidesJson;
}
