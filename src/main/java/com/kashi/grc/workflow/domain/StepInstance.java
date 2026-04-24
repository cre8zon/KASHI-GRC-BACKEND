package com.kashi.grc.workflow.domain;

import com.kashi.grc.common.domain.BaseEntity;
import com.kashi.grc.workflow.enums.ApprovalType;
import com.kashi.grc.workflow.enums.AssignerResolution;
import com.kashi.grc.workflow.enums.StepAction;
import com.kashi.grc.workflow.enums.StepStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Runtime execution record of one WorkflowStep within a WorkflowInstance.
 *
 * ── BLUEPRINT SNAPSHOT ───────────────────────────────────────────────────────
 * All snap_* fields are copied from WorkflowStep at the moment this StepInstance
 * is created. After that, the running instance is completely isolated — blueprint
 * edits never affect in-flight steps. All routing, approval logic, SLA computation,
 * and task creation reads from snap_* fields, never from workflow_steps.
 *
 * step_id is kept as a soft reference for audit/debugging only.
 */
@Entity
@Table(name = "step_instances")
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class StepInstance extends BaseEntity {

    @Column(name = "workflow_instance_id", nullable = false)
    private Long workflowInstanceId;

    /** Soft reference to the blueprint step — for audit only, not for routing */
    @Column(name = "step_id")
    private Long stepId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    @Builder.Default
    private StepStatus status = StepStatus.IN_PROGRESS;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "sla_due_at")
    private LocalDateTime slaDueAt;

    /** Starts at 1; increments each time this step is revisited via send-back */
    @Column(name = "iteration_count", nullable = false)
    @Builder.Default
    private Integer iterationCount = 1;

    @Column(name = "remarks", columnDefinition = "TEXT")
    private String remarks;

    // ── Blueprint snapshot fields ─────────────────────────────────────────────
    // Copied from WorkflowStep at creation time. Never mutated after that.
    // All engine logic reads from these — never from workflow_steps table.

    @Column(name = "snap_name", length = 255)
    private String snapName;

    @Column(name = "snap_description", columnDefinition = "TEXT")
    private String snapDescription;

    @Column(name = "snap_step_order")
    private Integer snapStepOrder;

    @Column(name = "snap_side", length = 50)
    private String snapSide;

    @Enumerated(EnumType.STRING)
    @Column(name = "snap_approval_type", length = 50)
    private ApprovalType snapApprovalType;

    @Column(name = "snap_min_approvals")
    private Integer snapMinApprovals;

    @Column(name = "snap_is_parallel")
    private Boolean snapIsParallel;

    @Column(name = "snap_is_optional")
    private Boolean snapIsOptional;

    @Column(name = "snap_sla_hours")
    private Integer snapSlaHours;

    @Column(name = "snap_automated_action", length = 100)
    private String snapAutomatedAction;

    @Enumerated(EnumType.STRING)
    @Column(name = "snap_assigner_resolution", length = 50)
    private AssignerResolution snapAssignerResolution;

    @Column(name = "snap_allow_override")
    private Boolean snapAllowOverride;

    @Enumerated(EnumType.STRING)
    @Column(name = "snap_step_action", length = 50)
    private StepAction snapStepAction;

    /**
     * Snapshot of WorkflowStep.navKey — the nav table key for ACTOR tasks.
     * Carried through to TaskInstanceResponse so the frontend resolves the route
     * from the nav table for actors (FILL / REVIEW / GENERATE / etc.).
     * Null for SYSTEM steps.
     */
    @Column(name = "snap_nav_key", length = 100)
    private String snapNavKey;

    /**
     * Snapshot of WorkflowStep.assignerNavKey — the nav table key for ASSIGNER tasks.
     * Carried through to TaskInstanceResponse so coordinators also get a proper route
     * instead of falling back to inline approve/reject buttons.
     * Null for SYSTEM steps and steps with no assignerRoles.
     */
    @Column(name = "snap_assigner_nav_key", length = 100)
    private String snapAssignerNavKey;
}