package com.kashi.grc.workflow.event;

/**
 * Published by TaskSectionCompletionService when a section completes
 * but the task is not yet fully approved (more sections pending).
 * WorkflowEventListener forwards this to the frontend via WebSocket
 * so the progress bar animates without a page reload.
 */
public record TaskSectionProgressEvent(
        Long   workflowInstanceId,
        Long   stepInstanceId,
        Long   taskInstanceId,
        Long   userId,
        String sectionKey,
        String sectionLabel,
        int    sectionsCompleted,
        int    sectionsRequired,
        boolean allSectionsDone
) {}