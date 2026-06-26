package com.kashi.grc.audit.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * AuditProjectInstance — frozen snapshot of AuditProject at the moment
 * the first engagement is started within a project.
 *
 * ── FULL ISOLATION STACK ─────────────────────────────────────────────────────
 *
 *   AuditProject (live, mutable)
 *       ↓ snapshotted on first engagement start → ONE instance per project
 *   AuditProjectInstance (frozen)                              ← THIS ENTITY
 *       ↓ referenced by every engagement in the project
 *   AuditEngagement (live)
 *       ↓ snapshotted on creation
 *   AuditEngagementTemplateInstance (frozen)
 *   AuditSectionInstance tree (frozen)
 *   AuditControlInstance (frozen)
 *
 * ── CREATION RULE ────────────────────────────────────────────────────────────
 * AuditEngagementService.create():
 *   1. Check if AuditProjectInstance already exists for this project
 *   2. If NOT → create it now (snapshot the project)
 *   3. Set engagement.projectInstanceId = instance.id
 *   4. All subsequent engagements in the same project reference the SAME instance
 *
 * ── ZERO FK RULE ─────────────────────────────────────────────────────────────
 * originalProjectId = plain Long, audit trail only, never used in runtime joins.
 * AuditProject edits (rename, owner change, date extension) never affect this.
 */
@Entity
@Table(name = "audit_project_instances",
        indexes = {
                @Index(name = "idx_audit_proj_inst_tenant",  columnList = "tenant_id"),
                @Index(name = "idx_audit_proj_inst_project", columnList = "original_project_id"),
                @Index(name = "idx_audit_proj_inst_wf",      columnList = "workflow_instance_id")
        }
)
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class AuditProjectInstance extends TenantAwareEntity {

    // ── Audit trail ───────────────────────────────────────────────────────────

    /** Plain Long — audit trail only. NEVER used in runtime joins. */
    @Column(name = "original_project_id", nullable = false)
    private Long originalProjectId;

    // ── Snapshot fields — frozen at first engagement start ────────────────────

    @Column(name = "project_name_snapshot", nullable = false, length = 500)
    private String projectNameSnapshot;

    @Column(name = "project_ref_snapshot", length = 30)
    private String projectRefSnapshot;

    /**
     * Unique ref for THIS running instance — format: {projectRef}-RUN-{seq}
     * e.g. PROJ-2026-0001-RUN-1 (first run), PROJ-2026-0001-RUN-2 (second annual run)
     * Distinct from projectRefSnapshot which is the library project ref.
     */
    @Column(name = "instance_ref", length = 50)
    private String instanceRef;

    @Column(name = "description_snapshot", columnDefinition = "TEXT")
    private String descriptionSnapshot;

    @Column(name = "owner_id_snapshot")
    private Long ownerIdSnapshot;

    @Column(name = "planned_start_snapshot")
    private LocalDate plannedStartSnapshot;

    @Column(name = "planned_end_snapshot")
    private LocalDate plannedEndSnapshot;

    /** Project status at snapshot time */
    @Column(name = "status_at_snapshot", length = 20)
    private String statusAtSnapshot;

    /** Number of templates planned at snapshot time — captures scope intent */
    @Column(name = "planned_template_count")
    @Builder.Default
    private Integer plannedTemplateCount = 0;

    @Column(name = "snapshotted_at", nullable = false)
    private LocalDateTime snapshottedAt;

    @Column(name = "snapshotted_by")
    private Long snapshottedBy;

    // ── Workflow ───────────────────────────────────────────────────────────────

    @Column(name = "workflow_instance_id")
    private Long workflowInstanceId;

    // ── Step 11: Cross-Framework Consolidation ────────────────────────────────
    @Column(name = "cross_framework_notes", columnDefinition = "TEXT")
    private String crossFrameworkNotes;

    @Column(name = "programme_risk", length = 30)
    private String programmeRisk;          // LOW | MEDIUM | HIGH | CRITICAL

    // ── Step 12: Management Response ──────────────────────────────────────────
    @Column(name = "management_response", columnDefinition = "TEXT")
    private String managementResponse;

    @Column(name = "acceptance_of_findings", length = 30)
    private String acceptanceOfFindings;   // ACCEPTED | PARTIAL | REJECTED

    @Column(name = "corrective_actions", columnDefinition = "TEXT")
    private String correctiveActions;

    @Column(name = "committed_closure_date")
    private LocalDate committedClosureDate;

    // ── Step 13: Executive Sign-off ───────────────────────────────────────────
    @Column(name = "executive_sign_off", columnDefinition = "TEXT")
    private String executiveSignOff;

    @Column(name = "programme_outcome", length = 30)
    private String programmeOutcome;       // CLEAN | QUALIFIED | ADVERSE

    @Column(name = "closure_statement", columnDefinition = "TEXT")
    private String closureStatement;

    @Column(name = "next_audit_due")
    private LocalDate nextAuditDue;

    @Column(name = "signed_off_by")
    private Long signedOffBy;

    @Column(name = "signed_off_at")
    private LocalDateTime signedOffAt;

    /**
     * Live status of this RUNNING project instance — distinct from
     * statusAtSnapshot (which is frozen at creation time).
     * PLANNING  → instance row created but workflow 16 not yet started
     *             (e.g. ensureProjectInstance() ran via an earlier engagement
     *             start, but POST /project-instances full start hasn't fired)
     * IN_PROGRESS → workflow 16 started, governs all engagements
     * COMPLETED   → workflow 16 reached Auto-Close (Step 11 signed off)
     * Drives audit_project_status badge on audit_project_list/_detail.
     */
    @Column(name = "status", length = 20)
    @Builder.Default
    private String status = "PLANNING";
}