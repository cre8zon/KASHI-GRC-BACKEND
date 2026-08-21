package com.kashi.grc.audit.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * AuditControlInstance — runtime snapshot of one AuditControl (leaf node).
 *
 * Controls attach to ANY depth of the section instance tree.
 * sectionInstanceId points to whichever AuditSectionInstance this control lives under.
 *
 * ── FLAT CONTROL LIST ────────────────────────────────────────────────────────
 * findByEngagementId() returns ALL controls across all section depths — flat list.
 * UI groups them by sectionInstanceId / section path as needed.
 * The sectionPath field (snapshotted from section) helps UI render breadcrumb
 * without a join: "A / A.5 / A.5.1" → control lives at A.5.1 level.
 *
 * ── ASSIGNMENT INHERITANCE ────────────────────────────────────────────────────
 * assignedAuditorId is set either:
 *   (a) Explicitly per control (fine-grained delegation)
 *   (b) Inherited from parent section assignment (bulk assignment)
 * The service handles (b) during snapshotTemplate() — it copies the
 * section's assigned auditor to each control in that section.
 *
 * ── ISOLATION ────────────────────────────────────────────────────────────────
 * controlTagSnapshot snapshotted from AuditControl.controlTag at instantiation.
 * KashiGuard reads this — never the live library tag.
 *
 * ── OPTION B SUPPORT ─────────────────────────────────────────────────────────
 * workflowInstanceId is nullable. Null = Option A (compound sections).
 * Non-null = Option B (per-control workflow). Schema supports both, zero migration needed.
 */
@Entity
@Table(name = "audit_control_instances",
        indexes = {
                @Index(name = "idx_aci_tenant",         columnList = "tenant_id"),
                @Index(name = "idx_aci_engagement",     columnList = "engagement_id"),
                @Index(name = "idx_aci_section",        columnList = "section_instance_id"),
                @Index(name = "idx_aci_section_path",   columnList = "section_path"),
                @Index(name = "idx_aci_auditor",        columnList = "assigned_auditor_id"),
                @Index(name = "idx_aci_auditee",        columnList = "auditee_assigned_user_id"),
                @Index(name = "idx_aci_tag",            columnList = "control_tag_snapshot"),
                @Index(name = "idx_aci_result",         columnList = "test_result")
        }
)
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class AuditControlInstance extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "engagement_id", nullable = false)
    private Long engagementId;

    /** The section node this control is directly under (at any depth) */
    @Column(name = "section_instance_id", nullable = false)
    private Long sectionInstanceId;

    /**
     * Snapshotted from the section's path at instantiation time.
     * e.g. "/4/12/37/" — lets UI render breadcrumb without a join.
     * Also allows "find all controls under A.9" via path LIKE '/4/%'
     */
    @Column(name = "section_path", length = 500)
    private String sectionPath;

    @Column(name = "original_control_id")
    private Long originalControlId;

    // ── Snapshots (frozen at instantiation) ───────────────────────────────────

    @Column(name = "control_name_snapshot", nullable = false, columnDefinition = "TEXT")
    private String controlNameSnapshot;

    @Column(name = "control_code_snapshot", length = 100)
    private String controlCodeSnapshot;

    @Column(name = "description_snapshot", columnDefinition = "TEXT")
    private String descriptionSnapshot;

    /**
     * Frozen copy of AuditControl.evidenceGuidance, taken when the engagement
     * was created. Snapshotted rather than read live for the same reason every
     * other *Snapshot column here is: editing the library must not silently
     * change what an in-flight or already-signed-off engagement was assessed
     * against.
     *
     * Null on rows created before this column existed, and on controls whose
     * library entry has no guidance — in both cases getControlInstance falls
     * back to rolling up the mapped tests.
     */
    @Column(name = "evidence_guidance_snapshot", columnDefinition = "TEXT")
    private String evidenceGuidanceSnapshot;

    /** Snapshotted section breadcrumb for display: "A.9 / A.9.1 / A.9.1.1" */
    @Column(name = "section_breadcrumb_snapshot", length = 500)
    private String sectionBreadcrumbSnapshot;

    @Column(name = "test_type_snapshot", length = 30)
    private String testTypeSnapshot;

    @Column(name = "framework_ref_snapshot", length = 100)
    private String frameworkRefSnapshot;

    /**
     * Snapshotted KashiGuard tag. Frozen at instantiation — isolation contract.
     * GuardRule matches on this, never the live library tag.
     */
    @Column(name = "control_tag_snapshot", length = 80)
    private String controlTagSnapshot;

    /**
     * Phase 3 (UCF): the frozen ancestry chain — 'IAM-02.3,IAM-02,IAM'.
     * Written once at instantiation by TagExpansionService and never edited.
     * The evidence matcher tests the uploaded tag for MEMBERSHIP in this set, so
     * coarse evidence (tagged IAM-02) reaches a leaf-level control (IAM-02.3),
     * while the reverse cannot happen. control_tag_snapshot is retained for the
     * legacy exact-match path on instances created before Phase 3.
     */
    @Column(name = "matched_tags_snapshot", length = 500)
    private String matchedTagsSnapshot;

    @Column(name = "weight")
    private Double weight;

    @Column(name = "is_mandatory", nullable = false)
    @Builder.Default
    private boolean isMandatory = false;

    @Column(name = "order_no", nullable = false)
    @Builder.Default
    private Integer orderNo = 0;

    // ── Assignment ────────────────────────────────────────────────────────────

    @Column(name = "assigned_auditor_id")
    private Long assignedAuditorId;

    @Column(name = "auditee_assigned_user_id")
    private Long auditeeAssignedUserId;

    // ── Test execution ────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "test_result", length = 30)
    @Builder.Default
    private TestResult testResult = TestResult.NOT_TESTED;

    @Column(name = "test_notes", columnDefinition = "TEXT")
    private String testNotes;

    /** Due date for auditee evidence submission. Set when assigning auditee. */
    @Column(name = "evidence_due_date")
    private java.time.LocalDate evidenceDueDate;

    @Column(name = "test_procedure", columnDefinition = "TEXT")
    private String testProcedure;

    @Column(name = "tested_at")
    private LocalDateTime testedAt;

    @Column(name = "tested_by")
    private Long testedBy;

    // ── Auditee evidence ──────────────────────────────────────────────────────

    @Column(name = "auditee_evidence_submitted", nullable = false)
    @Builder.Default
    private boolean auditeeEvidenceSubmitted = false;

    @Column(name = "auditee_evidence_submitted_at")
    private LocalDateTime auditeeEvidenceSubmittedAt;

    // ── Finding linkage ───────────────────────────────────────────────────────

    @Column(name = "finding_linked", nullable = false)
    @Builder.Default
    private boolean findingLinked = false;

    /** Issue.id — loose coupling, no FK, keeps modules independent */
    @Column(name = "finding_issue_id")
    private Long findingIssueId;

    // ── Option B: per-control workflow ────────────────────────────────────────

    @Column(name = "workflow_instance_id")
    private Long workflowInstanceId;

    public enum TestResult {
        EFFECTIVE,
        PARTIALLY_EFFECTIVE,
        INEFFECTIVE,
        NOT_APPLICABLE,
        NOT_TESTED
    }
}