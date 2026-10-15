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

    /**
     * Does NOT set tenants.is_audit_firm. A true value opens an audit-firm
     * verification application for the new tenant as a DRAFT; the flag only
     * becomes true when that application is approved with evidence and the
     * firm's email confirmation.
     */
    public Boolean isAuditFirm;

    // Commercial profile — see Tenant for the vocabularies.
    public String acquisitionChannel;
    public String industry;
    public String employeeBand;
    public String country;
}
