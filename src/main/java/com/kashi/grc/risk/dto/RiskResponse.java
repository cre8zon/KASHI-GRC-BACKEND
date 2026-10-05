package com.kashi.grc.risk.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Detail payload for GET /v1/risks/{id}.
 *
 * Property names are the field_key values from risk_detail_header,
 * _tab_overview, _tab_assessment and _tab_treatment. UniversalModulePage reads
 * entity[field.fieldKey] directly, so a name that does not match renders blank
 * with no error anywhere.
 *
 * isAssignedToCurrentUser exists because ui_actions.requires_assignment = 1 on
 * every state-changing risk action, and that flag is INERT unless the GET
 * emits this field — exactly how the control-instance gates were dead for
 * months. UniversalModulePage hides an action only on an explicit `false`, so
 * omitting it would silently ungate everything instead of failing loudly.
 */
@Getter
@Builder
public class RiskResponse {

    private Long    id;
    private String  riskRef;
    private String  title;
    private String  description;
    private String  category;
    private String  riskSource;
    private String  status;

    // ── Assessment ────────────────────────────────────────────────────────────
    private Integer inherentLikelihood;
    private Integer inherentImpact;
    private Integer inherentScore;
    private Integer residualLikelihood;
    private Integer residualImpact;
    private Integer residualScore;
    private String  assessmentNotes;
    private LocalDateTime assessedAt;

    // ── Treatment ─────────────────────────────────────────────────────────────
    private String  treatmentOption;
    private String  treatmentPlan;
    private LocalDateTime treatedAt;
    private String  acceptanceJustification;
    private Long    acceptedById;
    private String  acceptedByName;
    private LocalDateTime acceptedAt;

    // ── Ownership ─────────────────────────────────────────────────────────────
    private Long    ownerId;
    private String  ownerName;
    private String  ownerTeam;
    private String  controlTags;
    private String  frameworkRefs;

    // ── Review cycle ──────────────────────────────────────────────────────────
    private LocalDate nextReviewDate;
    private Integer   reviewFrequencyMonths;
    private Boolean   reviewOverdue;

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    private LocalDateTime closedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long    createdBy;
    private Long    workflowInstanceId;

    // ── Provenance / ownership of the ROW (not the risk) ──────────────────────

    /** The platform library row this was adopted from, if any. */
    private Long    sourceRiskId;

    /** "LIBRARY" for a platform scenario, "TENANT" for the org's own entry. */
    private String  origin;

    /**
     * False for a platform library row. UniversalModulePage hides the whole
     * collaboration surface (workflow, evidence, comments, history) when this
     * is false, which is right: a tenant cannot act on a scenario they have
     * not adopted.
     */
    private Boolean editable;

    /**
     * Drives ui_actions.requires_assignment. See class javadoc.
     * True when the caller owns the risk, created it, or it has no owner yet.
     */
    private Boolean isAssignedToCurrentUser;

    // ── Linked controls ───────────────────────────────────────────────────────
    private List<LinkedControl> linkedControls;
    private Integer linkedControlCount;

    /**
     * One linked LIBRARY control plus its latest observed effectiveness.
     * effectiveness is the test_result of the most recently tested
     * audit_control_instance derived from this control, or NOT_TESTED when no
     * engagement has covered it yet.
     */
    @Getter
    @Builder
    public static class LinkedControl {
        private Long   linkId;
        private Long   controlId;
        private String controlCode;
        private String name;
        private String frameworkRef;
        private String controlTag;
        private String linkNote;
        private String effectiveness;
    }
}
