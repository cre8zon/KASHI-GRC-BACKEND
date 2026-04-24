package com.kashi.grc.tenant.dto.request;

import lombok.Data;

@Data
public class TenantUpdateRequest {
    public String name;
    public String description;
    public String plan;
    public String status;
    public Integer maxUsers;
    public Integer maxVendors;
}
