package com.kashi.grc.tenant.dto.request;

import lombok.Data;

@Data
public class TenantUpdateRequest {
    public String name;
    public String description;
    public String plan;
    public String status;
    /** Why the status is changing — required when suspending (kept in account_status_changes). */
    public String statusReason;
    public Integer maxUsers;
    public Integer maxVendors;

    // Commercial profile
    public String acquisitionChannel;
    public String industry;
    public String employeeBand;
    public String country;

    // Bill-to
    public String legalName;
    public String billingEmail;
    public String billingAddress;
    public String taxId;
}
