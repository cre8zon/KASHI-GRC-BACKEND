package com.kashi.grc.workflow.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
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
// This is identical in design to StepInstance.snap_* for WorkflowStep.
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

    @Column(name = "remarks", columnDefinition = "TEXT")
    private String remarks;
}


