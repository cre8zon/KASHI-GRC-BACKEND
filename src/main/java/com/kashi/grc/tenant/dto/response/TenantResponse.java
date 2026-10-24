package com.kashi.grc.tenant.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TenantResponse {
    private Long tenantId;
    private String name;
    private String code;
    private String description;
    private String status;
    private String plan;
    private Integer maxUsers;
    private Integer maxVendors;
    private Boolean isAuditFirm;
    private LocalDateTime createdAt;

    // Commercial profile
    private String acquisitionChannel;
    private LocalDate firstPaidAt;
    private String industry;
    private String employeeBand;
    private String country;

    // Bill-to
    private String legalName;
    private String billingEmail;
    private String billingAddress;
    private String taxId;

    /** Org Owner (tenants.owner_user_id). */
    private Long ownerUserId;
    /** When suspended, and when the read-only grace period ends (access is blocked after). */
    private LocalDateTime suspendedAt;
    private LocalDateTime readOnlyUntil;
}
