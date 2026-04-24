package com.kashi.grc.workflow.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * Actor role for a workflow step — who DOES the work.
 *
 * RENAMED: workflow_step_roles → workflow_step_actor_roles
 * for consistency with workflow_step_assigner_roles.
 *
 * Role-to-user resolution happens at runtime by the engine (dbRepository.findUserIdsByRoleAndTenant).
 * Multiple roles can be assigned to one step — all matching users receive tasks (or are pooled).
 */
@Entity
@Table(name = "workflow_step_actor_roles")
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class WorkflowStepRole extends BaseEntity {

    @Column(name = "step_id", nullable = false)
    private Long stepId;

    @Column(name = "role_id", nullable = false)
    private Long roleId;
}