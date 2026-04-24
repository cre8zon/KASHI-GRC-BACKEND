package com.kashi.grc.workflow.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

// ================================================================
// WorkflowStepSection.java
//
// BLUEPRINT ONLY. Written by Platform Admin.
// Read exactly once — inside snapshotSectionsForTask() when a step
// becomes active. Never read again during runtime.
//
// Any edit here (add section, change label, change completion_event,
// toggle required) only affects future workflow instances.
// Running instances are 100% isolated via TaskSectionCompletion.snap_*.
// ================================================================
@Entity
@Table(name = "workflow_step_sections",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_wss_step_key",
                columnNames = {"step_id", "section_key"}))
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class WorkflowStepSection extends BaseEntity {

    @Column(name = "step_id", nullable = false)
    private Long stepId;

    @Column(name = "section_key", nullable = false, length = 100)
    private String sectionKey;

    @Column(name = "section_order", nullable = false)
    @Builder.Default
    private Integer sectionOrder = 0;

    @Column(name = "label", nullable = false, length = 255)
    private String label;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "required", nullable = false)
    @Builder.Default
    private boolean required = true;

    /**
     * The TaskSectionEvent.completionEvent string that marks this section done.
     * e.g. "ASSESSMENT_SUBMITTED", "EVALUATION_COMPLETE", "POLICY_SIGNED_OFF"
     */
    @Column(name = "completion_event", nullable = false, length = 100)
    private String completionEvent;

    /** Case 2: section distributes work to other users */
    @Column(name = "requires_assignment", nullable = false)
    @Builder.Default
    private boolean requiresAssignment = false;

    /** Case 3: section tracks item-level completions */
    @Column(name = "tracks_items", nullable = false)
    @Builder.Default
    private boolean tracksItems = false;
}