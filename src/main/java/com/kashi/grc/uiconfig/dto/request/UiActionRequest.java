package com.kashi.grc.uiconfig.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UiActionRequest {
    @NotBlank public String screenKey;
    @NotBlank public String actionKey;
    @NotBlank public String label;
    public String  icon;
    public String  variant = "primary";
    public String  apiEndpoint;
    public String  httpMethod = "POST";
    public String  payloadTemplateJson;
    public String  requiredPermission;
    public String  allowedSides;
    public String  allowedStatusesJson;
    public boolean requiresConfirmation;
    public String  confirmationMessage;
    public boolean requiresRemarks;
    public Integer sortOrder = 0;
    public boolean isActive = true;
}
