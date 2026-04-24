package com.kashi.grc.workflow.dto.response;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * Per-section progress — returned by GET /v1/compound-tasks/{taskInstanceId}/progress.
 * All label/config fields sourced from snap_* — zero blueprint join.
 */
@Data
@Builder
public class TaskSectionProgressResponse {
    // From snap_* columns
    private String  sectionKey;
    private Integer sectionOrder;
    private String  label;
    private String  description;
    private boolean required;
    private String  completionEvent;
    private boolean requiresAssignment;
    private boolean tracksItems;

    // Case 2 progress
    private int assigneesTotal;
    private int assigneesCompleted;

    // Case 3 progress
    private int itemsTotal;
    private int itemsCompleted;

    // Completion state
    private boolean       completed;
    private LocalDateTime completedAt;
    private Long          completedBy;
    private String        artifactType;
    private Long          artifactId;
}