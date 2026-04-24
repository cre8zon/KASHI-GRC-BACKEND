package com.kashi.grc.workflow.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// ================================================================
// TaskSectionAssignment.java  (Case 2)
// ================================================================
@Entity
@Table(name = "task_section_assignments")
@Getter
@Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class TaskSectionAssignment extends BaseEntity {

    @Column(name = "task_instance_id", nullable = false)
    private Long taskInstanceId;

    @Column(name = "step_instance_id", nullable = false)
    private Long stepInstanceId;

    @Column(name = "workflow_instance_id", nullable = false)
    private Long workflowInstanceId;

    @Column(name = "section_key", nullable = false, length = 100)
    private String sectionKey;

    @Column(name = "assigned_to_user_id", nullable = false)
    private Long assignedToUserId;

    @Column(name = "assigned_by_user_id", nullable = false)
    private Long assignedByUserId;

    @Column(name = "sub_task_instance_id")
    private Long subTaskInstanceId;

    @Column(name = "status", nullable = false, length = 50)
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "assigned_at")
    private LocalDateTime assignedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}


