package com.kashi.grc.uiconfig.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class FeatureFlagRequest {
    @NotBlank public String  flagKey;
    public boolean isEnabled;
    /** GLOBAL (all tenants) | PLATFORM (system only) | TENANT (targetTenantId). */
    public String  scope;
    /** Target tenant when scope=TENANT — this is how a feature is licensed to
     *  one organization. */
    public Long    targetTenantId;
    public String  description;
    public String  allowedSidesJson;
}