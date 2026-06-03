package com.kashi.grc.workflow.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

// ================================================================
// TaskSectionItem.java  (Case 3)
//
// Each row represents one tracked item within a compound task section.
// e.g. one CONTROL, one FINDING, one QUESTION_RESPONSE.
//
// NEW: assignedToUserId — the user currently assigned to work on this item.
// Set by TaskSectionCompletionService.assignItems().
// Displayed in SectionItemResponse.assignedToUserId for item card rendering.
//
// MIGRATION (run once):
//   ALTER TABLE task_section_items
//     ADD COLUMN assigned_to_user_id BIGINT NULL;
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

    /**
     * User currently assigned to work on this specific item.
     * Null = unassigned (visible to the section ACTOR but not delegated yet).
     * Set by TaskSectionCompletionService.assignItems() when the ACTOR delegates.
     * Displayed in TaskSectionProgressResponse.SectionItemResponse for item cards.
     */
    @Column(name = "assigned_to_user_id")
    private Long assignedToUserId;
}