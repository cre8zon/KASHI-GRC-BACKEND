package com.kashi.grc.workflow.domain;

import com.kashi.grc.common.domain.BaseEntity;
import com.kashi.grc.workflow.enums.ApprovalType;
import com.kashi.grc.workflow.enums.ActorResolution;
import com.kashi.grc.workflow.enums.AssignerResolution;
import com.kashi.grc.workflow.enums.StepAction;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "workflow_steps",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_workflow_step_order",
                columnNames = {"workflow_id", "step_order"}))
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class WorkflowStep extends BaseEntity {

    @Column(name = "workflow_id", nullable = false)
    private Long workflowId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "step_order", nullable = false)
    private Integer stepOrder;

    @Column(name = "side", length = 50)
    private String side;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_type", nullable = false, length = 50)
    @Builder.Default
    private ApprovalType approvalType = ApprovalType.ANY_ONE;

    @Column(name = "min_approvals_required")
    @Builder.Default
    private Integer minApprovalsRequired = 1;

    @Column(name = "is_parallel", nullable = false)
    @Builder.Default
    private boolean isParallel = false;

    @Column(name = "is_optional", nullable = false)
    @Builder.Default
    private boolean isOptional = false;

    @Column(name = "sla_hours")
    private Integer slaHours;

    @Column(name = "automated_action", length = 100)
    private String automatedAction;

    /**
     * How to assign this step when it becomes active.
     * POOL           - shared queue, first actor-role holder claims it
     * PUSH_TO_ROLES  - tasks pushed to assignerRole holders, they delegate to actor
     * PREVIOUS_ACTOR - whoever approved the previous step assigns this one
     * INITIATOR      - workflow creator assigns
     * null           - engine infers (backward compatible)
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "assigner_resolution", length = 50)
    private AssignerResolution assignerResolution;

    /**
     * Controls how ACTOR task recipients are resolved when this step activates.
     * ROLE_BASED (default) — all role holders get a task.
     * ASSIGNMENT_SCOPED — WorkflowActorResolver SPI returns only the assigned users.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "actor_resolution", length = 50)
    @Builder.Default
    private ActorResolution actorResolution = ActorResolution.ROLE_BASED;

    /**
     * If true, the resolved assigner can redirect the task to a specific person.
     * Defaults true for flexibility.
     */
    @Column(name = "allow_override", nullable = false)
    @Builder.Default
    private boolean allowOverride = true;

    /**
     * What kind of work the ACTOR does on this step.
     * Drives frontend routing: (entityType + stepSide + stepAction) → URL.
     * ASSIGN | FILL | REVIEW | APPROVE | ACKNOWLEDGE | EVALUATE | GENERATE | CUSTOM
     * null for SYSTEM steps (automated, no actor UI needed).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "step_action", length = 50)
    private StepAction stepAction;

    /**
     * Navigation key for ACTOR tasks — identifies which page renders the actor's work.
     * Must match a navKey in the ui_navigation table.
     * e.g. "vendor_assessment_fill", "vendor_assessment_review", "issue_remediate"
     *
     * Set by Platform Admin when building the blueprint. Null for SYSTEM steps.
     */
    @Column(name = "nav_key", length = 100)
    private String navKey;

    /**
     * Navigation key for ASSIGNER tasks — identifies which page the coordinator uses.
     * Must match a navKey in the ui_navigation table.
     * e.g. "vendor_assessment_assign", "audit_engagement_assign"
     *
     * Separate from navKey so actor and assigner can land on different pages.
     * If null and an ASSIGNER task exists, the inbox shows inline action buttons only.
     * Null for SYSTEM steps and steps with no assignerRoles.
     */
    @Column(name = "assigner_nav_key", length = 100)
    private String assignerNavKey;

    // ── NEW FIELD ─────────────────────────────────────────────────────────────

    /**
     * Optional UI restrictions for actors on this step.
     * Platform Admin sets this per step in the WorkflowBlueprintDesigner UI.
     * Snapshotted into StepInstance.snapUiOverrideJson at instance creation time
     * to preserve blueprint isolation — running instances never re-read this.
     *
     * JSON format (all keys optional):
     * {
     *   "visibleTabs":    ["overview", "evidence"],
     *   "hiddenTabs":     ["audit_trail"],
     *   "editableFields": ["mitigationPlan", "residualRisk"],
     *   "readOnlyFields": ["inherentRisk", "riskOwner"],
     *   "hiddenFields":   ["internalNotes"],
     *   "availableActions": ["APPROVE", "REJECT", "SEND_BACK"]
     * }
     *
     * Step override can only RESTRICT what the role allows — never expand beyond
     * the user's role ceiling. Enforced in WorkflowAccessService.mergeContext().
     */
    @Column(name = "step_ui_override_json", columnDefinition = "JSON")
    private String stepUiOverrideJson;

    /**
     * SOD rules for this step — JSON array of rule objects.
     * Evaluated at actor resolution time by WorkflowEngineService.
     *
     * Supported rule types:
     *   EXCLUDE_ENTITY_OWNER   — entity owner cannot act (cannot validate own work)
     *   EXCLUDE_PREVIOUS_ACTOR — whoever acted on the previous step cannot act here
     *   EXCLUDE_ROLE           — users with a specific roleId cannot act here
     *
     * Example:
     *   [{"type":"EXCLUDE_ENTITY_OWNER","reason":"Cannot validate own remediation"},
     *    {"type":"EXCLUDE_PREVIOUS_ACTOR","reason":"Four-eyes principle"}]
     */
    @Column(name = "sod_rules_json", columnDefinition = "JSON")
    private String sodRulesJson;

    // ── Migration SQL ─────────────────────────────────────────────────────────
    // ALTER TABLE workflow_steps
    //   ADD COLUMN step_ui_override_json JSON NULL
    //   COMMENT 'UI restrictions for actors on this step: visibleTabs, editableFields, availableActions';

    @Column(name = "auto_approve_assigner_on_fill", nullable = false)
    @Builder.Default
    private boolean autoApproveAssignerOnFill = false;

    /**
     * When true: as soon as an ACTOR submits a FILL step form (i.e. their task
     * is approved), the engine immediately auto-approves ALL remaining PENDING
     * tasks on this step and completes it — no inbox action needed.
     *
     * Use this for any FILL step where form submission IS the approval:
     *   - Issue creation / triage (step 1 of Issue Remediation Lifecycle)
     *   - Policy draft submission
     *   - Evidence upload confirmation
     *   - Any form where "submit" means "done"
     *
     * Works with ANY_ONE approval_type: first actor to submit triggers completion.
     * Works with ALL approval_type: every actor must submit, last one triggers completion.
     *
     * Set via workflow_steps.auto_complete_actor_on_submit = 1 in DB.
     */
    @Column(name = "auto_complete_actor_on_submit", nullable = false)
    @Builder.Default
    private boolean autoCompleteActorOnSubmit = false;
}