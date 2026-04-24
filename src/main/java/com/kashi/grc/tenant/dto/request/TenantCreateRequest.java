package com.kashi.grc.tenant.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class TenantCreateRequest {
    @NotBlank public String name;
    @NotBlank public String code;
    public String description;
    public String plan;
    public Integer maxUsers;
    public Integer maxVendors;
}
