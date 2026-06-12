package com.kashi.grc.issue.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kashi.grc.issue.domain.Issue;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * IssueResponse — returned by all issue endpoints.
 *
 * Includes computed fields (slaDueIn, isOverdue, ownerName)
 * and UI keys for Screen Designer integration.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class IssueResponse {

    // ── Identity ──────────────────────────────────────────────────────────────
    private Long   id;
    private String issueRef;
    private String title;
    private String description;

    // ── Classification ────────────────────────────────────────────────────────
    private Issue.IssueType issueType;
    private Issue.Severity  severity;
    private Issue.Status    status;
    private String          category;
    private String          sourceModule;

    // ── Source ────────────────────────────────────────────────────────────────
    private String sourceEntityType;
    private Long   sourceEntityId;
    private String sourceDescription;
    private String externalId;
    private String externalSource;
    private Double cvssScore;

    // ── Ownership ─────────────────────────────────────────────────────────────
    private Long   ownerId;
    private String ownerName;
    private Long   createdBy;
    private String createdByName;
    private String raisedBySide;

    // ── SLA ───────────────────────────────────────────────────────────────────
    private LocalDateTime dueAt;
    private boolean       slaBreached;
    private int           escalationCount;
    private LocalDateTime lastEscalatedAt;

    /** Computed: hours until SLA breach. Negative = already breached. */
    private Long slaDueInHours;

    // ── Timeline ──────────────────────────────────────────────────────────────
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime acknowledgedAt;
    private LocalDateTime remediatedAt;
    private LocalDateTime validatedAt;
    private LocalDateTime closedAt;
    private Long           closedBy;

    // ── RCA ───────────────────────────────────────────────────────────────────
    private String  rcaMethod;
    private String  rootCauseCategory;
    private String  immediateCause;
    private String  rootCause;
    private String  contributingFactors;  // JSON array string
    @com.fasterxml.jackson.annotation.JsonProperty("isSystemic")
    private boolean isSystemic;
    private String  rcaJson;              // legacy blob — kept for compatibility

    // ── Remediation ───────────────────────────────────────────────────────────
    private String  remediationPlan;
    private String  remediationType;
    @com.fasterxml.jackson.annotation.JsonProperty("acceptedRisk")
    private boolean acceptedRisk;
    private String  acceptedRiskNote;
    private String  closureSummary;

    // ── Framework ─────────────────────────────────────────────────────────────
    private String linkedControlIds;
    private String linkedRiskIds;
    private String frameworkRef;

    // ── Workflow ──────────────────────────────────────────────────────────────
    private Long   workflowInstanceId;
    private String workflowStatus;     // mirrors WorkflowInstance.status

    // ── UI keys (from Screen Designer) ────────────────────────────────────────
    private String listScreenKey;
    private String detailScreenKey;
    private String itemScreenKey;
}