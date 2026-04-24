package com.kashi.grc.workflow.domain;

import com.kashi.grc.common.domain.BaseEntity;
import com.kashi.grc.workflow.enums.ApprovalType;
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
}