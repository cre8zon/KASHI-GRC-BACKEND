package com.kashi.grc.workflow.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

// ================================================================
// TaskSectionCompletion.java
//
// RUNTIME + SNAPSHOT. One row per (taskInstance, sectionKey).
//
// snap_* columns are copied from WorkflowStepSection at the moment
// snapshotSectionsForTask() runs. After that:
//   - Runtime logic reads ONLY snap_* columns
//   - workflow_step_sections is NEVER queried again for this task
//   - Blueprint edits have zero effect on this running task
//
// NEW snap fields: snapSectionScreenKey, snapItemScreenKey, snapItemRefType,
//                  snapSectionUiJson, snapItemUiJson
//
// MIGRATION (run once):
//   ALTER TABLE task_section_completions
//     ADD COLUMN snap_section_screen_key VARCHAR(100) NULL,
//     ADD COLUMN snap_item_screen_key    VARCHAR(100) NULL,
//     ADD COLUMN snap_item_ref_type      VARCHAR(100) NULL,
//     ADD COLUMN snap_section_ui_json    JSON         NULL,
//     ADD COLUMN snap_item_ui_json       JSON         NULL,
//     ADD COLUMN remarks                 TEXT         NULL;
// ================================================================
@Entity
@Table(name = "task_section_completions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_tsc_task_key",
                columnNames = {"task_instance_id", "snap_section_key"}))
@Getter
@Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class TaskSectionCompletion extends BaseEntity {

    @Column(name = "task_instance_id", nullable = false)
    private Long taskInstanceId;

    @Column(name = "step_instance_id", nullable = false)
    private Long stepInstanceId;

    @Column(name = "workflow_instance_id", nullable = false)
    private Long workflowInstanceId;

    // ── Blueprint snapshot ────────────────────────────────────────
    // Written once at snapshotSectionsForTask(). Never mutated after.

    @Column(name = "snap_section_key", nullable = false, length = 100)
    private String snapSectionKey;

    @Column(name = "snap_section_order", nullable = false)
    @Builder.Default
    private Integer snapSectionOrder = 0;

    @Column(name = "snap_label", nullable = false, length = 255)
    private String snapLabel;

    @Column(name = "snap_description", length = 1000)
    private String snapDescription;

    @Column(name = "snap_required", nullable = false)
    @Builder.Default
    private boolean snapRequired = true;

    /** Snapshot of the completion event key — matched against incoming TaskSectionEvents */
    @Column(name = "snap_completion_event", nullable = false, length = 100)
    private String snapCompletionEvent;

    @Column(name = "snap_requires_assignment", nullable = false)
    @Builder.Default
    private boolean snapRequiresAssignment = false;

    @Column(name = "snap_tracks_items", nullable = false)
    @Builder.Default
    private boolean snapTracksItems = false;

    // ── NEW snapshot UI fields ────────────────────────────────────

    @Column(name = "snap_section_screen_key", length = 100)
    private String snapSectionScreenKey;

    @Column(name = "snap_item_screen_key", length = 100)
    private String snapItemScreenKey;

    @Column(name = "snap_item_ref_type", length = 100)
    private String snapItemRefType;

    @Column(name = "snap_section_ui_json", columnDefinition = "JSON")
    private String snapSectionUiJson;

    @Column(name = "snap_item_ui_json", columnDefinition = "JSON")
    private String snapItemUiJson;

    // ── Runtime state ─────────────────────────────────────────────
    // Mutated by TaskSectionCompletionService when events arrive.

    @Column(name = "completed", nullable = false)
    @Builder.Default
    private boolean completed = false;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "completed_by")
    private Long completedBy;

    @Column(name = "artifact_type", length = 100)
    private String artifactType;

    @Column(name = "artifact_id")
    private Long artifactId;

    /**
     * Optional notes recorded when a section is completed.
     * Set by markAllSectionsCompleteForTask and onSectionEvent — passed through
     * from TaskSectionEvent.remarks() and VendorAssessmentFillPage submission.
     */
    @Column(name = "remarks", columnDefinition = "TEXT")
    private String remarks;
}