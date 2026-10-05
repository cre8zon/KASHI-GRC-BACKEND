package com.kashi.grc.risk.domain;

import com.kashi.grc.common.domain.GlobalOrTenantEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Risk register entry.
 *
 * ── WHY GlobalOrTenantEntity AND NOT AuditableEntity ────────────────────────
 * AuditableEntity extends TenantAwareEntity, whose tenant_id is nullable=false.
 * The risks table deliberately allows NULL — that is what marks a platform
 * library scenario. Mapping it with a non-null tenant_id would make every
 * library row unreadable through JPA. AuditPolicy solves the identical problem
 * the identical way, so this follows that precedent rather than inventing one,
 * and declares created_by / updated_by / is_deleted itself.
 *
 * SCOPING
 *   tenant_id IS NULL  → platform library scenario, owned by SYSTEM, never
 *                        written to by a tenant.
 *   tenant_id = <id>   → that tenant's own register entry.
 *   sourceRiskId       → the library row this entry was adopted from. Adoption
 *                        COPIES; it never shares a row.
 *
 * CONTROL LINKAGE
 *   risk_control_links points at audit_controls (the LIBRARY), never at
 *   audit_control_instances. A risk is treated by a standing control;
 *   instances are point-in-time engagement records. Effectiveness is read by
 *   traversal — see RiskControlLinkRepositoryImpl.findEffectivenessByRiskId.
 *
 * SCORES
 *   inherentScore and residualScore are DERIVED (likelihood × impact) and are
 *   written by RiskService on every assessment write. They are stored rather
 *   than computed on read because the list screen sorts and filters on them.
 *   Never trust a score supplied by the client.
 *
 * SEED
 *   See risk_register_seed.sql §1 for the DDL these columns mirror.
 */
@Entity
@Table(
        name = "risks",
        indexes = {
                @Index(name = "idx_risk_tenant",   columnList = "tenant_id"),
                @Index(name = "idx_risk_status",   columnList = "status"),
                @Index(name = "idx_risk_owner",    columnList = "owner_id"),
                @Index(name = "idx_risk_source",   columnList = "source_risk_id"),
                @Index(name = "idx_risk_category", columnList = "category"),
                @Index(name = "idx_risk_review",   columnList = "next_review_date"),
                @Index(name = "idx_risk_tags",     columnList = "control_tags"),
        }
)
@Getter @Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Risk extends GlobalOrTenantEntity {

    // ── Identity ──────────────────────────────────────────────────────────────

    /** The platform library row this entry was adopted from. NULL = raised directly. */
    @Column(name = "source_risk_id")
    private Long sourceRiskId;

    /** Human-readable reference, e.g. RSK-2026-0042. Library rows use RSK-L-nnn. */
    @Column(name = "risk_ref", length = 30)
    private String riskRef;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    // ── Classification ────────────────────────────────────────────────────────

    /** Option set risk_category_options — ACCESS_CONTROL, DATA_PROTECTION, … */
    @Column(name = "category", length = 50)
    private String category;

    /** Option set risk_source_options — ISO27005, DPDPA, CERTIN, RBI, SEBI, … */
    @Column(name = "risk_source", length = 50)
    private String riskSource;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private Status status = Status.IDENTIFIED;

    // ── Inherent assessment (before treatment) ────────────────────────────────

    @Column(name = "inherent_likelihood")
    private Integer inherentLikelihood;

    @Column(name = "inherent_impact")
    private Integer inherentImpact;

    /** Derived: likelihood × impact. Written by the service, never by the client. */
    @Column(name = "inherent_score")
    private Integer inherentScore;

    // ── Residual assessment (after treatment) ─────────────────────────────────

    @Column(name = "residual_likelihood")
    private Integer residualLikelihood;

    @Column(name = "residual_impact")
    private Integer residualImpact;

    /** Derived: likelihood × impact. Written by the service, never by the client. */
    @Column(name = "residual_score")
    private Integer residualScore;

    // ── Treatment ─────────────────────────────────────────────────────────────

    /** Option set risk_treatment_options — MODIFY / RETAIN / AVOID / SHARE. */
    @Column(name = "treatment_option", length = 30)
    private String treatmentOption;

    @Column(name = "treatment_plan", columnDefinition = "TEXT")
    private String treatmentPlan;

    @Column(name = "assessment_notes", columnDefinition = "TEXT")
    private String assessmentNotes;

    // ── Acceptance ────────────────────────────────────────────────────────────

    @Column(name = "acceptance_justification", columnDefinition = "TEXT")
    private String acceptanceJustification;

    @Column(name = "accepted_by_id")
    private Long acceptedById;

    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    // ── Ownership ─────────────────────────────────────────────────────────────

    @Column(name = "owner_id")
    private Long ownerId;

    @Column(name = "owner_team", length = 200)
    private String ownerTeam;

    /** Comma-separated, same vocabulary as audit_controls.control_tag. */
    @Column(name = "control_tags", length = 500)
    private String controlTags;

    /** Comma-separated framework references, e.g. "ISO 27001 A.8.1,SOC2 CC6.1". */
    @Column(name = "framework_refs", length = 500)
    private String frameworkRefs;

    // ── Lifecycle timestamps ──────────────────────────────────────────────────

    @Column(name = "assessed_at")
    private LocalDateTime assessedAt;

    @Column(name = "treated_at")
    private LocalDateTime treatedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "next_review_date")
    private LocalDate nextReviewDate;

    @Column(name = "review_frequency_months")
    @Builder.Default
    private Integer reviewFrequencyMonths = 12;

    // ── Workflow ──────────────────────────────────────────────────────────────

    @Column(name = "workflow_instance_id")
    private Long workflowInstanceId;

    // ── Audit columns (declared here, not inherited — see class javadoc) ──────

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "is_deleted", nullable = false)
    @Builder.Default
    private boolean isDeleted = false;

    // ── Status ────────────────────────────────────────────────────────────────

    /**
     * Must stay identical to module_blueprints.status_flow_json for RISK.
     * The UniversalModulePage workflow gate resolves transitions from that JSON
     * and matches them to ui_actions by actionKey; a status here that is absent
     * there renders no buttons at all.
     */
    public enum Status {
        IDENTIFIED,
        ASSESSED,
        TREATMENT_PLANNED,
        TREATED,
        ACCEPTED,
        CLOSED
    }

    /** True for a platform library scenario — read-only to every tenant. */
    @Transient
    public boolean isLibraryRow() {
        return getTenantId() == null;
    }
}
