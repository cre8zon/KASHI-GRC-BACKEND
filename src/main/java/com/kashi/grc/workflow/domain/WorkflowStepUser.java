package com.kashi.grc.workflow.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/** Direct user assignment for a step — no role resolution needed at runtime. */
@Entity
@Table(name = "workflow_step_users")
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class WorkflowStepUser extends BaseEntity {

    @Column(name = "step_id", nullable = false)
    private Long stepId;

    @Column(name = "user_id", nullable = false)
    private Long userId;
}
