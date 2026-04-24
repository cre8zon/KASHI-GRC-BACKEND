package com.kashi.grc.workflow.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// ================================================================
// TaskSectionItem.java  (Case 3)
// ================================================================
@Entity
@Table(name = "task_section_items",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_tsi_task_ref",
                columnNames = {"task_instance_id", "section_key", "item_ref_type", "item_ref_id"}))
@Getter
@Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class TaskSectionItem extends BaseEntity {

    @Column(name = "task_instance_id", nullable = false)
    private Long taskInstanceId;

    @Column(name = "step_instance_id", nullable = false)
    private Long stepInstanceId;

    @Column(name = "workflow_instance_id", nullable = false)
    private Long workflowInstanceId;

    @Column(name = "section_key", nullable = false, length = 100)
    private String sectionKey;

    @Column(name = "item_ref_type", nullable = false, length = 100)
    private String itemRefType;

    @Column(name = "item_ref_id", nullable = false)
    private Long itemRefId;

    @Column(name = "item_label", length = 500)
    private String itemLabel;

    @Column(name = "status", nullable = false, length = 50)
    @Builder.Default
    private String status = "PENDING";
}