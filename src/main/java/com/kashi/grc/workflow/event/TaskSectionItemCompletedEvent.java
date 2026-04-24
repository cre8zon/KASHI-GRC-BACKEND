package com.kashi.grc.workflow.event;

/**
 * Published when a single item within a section is completed (Case 3).
 * Used for live progress updates on the work page.
 */
public record TaskSectionItemCompletedEvent(
        Long   workflowInstanceId,
        Long   stepInstanceId,
        Long   taskInstanceId,
        String sectionKey,
        Long   itemId,
        Long   completedByUserId,
        int    itemsCompleted,
        int    itemsTotal
) {}