package com.kashi.grc.uiconfig.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UiNavigationRequest {
    @NotBlank public String navKey;
    @NotBlank public String label;
    public String icon;
    @NotBlank public String route;
    public String parentKey;
    public Integer sortOrder;
    public String module;
    public String allowedSides;
    public String minLevel;
    public String requiredPermission;
    public boolean isActive = true;
    public String badgeCountEndpoint;
}
