package com.kashi.grc.workflow.domain;

import com.kashi.grc.common.domain.BaseEntity;
import com.kashi.grc.workflow.enums.ActionType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Immutable audit log — one row per action on a TaskInstance. NEVER updated.
 *
 * tenant_id included for efficient org-scoped audit queries without joins.
 * workflowInstanceId and stepInstanceId denormalized for same reason.
 *
 * Two-layer audit:
 *   WorkflowTaskAction   — granular, task-level action log
 *   WorkflowInstanceHistory — higher-level workflow/step event log
 */
@Entity
@Table(name = "workflow_task_actions")
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class WorkflowTaskAction extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "task_instance_id", nullable = false)
    private Long taskInstanceId;

    @Column(name = "workflow_instance_id", nullable = false)
    private Long workflowInstanceId;

    @Column(name = "step_instance_id", nullable = false)
    private Long stepInstanceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 50)
    private ActionType actionType;

    @Column(name = "performed_by", nullable = false)
    private Long performedBy;

    @Column(name = "performed_at")
    private LocalDateTime performedAt;

    @Column(name = "remarks", columnDefinition = "TEXT")
    private String remarks;

    @Column(name = "target_user_id")
    private Long targetUserId;

    @Column(name = "target_step_id")
    private Long targetStepId;
}
