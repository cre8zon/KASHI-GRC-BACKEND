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

    /**
     * The primary contact, by name — the thing the vendor list actually wants to
     * show and the one piece of them that was never sent.
     *
     * The vendor table has no contact name: onboarding puts the address in
     * vendors.primary_contact_email and creates the person as a USER row with
     * users.vendor_id set. So the list had an email (dropped by NON_NULL
     * whenever the column is empty) and a bare numeric vrmUserId, and no way to
     * render "Priyanka Paridhi" without a second lookup the table does not make.
     */
    private String      primaryContactName;

    /**
     * Who that name belongs to. vrmUserId is kept for the callers already using
     * it, but it is whichever vendor-side user came back first from an unordered
     * query — it is not a designation. This one is resolved deliberately.
     */
    private Long        primaryContactUserId;
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