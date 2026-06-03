package com.kashi.grc.audit.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * AuditEngagement — one audit run within a project.
 * Analogous to VendorAssessment in the TPRM module.
 *
 * e.g. "ISO 27001 Surveillance Audit 2026" within "Cloud Security Program 2026"
 *
 * ISOLATION CONTRACT:
 *   At engagement creation, the template is instantiated into:
 *     AuditEngagementTemplateInstance → AuditSectionInstance → AuditControlInstance
 *   These snapshots are NEVER updated from the library after instantiation.
 *   Changes to the template library do not affect running engagements.
 *
 *   The project is also snapshotted once per project lifecycle:
 *     AuditProject → AuditProjectInstance (shared by all engagements in the project)
 *   projectInstanceId references this frozen snapshot — plain Long, no join.
 *
 * WORKFLOW:
 *   workflowInstanceId links to the engagement-level workflow.
 *   Blueprint: AUDIT_ENGAGEMENT_INTERNAL or AUDIT_ENGAGEMENT_EXTERNAL
 *   Started automatically by AuditEngagementService.create().
 *
 * auditType:
 *   INTERNAL → org-side actors (CAE, Auditor, Auditee) — no external auditor
 *   EXTERNAL → involves AUDITOR side (external audit firm) + AUDITEE side
 *
 * engagementRef: human-readable reference, e.g. ENG-2026-0042
 */
@Entity
@Table(name = "audit_engagements",
        indexes = {
                @Index(name = "idx_audit_eng_tenant",        columnList = "tenant_id"),
                @Index(name = "idx_audit_eng_project",       columnList = "project_id"),
                @Index(name = "idx_audit_eng_proj_instance", columnList = "project_instance_id"),
                @Index(name = "idx_audit_eng_status",        columnList = "status"),
                @Index(name = "idx_audit_eng_workflow",      columnList = "workflow_instance_id"),
                @Index(name = "idx_audit_eng_type",          columnList = "audit_type")
        }
)
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class AuditEngagement extends TenantAwareEntity {

    @Column(name = "engagement_ref", length = 30)
    private String engagementRef;

    @Column(name = "project_id", nullable = true)
    private Long projectId;

    /**
     * References the frozen AuditProjectInstance snapshot.
     * Set by AuditEngagementService.create() on first engagement — subsequent
     * engagements in the same project share the same instance.
     * Plain Long — no @ManyToOne, no join, no cascade. Audit trail only.
     */
    @Column(name = "project_instance_id")
    private Long projectInstanceId;

    @Column(name = "name", nullable = false, length = 500)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** Original template used — reference only, not joined at runtime */
    @Column(name = "template_id")
    private Long templateId;

    @Column(name = "framework_ref", length = 100)
    private String frameworkRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "audit_type", nullable = false, length = 20)
    @Builder.Default
    private AuditTemplate.AuditType auditType = AuditTemplate.AuditType.INTERNAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private Status status = Status.PLANNING;

    /** Lead auditor (org side) */
    @Column(name = "lead_auditor_id")
    private Long leadAuditorId;

    /** CAE or engagement owner */
    @Column(name = "owner_id")
    private Long ownerId;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "planned_start")
    private LocalDateTime plannedStart;

    @Column(name = "planned_end")
    private LocalDateTime plannedEnd;

    @Column(name = "actual_start")
    private LocalDateTime actualStart;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "submitted_by")
    private Long submittedBy;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    // ── Scoring ───────────────────────────────────────────────────────────────

    @Column(name = "total_controls")
    @Builder.Default
    private Integer totalControls = 0;

    @Column(name = "tested_controls")
    @Builder.Default
    private Integer testedControls = 0;

    @Column(name = "passed_controls")
    @Builder.Default
    private Integer passedControls = 0;

    @Column(name = "failed_controls")
    @Builder.Default
    private Integer failedControls = 0;

    @Column(name = "not_applicable_controls")
    @Builder.Default
    private Integer notApplicableControls = 0;

    // ── Report ────────────────────────────────────────────────────────────────

    @Column(name = "report_version")
    @Builder.Default
    private Integer reportVersion = 0;

    @Column(name = "report_generated_at")
    private LocalDateTime reportGeneratedAt;

    @Column(name = "report_generated_by")
    private Long reportGeneratedBy;

    @Column(name = "report_url", columnDefinition = "TEXT")
    private String reportUrl;

    @Column(name = "overall_rating", length = 30)
    private String overallRating;  // EFFECTIVE | PARTIALLY_EFFECTIVE | INEFFECTIVE | NOT_RATED

    @Column(name = "open_finding_count")
    @Builder.Default
    private Integer openFindingCount = 0;

    // ── Workflow ──────────────────────────────────────────────────────────────

    @Column(name = "workflow_instance_id")
    private Long workflowInstanceId;

    // ── UI keys ───────────────────────────────────────────────────────────────

    @Column(name = "list_screen_key", length = 100)
    @Builder.Default
    private String listScreenKey = "audit_engagement_list";

    @Column(name = "detail_screen_key", length = 100)
    @Builder.Default
    private String detailScreenKey = "audit_engagement_detail";

    public enum Status {
        PLANNING,
        FIELDWORK,
        EVIDENCE_REVIEW,
        DRAFT_REPORT,
        MANAGEMENT_RESPONSE,
        FINAL_REPORT,
        CLOSED,
        CANCELLED
    }
}