package com.kashi.grc.workflow.event;

import com.kashi.grc.workflow.service.TaskSectionCompletionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Consumes WorkflowEvents published via Spring ApplicationEventPublisher
 * and pushes them to WebSocket rooms via STOMP.
 *
 * Room naming convention:
 *   /topic/instance/{workflowInstanceId}  → all participants on a workflow
 *   /topic/user/{userId}                  → personal inbox for a specific user
 *   /topic/artifact/{entityType}/{id}     → observers of a specific artifact
 *
 * @Async ensures WebSocket pushes don't block the main transaction thread.
 * Events are fire-and-forget — if the push fails, the DB transaction already committed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowEventListener {

    private final SimpMessagingTemplate       messagingTemplate;
    private final TaskSectionCompletionService sectionCompletionService;

    @Async
    @EventListener
    public void onTaskAssigned(WorkflowEvent.TaskAssigned event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type",               event.eventType());
        payload.put("workflowInstanceId", event.workflowInstanceId());
        payload.put("stepInstanceId",     event.stepInstanceId());
        payload.put("taskId",             event.taskId());
        payload.put("stepName",           event.stepName());
        payload.put("entityType",         event.entityType());
        payload.put("entityId",           event.entityId());
        payload.put("artifactId",         event.artifactId());
        payload.put("resolvedStepSide",   event.resolvedStepSide());
        payload.put("resolvedStepAction", event.resolvedStepAction());
        payload.put("taskRole",           event.taskRole());
        payload.put("ts",                 Instant.now().toEpochMilli());

        // Push to the workflow instance room — all participants see it
        push("/topic/instance/" + event.workflowInstanceId(), payload);
        // Push to the assigned user's personal room — triggers inbox badge update
        push("/topic/user/" + event.assignedUserId(), payload);
        // Push to the artifact room — observers see task created without refreshing
        if (event.entityType() != null && event.artifactId() != null) {
            push("/topic/artifact/" + event.entityType().toLowerCase()
                    + "/" + event.artifactId(), payload);
        }

        log.debug("[WS] TaskAssigned → userId={} | step='{}' | instanceId={}",
                event.assignedUserId(), event.stepName(), event.workflowInstanceId());
    }

    @Async
    @EventListener
    public void onStepAdvanced(WorkflowEvent.StepAdvanced event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type",               event.eventType());
        payload.put("workflowInstanceId", event.workflowInstanceId());
        payload.put("stepInstanceId",     event.stepInstanceId());
        payload.put("stepName",           event.stepName());
        payload.put("stepOrder",          event.stepOrder());
        payload.put("stepStatus",         event.stepStatus());
        payload.put("ts",                 Instant.now().toEpochMilli());

        push("/topic/instance/" + event.workflowInstanceId(), payload);
        log.debug("[WS] StepAdvanced → '{}' | instanceId={}", event.stepName(), event.workflowInstanceId());
    }

    @Async
    @EventListener
    public void onStepCompleted(WorkflowEvent.StepCompleted event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type",               event.eventType());
        payload.put("workflowInstanceId", event.workflowInstanceId());
        payload.put("stepInstanceId",     event.stepInstanceId());
        payload.put("stepName",           event.stepName());
        payload.put("outcome",            event.outcome());
        payload.put("performedBy",        event.performedBy());
        payload.put("ts",                 Instant.now().toEpochMilli());

        push("/topic/instance/" + event.workflowInstanceId(), payload);
        log.debug("[WS] StepCompleted → '{}' outcome={} | instanceId={}",
                event.stepName(), event.outcome(), event.workflowInstanceId());
    }

    @Async
    @EventListener
    public void onWorkflowStarted(WorkflowEvent.WorkflowStarted event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type",               event.eventType());
        payload.put("workflowInstanceId", event.workflowInstanceId());
        payload.put("workflowName",       event.workflowName());
        payload.put("entityId",           event.entityId());
        payload.put("entityType",         event.entityType());
        payload.put("ts",                 Instant.now().toEpochMilli());

        push("/topic/instance/" + event.workflowInstanceId(), payload);
        // Also push to entity room so VendorDetailPage updates without refresh
        if (event.entityType() != null) {
            push("/topic/entity/" + event.entityType().toLowerCase()
                    + "/" + event.entityId(), payload);
        }
    }

    @Async
    @EventListener
    public void onWorkflowCompleted(WorkflowEvent.WorkflowCompleted event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type",               event.eventType());
        payload.put("workflowInstanceId", event.workflowInstanceId());
        payload.put("ts",                 Instant.now().toEpochMilli());
        push("/topic/instance/" + event.workflowInstanceId(), payload);
    }

    @Async
    @EventListener
    public void onWorkflowCancelled(WorkflowEvent.WorkflowCancelled event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type",               event.eventType());
        payload.put("workflowInstanceId", event.workflowInstanceId());
        payload.put("ts",                 Instant.now().toEpochMilli());
        push("/topic/instance/" + event.workflowInstanceId(), payload);
        if (event.entityType() != null) {
            push("/topic/entity/" + event.entityType().toLowerCase()
                    + "/" + event.entityId(), payload);
        }
    }

    // Gap 8: handler for TaskSectionAssignedEvent — was missing, assignees got no real-time push
    @Async
    @EventListener
    public void onSectionAssigned(com.kashi.grc.workflow.event.TaskSectionAssignedEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type",              "SECTION_ASSIGNED");
        payload.put("workflowInstanceId", event.workflowInstanceId());
        payload.put("stepInstanceId",     event.stepInstanceId());
        payload.put("taskInstanceId",     event.parentTaskInstanceId());
        payload.put("sectionKey",         event.sectionKey());
        payload.put("sectionLabel",       event.sectionLabel());
        payload.put("assignedByUserId",   event.assignedByUserId());
        payload.put("assigneeUserIds",    event.assignedToUserIds());
        payload.put("subTaskIds",         event.subTaskInstanceIds());
        payload.put("ts",                 Instant.now().toEpochMilli());

        // Push to workflow room — all participants see the assignment
        push("/topic/instance/" + event.workflowInstanceId(), payload);
        // Push to each assignee's personal room — triggers inbox badge refresh
        if (event.assignedToUserIds() != null) {
            event.assignedToUserIds().forEach(uid -> push("/topic/user/" + uid, payload));
        }
        log.debug("[WS] SectionAssigned → task={} | section={} | assignees={}",
                event.parentTaskInstanceId(), event.sectionKey(),
                event.assignedToUserIds() != null ? event.assignedToUserIds().size() : 0);
    }

    private void push(String destination, Object payload) {
        try {
            messagingTemplate.convertAndSend(destination, payload);
        } catch (Exception e) {
            log.warn("[WS] Failed to push to {}: {}", destination, e.getMessage());
        }
    }

    @Async
    @EventListener
    public void onSectionProgress(com.kashi.grc.workflow.event.TaskSectionProgressEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type",              "SECTION_PROGRESS");
        payload.put("workflowInstanceId", event.workflowInstanceId());
        payload.put("taskInstanceId",     event.taskInstanceId());
        payload.put("sectionKey",         event.sectionKey());
        payload.put("sectionLabel",       event.sectionLabel());
        payload.put("sectionsCompleted",  event.sectionsCompleted());
        payload.put("sectionsRequired",   event.sectionsRequired());
        payload.put("allSectionsDone",    event.allSectionsDone());
        payload.put("ts",                 Instant.now().toEpochMilli());
        push("/topic/instance/" + event.workflowInstanceId(), payload);
        push("/topic/user/"     + event.userId(),             payload);
        log.debug("[WS] SectionProgress → task={} | done={}/{}",
                event.taskInstanceId(), event.sectionsCompleted(), event.sectionsRequired());
    }

    @Async
    @EventListener
    public void onItemCompleted(com.kashi.grc.workflow.event.TaskSectionItemCompletedEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type",           "SECTION_ITEM_COMPLETED");
        payload.put("taskInstanceId", event.taskInstanceId());
        payload.put("sectionKey",     event.sectionKey());
        payload.put("itemId",         event.itemId());
        payload.put("itemsCompleted", event.itemsCompleted());
        payload.put("itemsTotal",     event.itemsTotal());
        payload.put("ts",             Instant.now().toEpochMilli());
        push("/topic/instance/" + event.workflowInstanceId(), payload);
        push("/topic/user/"     + event.completedByUserId(), payload);
        log.debug("[WS] ItemCompleted → task={} | section={} | progress={}/{}",
                event.taskInstanceId(), event.sectionKey(),
                event.itemsCompleted(), event.itemsTotal());

        // ── Auto-fire section completion when all items done ──────────────────
        // For tracksItems=true sections, the completionEvent fires automatically
        // when itemsCompleted == itemsTotal. This is the Case 3 gate:
        //   completeItem() × N items → last item → SECTION_DONE event → section complete
        // Without this, item-tracked sections (SCORE_ANSWERS, REVIEW_ANSWERS, etc.)
        // never complete regardless of how many items are marked done.
        if (event.itemsCompleted() > 0 && event.itemsCompleted() >= event.itemsTotal()) {
            log.info("[CASE3-AUTO] All items done for section={} | task={} — firing section completion",
                    event.sectionKey(), event.taskInstanceId());
            // Resolve the completionEvent for this section from the snapshot
            sectionCompletionService.autoCompleteItemTrackedSection(
                    event.taskInstanceId(), event.sectionKey(), event.completedByUserId());
        }
    }
}