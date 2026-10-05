package com.kashi.grc.vendor.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VendorResponse {
    private Long        vendorId;

    /**
     * Same value as vendorId, under the name the generic list screen reads.
     *
     * UniversalModulePage.handleRowClick does
     *     navigate(`/module/${base}/${row.id}`)
     * so a DTO without `id` produces /module/vendor/undefined and the detail
     * page never resolves. Every module that works exposes `id`; this one
     * exposed only vendorId, which is why clicking a row went nowhere.
     *
     * Added rather than renaming vendorId, because existing callers read that
     * name and a rename would break them silently.
     */
    private Long        id;
    private String      name;
    private String      legalName;
    private String      registrationNumber;
    private String      country;
    private String      industry;
    private String      status;
    private String      riskClassification;
    private String      criticality;
    private String      dataAccessLevel;
    private String      servicesProvided;
    private String      website;
    private Long        vrmUserId;
    private String      primaryContactEmail;
    private BigDecimal  currentRiskScore;
    private Long        tierId;
    private LocalDateTime createdAt;
    private Long        activeCycleId;
    private Boolean     assessmentInstantiated;
    private Long        activeWorkflowInstanceId;
    private Integer     currentCycleNo;

    /**
     * NEW — status of the linked WorkflowInstance (IN_PROGRESS, ON_HOLD, CANCELLED, COMPLETED…).
     * Null when no workflow instance is linked to the active cycle.
     *
     * Used by VendorDetailPage.setupIncomplete to show the setup panel when the
     * workflow instance has been cancelled — even though activeWorkflowInstanceId
     * is still set (it points to the cancelled instance on the cycle record).
     */
    private String workflowInstanceStatus;
}