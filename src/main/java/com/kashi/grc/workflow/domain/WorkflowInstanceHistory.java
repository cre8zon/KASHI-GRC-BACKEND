package com.kashi.grc.workflow.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Append-only audit trail — every significant state change in a WorkflowInstance.
 * NEVER updated or deleted.
 *
 * tenant_id included for efficient org-scoped compliance reports.
 * stepName and stepOrder are snapshotted at record time so history stays
 * accurate even after workflow versioning.
 *
 * Event types: WORKFLOW_STARTED, STEP_STARTED, STEP_APPROVED, STEP_REJECTED,
 * STEP_SENT_BACK, TASK_ASSIGNED, TASK_REASSIGNED, TASK_DELEGATED, TASK_ESCALATED,
 * WORKFLOW_COMPLETED, WORKFLOW_REJECTED, WORKFLOW_CANCELLED, WORKFLOW_ON_HOLD,
 * WORKFLOW_RESUMED, WORKFLOW_WITHDRAWN, COMMENT_ADDED.
 */
@Entity
@Table(name = "workflow_instance_history")
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class WorkflowInstanceHistory extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "workflow_instance_id", nullable = false)
    private Long workflowInstanceId;

    @Column(name = "step_instance_id")
    private Long stepInstanceId;

    @Column(name = "task_instance_id")
    private Long taskInstanceId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "from_status", length = 100)
    private String fromStatus;

    @Column(name = "to_status", length = 100)
    private String toStatus;

    @Column(name = "performed_by", nullable = false)
    private Long performedBy;

    @Column(name = "performed_at", nullable = false)
    private LocalDateTime performedAt;

    @Column(name = "remarks", columnDefinition = "TEXT")
    private String remarks;

    /** Snapshotted at record time — survives workflow definition changes */
    @Column(name = "step_id")
    private Long stepId;

    @Column(name = "step_name", length = 255)
    private String stepName;

    @Column(name = "step_order")
    private Integer stepOrder;
}
