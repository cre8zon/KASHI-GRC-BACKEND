package com.kashi.grc.workflow.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * Observer role for a workflow step.
 *
 * Observers have READ-ONLY access to the step's artifact without being
 * assigned a task. They cannot act on the step — they can only view it.
 *
 * Use cases:
 *   - Compliance officers who need visibility into all vendor assessments
 *     regardless of which step is active
 *   - Audit managers monitoring an engagement their team is working on
 *   - Risk officers watching a risk review process
 *   - Executive dashboards showing progress without task assignment
 *
 * Observer roles are independent of step.side — an ORG role can observe
 * a VENDOR step and vice versa.
 *
 * Access control:
 *   The artifact access guard (assertUserHasAccess) checks:
 *     1. User has an active ACTOR task → full access
 *     2. User has an active ASSIGNER task → full access (to assign)
 *     3. User holds an observer role for the current step → read-only access
 *     4. User holds a org/system role → always read-only access
 *     5. Else → 403
 */
@Entity
@Table(name = "workflow_step_observer_roles")
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class WorkflowStepObserverRole extends BaseEntity {

    @Column(name = "step_id", nullable = false)
    private Long stepId;

    /**
     * Role ID from the roles table.
     * Any side — not constrained to step.side.
     */
    @Column(name = "role_id", nullable = false)
    private Long roleId;
}