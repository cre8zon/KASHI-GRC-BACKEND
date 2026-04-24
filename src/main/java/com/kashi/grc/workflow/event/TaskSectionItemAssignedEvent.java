package com.kashi.grc.workflow.event;

import java.util.List;

/**
 * Published when items within a section are assigned to a user (Case 3).
 */
public record TaskSectionItemAssignedEvent(
        Long       workflowInstanceId,
        Long       stepInstanceId,
        Long       taskInstanceId,
        String     sectionKey,
        Long       assignedToUserId,
        List<Long> itemIds
) {}