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

    private String rcaJson;
    private String rootCauseCategory;
    private String remediationPlan;
    private String remediationType;
}