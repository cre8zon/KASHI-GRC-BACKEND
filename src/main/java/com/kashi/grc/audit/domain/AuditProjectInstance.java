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
}