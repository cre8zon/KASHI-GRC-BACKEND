package com.kashi.grc.workflow.event;

import lombok.Getter;

/**
 * Sealed hierarchy of workflow domain events.
 *
 * Published via Spring ApplicationEventPublisher.
 * Consumed by WorkflowEventListener → pushed to WebSocket rooms.
 *
 * Adding a new event = add a new subclass + handle in listener.
 * No other code changes required.
 */
public sealed interface WorkflowEvent
        permits WorkflowEvent.TaskAssigned,
        WorkflowEvent.StepAdvanced,
        WorkflowEvent.StepCompleted,
        WorkflowEvent.WorkflowStarted,
        WorkflowEvent.WorkflowCompleted,
        WorkflowEvent.WorkflowCancelled {

    Long workflowInstanceId();
    String eventType();

    /** Task created for a user — inbox badge update + routing info */
    record TaskAssigned(
            Long   workflowInstanceId,
            Long   stepInstanceId,
            Long   taskId,
            Long   assignedUserId,
            String stepName,
            String entityType,
            Long   entityId,
            Long   artifactId,
            String resolvedStepSide,
            String resolvedStepAction,
            String taskRole
    ) implements WorkflowEvent {
        @Override public String eventType() { return "TASK_ASSIGNED"; }
    }

    /** Step became active — observers and dashboards update */
    record StepAdvanced(
            Long   workflowInstanceId,
            Long   stepInstanceId,
            String stepName,
            Integer stepOrder,
            String stepStatus,
            Long   performedBy
    ) implements WorkflowEvent {
        @Override public String eventType() { return "STEP_ADVANCED"; }
    }

    /** Step approved or rejected — progress panel updates */
    record StepCompleted(
            Long   workflowInstanceId,
            Long   stepInstanceId,
            String stepName,
            String outcome,       // APPROVED | REJECTED | SENT_BACK
            Long   performedBy
    ) implements WorkflowEvent {
        @Override public String eventType() { return "STEP_COMPLETED"; }
    }

    /** Workflow instance started — vendor detail page updates */
    record WorkflowStarted(
            Long   workflowInstanceId,
            String workflowName,
            Long   entityId,
            String entityType,
            Long   initiatedBy
    ) implements WorkflowEvent {
        @Override public String eventType() { return "WORKFLOW_STARTED"; }
    }

    /** All steps completed */
    record WorkflowCompleted(
            Long   workflowInstanceId,
            Long   entityId,
            String entityType
    ) implements WorkflowEvent {
        @Override public String eventType() { return "WORKFLOW_COMPLETED"; }
    }

    /** Instance cancelled */
    record WorkflowCancelled(
            Long   workflowInstanceId,
            Long   entityId,
            String entityType,
            Long   performedBy
    ) implements WorkflowEvent {
        @Override public String eventType() { return "WORKFLOW_CANCELLED"; }
    }
}