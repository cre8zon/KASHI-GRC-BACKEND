package com.kashi.grc.workflow.domain;

import com.kashi.grc.common.domain.BaseEntity;
import com.kashi.grc.workflow.enums.ApprovalType;
import com.kashi.grc.workflow.enums.ActorResolution;
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

    @Column(name = "snap_actor_resolution", length = 50)
    private ActorResolution snapActorResolution;

    @Column(name = "snap_allow_override")
    private Boolean snapAllowOverride;

    @Enumerated(EnumType.STRING)
    @Column(name = "snap_step_action", length = 50)
    private StepAction snapStepAction;

    /** Snapshot of WorkflowStep.assignableSide — which side appears in the assignment dropdown */
    @Column(name = "snap_assignable_side", length = 50)
    private String snapAssignableSide;

    /** Snapshot of WorkflowStep.assignableRoleId — which role filters the assignment dropdown */
    @Column(name = "snap_assignable_role_id")
    private Long snapAssignableRoleId;

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

    // ── NEW FIELD ─────────────────────────────────────────────────────────────

    /**
     * Snapshot of WorkflowStep.stepUiOverrideJson — UI restrictions for actors on this step.
     * Copied at step instance creation; never mutated after that (blueprint isolation).
     *
     * JSON format (all keys optional — null means no restriction on that dimension):
     * {
     *   "visibleTabs":    ["overview", "evidence"],
     *   "hiddenTabs":     ["audit_trail"],
     *   "editableFields": ["mitigationPlan", "residualRisk"],
     *   "readOnlyFields": ["inherentRisk", "riskOwner"],
     *   "hiddenFields":   ["internalNotes"],
     *   "availableActions": ["APPROVE", "REJECT", "SEND_BACK"]
     * }
     *
     * Consumed by WorkflowAccessService.resolve() to populate AccessContext.
     * Step override can only RESTRICT what the role allows — never expand it.
     */
    @Column(name = "snap_auto_approve_assigner_on_fill")
    private Boolean snapAutoApproveAssignerOnFill;

    /** Snapshotted from WorkflowStep.autoCompleteActorOnSubmit at step instance creation. */
    @Column(name = "snap_auto_complete_actor_on_submit")
    private Boolean snapAutoCompleteActorOnSubmit;

    @Column(name = "snap_ui_override_json", columnDefinition = "JSON")
    private String snapUiOverrideJson;

    /**
     * Snapshot of WorkflowStep.sodRulesJson at the time this step instance was created.
     * Blueprint changes after step creation do not affect in-flight instances.
     */
    @Column(name = "snap_sod_rules_json", columnDefinition = "JSON")
    private String snapSodRulesJson;

    // ── Migration SQL ─────────────────────────────────────────────────────────
    // ALTER TABLE step_instances
    //   ADD COLUMN snap_ui_override_json JSON NULL
    //   COMMENT 'Snapshot of workflow_steps.step_ui_override_json at instance creation';
}