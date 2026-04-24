package com.kashi.grc.workflow.event;

import java.util.List;

/**
 * Universal module → workflow engine contract.
 * Fire this from any module when a section of work is done.
 * The engine listener (TaskSectionCompletionService.onSectionEvent)
 * finds the matching TaskSectionCompletion row by snap_completion_event
 * and marks it complete — zero blueprint read.
 */
public record TaskSectionEvent(
        String completionEvent,   // matches snap_completion_event on TaskSectionCompletion
        Long   taskInstanceId,    // required — null = event dropped
        Long   performedBy,
        String artifactType,
        Long   artifactId,
        String remarks
) {
    public static TaskSectionEvent sectionDone(
            String completionEvent, Long taskInstanceId, Long performedBy,
            String artifactType, Long artifactId) {
        return new TaskSectionEvent(completionEvent, taskInstanceId,
                performedBy, artifactType, artifactId, null);
    }

    public static TaskSectionEvent sectionDone(
            String completionEvent, Long taskInstanceId, Long performedBy) {
        return new TaskSectionEvent(completionEvent, taskInstanceId,
                performedBy, null, null, null);
    }
}