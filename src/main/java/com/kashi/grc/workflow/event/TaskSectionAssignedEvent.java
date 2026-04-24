package com.kashi.grc.workflow.event;

import java.util.List;

/**
 * Published when an assigner distributes a section's work to other users (Case 2).
 * WorkflowEventListener pushes this to assignee inboxes via WebSocket.
 */
public record TaskSectionAssignedEvent(
        Long         workflowInstanceId,
        Long         stepInstanceId,
        Long         parentTaskInstanceId,
        String       sectionKey,
        String       sectionLabel,
        Long         assignedByUserId,
        List<Long>   assignedToUserIds,
        List<Long>   subTaskInstanceIds
) {}