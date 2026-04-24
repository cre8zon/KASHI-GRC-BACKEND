package com.kashi.grc.workflow.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// ================================================================
// TaskSectionItemAssignment.java  (Case 3)
// ================================================================
@Entity
@Table(name = "task_section_item_assignments")
@Getter
@Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class TaskSectionItemAssignment extends BaseEntity {

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Column(name = "task_instance_id", nullable = false)
    private Long taskInstanceId;

    @Column(name = "section_key", nullable = false, length = 100)
    private String sectionKey;

    @Column(name = "assigned_to_user_id", nullable = false)
    private Long assignedToUserId;

    @Column(name = "assigned_by_user_id", nullable = false)
    private Long assignedByUserId;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "assigned_at")
    private LocalDateTime assignedAt;
}