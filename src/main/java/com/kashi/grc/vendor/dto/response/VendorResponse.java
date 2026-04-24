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