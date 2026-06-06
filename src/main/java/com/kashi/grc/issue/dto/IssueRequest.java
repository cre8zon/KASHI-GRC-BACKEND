package com.kashi.grc.issue.dto;

import com.kashi.grc.issue.domain.Issue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Request DTO for manual issue creation — INTERNAL and EXTERNAL types.
 * Used by POST /v1/issues from the frontend issue management UI.
 *
 * For AUTOMATED ingestion via external tools, use IssueIngestRequest instead.
 */
@Data
public class IssueRequest {

    // ── Core ──────────────────────────────────────────────────────────────────

    @NotBlank
    private String title;

    private String description;

    @NotNull
    private Issue.IssueType issueType;

    @NotNull
    private Issue.Severity severity;

    private String category;

    private String sourceModule;   // e.g. AUDIT, TPRM, RISK

    // ── Source linkage ────────────────────────────────────────────────────────

    private String sourceEntityType;
    private Long   sourceEntityId;
    private String sourceDescription;

    // ── Ownership ─────────────────────────────────────────────────────────────

    private Long ownerId;

    // ── SLA ───────────────────────────────────────────────────────────────────

    /** Explicit due date — if null, computed from severity SLA matrix by service */
    private LocalDateTime dueAt;

    // ── Framework ─────────────────────────────────────────────────────────────

    private List<Long> linkedControlIds;
    private List<Long> linkedRiskIds;
    private String     frameworkRef;

    // ── Workflow ──────────────────────────────────────────────────────────────

    /**
     * ID of the Workflow blueprint to start.
     * If null, service looks up the default blueprint for this issueType.
     * Platform admin configures which blueprint is default per issueType
     * via the workflow admin UI.
     */
    private Long workflowId;

    // ── RCA (optional at creation, filled via workflow FILL step) ─────────────

    /** RCA method — e.g. 5WHY, FISHBONE, FMEA */
    private String rcaMethod;

    private String rootCauseCategory;

    /** Proximate / immediate cause — what directly failed */
    private String immediateCause;

    /** Underlying systemic cause — the 5-Why answer */
    private String rootCause;

    /**
     * Contributing factors — the MULTILINE_LIST field serializes entries as a JSON array.
     * e.g. ["Lack of training", "Insufficient monitoring"]
     */
    private String contributingFactors;

    /** Whether this is a systemic issue affecting multiple areas */
    private Boolean isSystemic;

    /** Legacy RCA blob — accepted for backward compatibility */
    private String rcaJson;

    // ── Remediation ───────────────────────────────────────────────────────────

    private String remediationPlan;
    private String remediationType;
    private Boolean acceptedRisk;
    private String  acceptedRiskNote;
    private String  closureSummary;
}