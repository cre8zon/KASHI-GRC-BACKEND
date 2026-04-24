package com.kashi.grc.workflow.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import com.kashi.grc.workflow.enums.WorkflowStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A running execution of a global Workflow blueprint for a specific org entity.
 *
 * DESIGN:
 *   Workflow (blueprint) → tenant_id = null, owned by Platform Admin, shared globally.
 *   WorkflowInstance     → tenant_id = X,    owned by the org that started it.
 *
 * One blueprint can have unlimited instances across all orgs and across time.
 * Each instance is completely isolated — orgs cannot see each other's instances.
 * The blueprint is never modified when an instance runs.
 *
 * Only one active instance (IN_PROGRESS / PENDING / ON_HOLD) per entity at a time.
 * currentStepId is null when the workflow reaches a terminal state.
 *
 * Status lifecycle:
 *   PENDING → IN_PROGRESS → COMPLETED
 *   IN_PROGRESS → REJECTED    (any user rejects)
 *   IN_PROGRESS → ON_HOLD → IN_PROGRESS  (hold/resume)
 *   IN_PROGRESS → CANCELLED   (admin cancel or WITHDRAW action)
 */
@Entity
@Table(name = "workflow_instances")
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class WorkflowInstance extends TenantAwareEntity {

    /** FK to global Workflow blueprint — blueprint never changes */
    @Column(name = "workflow_id", nullable = false)
    private Long workflowId;

    /** e.g. VENDOR, AUDIT, CONTRACT — matches the workflow's entityType */
    @Column(name = "entity_type", nullable = false, length = 100)
    private String entityType;

    /** PK of the org's business entity being processed */
    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    /**
     * FK to step_instances.id — points to the RUNTIME StepInstance currently active.
     * NOT a FK to workflow_steps (blueprint) — instances are independent of the blueprint.
     *
     * Updated on every step transition to the newly created StepInstance.
     * Null when the workflow reaches a terminal state (COMPLETED/CANCELLED/REJECTED).
     *
     * DB column: current_step_instance_id → step_instances(id) ON DELETE SET NULL
     */
    @Column(name = "current_step_instance_id")
    private Long currentStepId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    @Builder.Default
    private WorkflowStatus status = WorkflowStatus.IN_PROGRESS;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /** User who triggered startWorkflow() */
    @Column(name = "initiated_by", nullable = false)
    private Long initiatedBy;

    /** LOW | MEDIUM | HIGH | CRITICAL — CRITICAL set automatically by ESCALATE action */
    @Column(name = "priority", length = 20)
    @Builder.Default
    private String priority = "MEDIUM";

    @Column(name = "due_date")
    private LocalDateTime dueDate;

    @Column(name = "remarks", columnDefinition = "TEXT")
    private String remarks;

}