package com.kashi.grc.workflow.service;

import com.kashi.grc.actionitem.domain.ActionItem;
import com.kashi.grc.actionitem.repository.ActionItemRepository;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.notification.service.NotificationService;
import com.kashi.grc.usermanagement.repository.UserRepository;
import com.kashi.grc.workflow.domain.*;
import com.kashi.grc.workflow.dto.request.TaskActionRequest;
import com.kashi.grc.workflow.dto.response.TaskSectionProgressResponse;
import com.kashi.grc.workflow.enums.ActionType;
import com.kashi.grc.workflow.enums.TaskRole;
import com.kashi.grc.workflow.enums.TaskStatus;
import com.kashi.grc.workflow.event.*;
import com.kashi.grc.workflow.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Compound task engine — full blueprint isolation (Option A).
 *
 * workflow_step_sections is read exactly ONCE per task in snapshotSectionsForTask().
 * Every section field is copied into TaskSectionCompletion.snap_* at that moment.
 * After snapshot, ALL runtime logic reads from task_section_completions only.
 * Blueprint edits have zero effect on running instances.
 *
 * ── CHANGES vs original ───────────────────────────────────────────────────────
 *
 * snapshotSectionsForTask(): now copies 5 new UI fields:
 *   snapSectionScreenKey, snapItemScreenKey, snapItemRefType,
 *   snapSectionUiJson, snapItemUiJson
 *
 * getProgress(): now returns 5 new UI fields + populated items list
 *   (SectionItemResponse per tracked item including hasOpenActionItem flag).
 *
 * ── CIRCULAR DEPENDENCY RESOLUTION ───────────────────────────────────────────
 * WorkflowEngineService injects TaskSectionCompletionService (eager)
 * TaskSectionCompletionService injects WorkflowEngineService (@Lazy proxy)
 */
@Slf4j
@Service
public class TaskSectionCompletionService {

    private final WorkflowStepSectionRepository       stepSectionRepository;
    private final TaskSectionCompletionRepository     completionRepository;
    private final TaskSectionAssignmentRepository     assignmentRepository;
    private final TaskSectionItemRepository           itemRepository;
    private final TaskSectionItemAssignmentRepository itemAssignmentRepository;
    private final TaskSectionItemCompletionRepository itemCompletionRepository;
    private final TaskInstanceRepository              taskInstanceRepository;
    private final StepInstanceRepository              stepInstanceRepository;
    private final WorkflowInstanceRepository          workflowInstanceRepository;
    private final NotificationService                 notificationService;
    private final ApplicationEventPublisher           eventPublisher;
    private final WorkflowEngineService               workflowEngineService;
    // NEW dependencies for enriched getProgress()
    private final ActionItemRepository                actionItemRepository;
    private final UserRepository                      userRepository;

    @Autowired
    public TaskSectionCompletionService(
            WorkflowStepSectionRepository       stepSectionRepository,
            TaskSectionCompletionRepository     completionRepository,
            TaskSectionAssignmentRepository     assignmentRepository,
            TaskSectionItemRepository           itemRepository,
            TaskSectionItemAssignmentRepository itemAssignmentRepository,
            TaskSectionItemCompletionRepository itemCompletionRepository,
            TaskInstanceRepository              taskInstanceRepository,
            StepInstanceRepository              stepInstanceRepository,
            WorkflowInstanceRepository          workflowInstanceRepository,
            NotificationService                 notificationService,
            ApplicationEventPublisher           eventPublisher,
            @Lazy WorkflowEngineService         workflowEngineService,
            ActionItemRepository                actionItemRepository,
            UserRepository                      userRepository) {
        this.stepSectionRepository    = stepSectionRepository;
        this.completionRepository     = completionRepository;
        this.assignmentRepository     = assignmentRepository;
        this.itemRepository           = itemRepository;
        this.itemAssignmentRepository = itemAssignmentRepository;
        this.itemCompletionRepository = itemCompletionRepository;
        this.taskInstanceRepository   = taskInstanceRepository;
        this.stepInstanceRepository   = stepInstanceRepository;
        this.workflowInstanceRepository = workflowInstanceRepository;
        this.notificationService      = notificationService;
        this.eventPublisher           = eventPublisher;
        this.workflowEngineService    = workflowEngineService;
        this.actionItemRepository     = actionItemRepository;
        this.userRepository           = userRepository;
    }

    // ════════════════════════════════════════════════════════════════
    // SNAPSHOT — only place workflow_step_sections is ever read at runtime
    // ════════════════════════════════════════════════════════════════

    /**
     * Copies all section blueprint fields into TaskSectionCompletion snap_* columns.
     * Called by WorkflowEngineService.assignTasksForStep() when a step becomes active.
     * After this method returns, workflow_step_sections is never read for this task again.
     *
     * NEW: also copies sectionScreenKey, itemScreenKey, itemRefType,
     *      sectionUiJson, itemUiJson for frontend UI rendering.
     */
    @Transactional
    public void snapshotSectionsForTask(TaskInstance task, StepInstance si, WorkflowInstance wi) {
        List<WorkflowStepSection> blueprint =
                stepSectionRepository.findByStepIdOrderBySectionOrderAsc(si.getStepId());

        if (blueprint.isEmpty()) {
            log.debug("[SNAPSHOT] Step '{}' has no sections", si.getSnapName());
            return;
        }

        int snapshotted = 0;
        for (WorkflowStepSection section : blueprint) {
            boolean exists = completionRepository
                    .findByTaskInstanceIdAndSnapSectionKey(task.getId(), section.getSectionKey())
                    .isPresent();
            if (exists) continue;

            completionRepository.save(TaskSectionCompletion.builder()
                    .taskInstanceId(task.getId())
                    .stepInstanceId(si.getId())
                    .workflowInstanceId(wi.getId())
                    // ── Existing snap fields ───────────────────────────────
                    .snapSectionKey(section.getSectionKey())
                    .snapSectionOrder(section.getSectionOrder())
                    .snapLabel(section.getLabel())
                    .snapDescription(section.getDescription())
                    .snapRequired(section.isRequired())
                    .snapCompletionEvent(section.getCompletionEvent())
                    .snapRequiresAssignment(section.isRequiresAssignment())
                    .snapTracksItems(section.isTracksItems())
                    // ── NEW: UI rendering snap fields ──────────────────────
                    .snapSectionScreenKey(section.getSectionScreenKey())
                    .snapItemScreenKey(section.getItemScreenKey())
                    .snapItemRefType(section.getItemRefType())
                    .snapSectionUiJson(section.getSectionUiJson())
                    .snapItemUiJson(section.getItemUiJson())
                    // ── Runtime state ──────────────────────────────────────
                    .completed(false)
                    .build());
            snapshotted++;
        }

        log.info("[SNAPSHOT] {} section(s) snapshotted | taskId={} | step='{}'",
                snapshotted, task.getId(), si.getSnapName());

        // ── Notify item registrars for sections that need item population ─────
        // ONLY fires when blueprint section has tracksItems=true AND itemRefType set.
        // Existing TPRM blueprints have NO sections → blueprint.isEmpty() returns
        // early above → this loop never runs → zero events fired → zero behavior change.
        for (WorkflowStepSection section : blueprint) {
            if (!section.isTracksItems()
                    || section.getItemRefType() == null
                    || section.getItemRefType().isBlank()) {
                continue;
            }
            eventPublisher.publishEvent(new com.kashi.grc.workflow.event.SectionItemsNeededEvent(
                    task.getId(),
                    si.getId(),
                    wi.getId(),
                    wi.getTenantId(),
                    section.getSectionKey(),
                    section.getItemRefType(),
                    section.getSectionScreenKey(),
                    section.getItemScreenKey(),
                    section.getSectionUiJson(),
                    task.getAssignedUserId()   // scope item registration to this task's assignee
            ));
            log.debug("[SNAPSHOT] SectionItemsNeededEvent fired | sectionKey={} | itemRefType={} | taskId={}",
                    section.getSectionKey(), section.getItemRefType(), task.getId());
        }
    }

    // ════════════════════════════════════════════════════════════════
    // CASE 1: TaskSectionEvent listener
    // ════════════════════════════════════════════════════════════════

    @EventListener
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void onSectionEvent(TaskSectionEvent event) {
        log.info("[SECTION] Event='{}' | taskId={} | by={}",
                event.completionEvent(), event.taskInstanceId(), event.performedBy());

        if (event.taskInstanceId() == null) return;

        TaskInstance task = taskInstanceRepository.findById(event.taskInstanceId()).orElse(null);
        if (task == null) {
            log.warn("[SECTION] TaskInstance {} not found", event.taskInstanceId());
            return;
        }
        if (task.getStatus() != TaskStatus.PENDING && task.getStatus() != TaskStatus.IN_PROGRESS) {
            log.debug("[SECTION] Task {} already terminal ({})", task.getId(), task.getStatus());
            return;
        }

        TaskSectionCompletion matched = completionRepository
                .findByTaskInstanceIdAndSnapCompletionEvent(task.getId(), event.completionEvent())
                .orElse(null);

        if (matched == null) {
            log.warn("[SECTION] No snapshotted section with completionEvent='{}' on task={}",
                    event.completionEvent(), task.getId());
            return;
        }

        if (matched.isCompleted()) {
            log.debug("[SECTION] Already complete — idempotent | taskId={} | section={}",
                    task.getId(), matched.getSnapSectionKey());
            return;
        }

        matched.setCompleted(true);
        matched.setCompletedAt(LocalDateTime.now());
        matched.setCompletedBy(event.performedBy());
        matched.setArtifactType(event.artifactType());
        matched.setArtifactId(event.artifactId());
        matched.setRemarks(event.remarks());
        completionRepository.save(matched);

        if (task.getStatus() == TaskStatus.PENDING) {
            task.setStatus(TaskStatus.IN_PROGRESS);
            taskInstanceRepository.save(task);
        }

        log.info("[SECTION] Marked complete | taskId={} | section='{}'",
                task.getId(), matched.getSnapSectionKey());

        boolean allDone = isAllRequiredComplete(task.getId());
        log.info("[SECTION] Gate check | taskId={} | allDone={}", task.getId(), allDone);

        pushProgressEvent(task, matched, allDone);

        if (allDone) {
            autoApproveTask(task, event.performedBy());
        }
    }

    // ════════════════════════════════════════════════════════════════
    // CASE 2: section-level assignment
    // ════════════════════════════════════════════════════════════════

    @Transactional
    public List<TaskSectionAssignment> assignSection(
            Long taskInstanceId, String sectionKey,
            List<Long> assigneeUserIds, Long assignedBy, String notes) {

        TaskInstance parentTask = taskInstanceRepository.findById(taskInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("TaskInstance", taskInstanceId));
        StepInstance si = stepInstanceRepository.findById(parentTask.getStepInstanceId())
                .orElseThrow(() -> new ResourceNotFoundException("StepInstance", parentTask.getStepInstanceId()));
        WorkflowInstance wi = workflowInstanceRepository.findById(si.getWorkflowInstanceId())
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowInstance", si.getWorkflowInstanceId()));

        TaskSectionCompletion sectionSnap = completionRepository
                .findByTaskInstanceIdAndSnapSectionKey(taskInstanceId, sectionKey)
                .orElseThrow(() -> new BusinessException("SECTION_NOT_FOUND",
                        "Section '" + sectionKey + "' not snapshotted on task " + taskInstanceId));

        if (!sectionSnap.isSnapRequiresAssignment()) {
            throw new BusinessException("SECTION_NOT_ASSIGNABLE",
                    "Section '" + sectionKey + "' does not support assignment");
        }

        List<TaskSectionAssignment> assignments = new ArrayList<>();
        List<Long> subTaskIds = new ArrayList<>();

        for (Long userId : assigneeUserIds) {
            if (assignmentRepository.existsByTaskInstanceIdAndSectionKeyAndAssignedToUserId(
                    taskInstanceId, sectionKey, userId)) {
                continue;
            }

            TaskInstance subTask = workflowEngineService.createSubTask(
                    si, userId, TaskRole.ACTOR, wi.getTenantId(),
                    sectionSnap.getSnapLabel() + " — assigned by coordinator");
            subTaskIds.add(subTask.getId());

            TaskSectionAssignment assignment = TaskSectionAssignment.builder()
                    .taskInstanceId(taskInstanceId)
                    .stepInstanceId(si.getId())
                    .workflowInstanceId(wi.getId())
                    .sectionKey(sectionKey)
                    .assignedToUserId(userId)
                    .assignedByUserId(assignedBy)
                    .subTaskInstanceId(subTask.getId())
                    .status("PENDING")
                    .notes(notes)
                    .assignedAt(LocalDateTime.now())
                    .build();
            assignmentRepository.save(assignment);
            assignments.add(assignment);

            notificationService.send(userId, "SECTION_ASSIGNED",
                    "Work assigned: " + sectionSnap.getSnapLabel(), "TASK", subTask.getId());

            log.info("[CASE2] Sub-task created | parentTask={} | section={} | assignee={} | subTask={}",
                    taskInstanceId, sectionKey, userId, subTask.getId());
        }

        eventPublisher.publishEvent(new TaskSectionAssignedEvent(
                wi.getId(), si.getId(), taskInstanceId, sectionKey,
                sectionSnap.getSnapLabel(), assignedBy, assigneeUserIds, subTaskIds));

        if (parentTask.getStatus() == TaskStatus.PENDING) {
            parentTask.setStatus(TaskStatus.IN_PROGRESS);
            taskInstanceRepository.save(parentTask);
        }

        return assignments;
    }

    @Transactional
    public void onSubTaskCompleted(Long subTaskInstanceId, Long completedBy) {
        TaskSectionAssignment assignment = assignmentRepository
                .findBySubTaskInstanceId(subTaskInstanceId).orElse(null);
        if (assignment == null) return;

        assignment.setStatus("COMPLETED");
        assignment.setCompletedAt(LocalDateTime.now());
        assignmentRepository.save(assignment);

        long incompleteSubTasks = assignmentRepository
                .countIncomplete(assignment.getTaskInstanceId(), assignment.getSectionKey());

        log.info("[CASE2] Sub-task {} done | section={} | remaining={}",
                subTaskInstanceId, assignment.getSectionKey(), incompleteSubTasks);

        if (incompleteSubTasks == 0) {
            TaskSectionCompletion sectionSnap = completionRepository
                    .findByTaskInstanceIdAndSnapSectionKey(
                            assignment.getTaskInstanceId(), assignment.getSectionKey())
                    .orElse(null);
            if (sectionSnap == null) return;

            eventPublisher.publishEvent(TaskSectionEvent.sectionDone(
                    sectionSnap.getSnapCompletionEvent(),
                    assignment.getTaskInstanceId(),
                    completedBy));
        }
    }

    // ════════════════════════════════════════════════════════════════
    // CASE 3: item-level tracking
    // ════════════════════════════════════════════════════════════════

    @Transactional
    public List<TaskSectionItem> registerItems(
            Long taskInstanceId, String sectionKey, List<ItemRegistration> items) {

        TaskInstance task = taskInstanceRepository.findById(taskInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("TaskInstance", taskInstanceId));
        StepInstance si = stepInstanceRepository.findById(task.getStepInstanceId())
                .orElseThrow(() -> new ResourceNotFoundException("StepInstance", task.getStepInstanceId()));
        WorkflowInstance wi = workflowInstanceRepository.findById(si.getWorkflowInstanceId())
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowInstance", si.getWorkflowInstanceId()));

        completionRepository.findByTaskInstanceIdAndSnapSectionKey(taskInstanceId, sectionKey)
                .orElseThrow(() -> new BusinessException("SECTION_NOT_FOUND",
                        "Section '" + sectionKey + "' not snapshotted on task " + taskInstanceId));

        List<TaskSectionItem> registered = new ArrayList<>();
        for (ItemRegistration reg : items) {
            boolean exists = itemRepository
                    .findByTaskInstanceIdAndSectionKey(taskInstanceId, sectionKey)
                    .stream()
                    .anyMatch(i -> i.getItemRefType().equals(reg.itemRefType())
                            && i.getItemRefId().equals(reg.itemRefId()));
            if (exists) continue;

            registered.add(itemRepository.save(TaskSectionItem.builder()
                    .taskInstanceId(taskInstanceId)
                    .stepInstanceId(si.getId())
                    .workflowInstanceId(wi.getId())
                    .sectionKey(sectionKey)
                    .itemRefType(reg.itemRefType())
                    .itemRefId(reg.itemRefId())
                    .itemLabel(reg.label())
                    .status("PENDING")
                    .build()));
        }

        log.info("[CASE3] Registered {} item(s) | taskId={} | section={}",
                registered.size(), taskInstanceId, sectionKey);
        return registered;
    }

    @Transactional
    public void assignItems(Long taskInstanceId, String sectionKey,
                            List<Long> itemIds, Long assignedToUserId, Long assignedBy) {
        LocalDateTime now = LocalDateTime.now();
        for (Long itemId : itemIds) {
            itemAssignmentRepository.findByItemIdAndIsActive(itemId, true)
                    .forEach(a -> { a.setActive(false); itemAssignmentRepository.save(a); });

            itemAssignmentRepository.save(TaskSectionItemAssignment.builder()
                    .itemId(itemId)
                    .taskInstanceId(taskInstanceId)
                    .sectionKey(sectionKey)
                    .assignedToUserId(assignedToUserId)
                    .assignedByUserId(assignedBy)
                    .isActive(true)
                    .assignedAt(now)
                    .build());

            itemRepository.findById(itemId).ifPresent(item -> {
                item.setStatus("IN_PROGRESS");
                itemRepository.save(item);
            });
        }

        log.info("[CASE3] Assigned {} item(s) to user={} | taskId={} | section={}",
                itemIds.size(), assignedToUserId, taskInstanceId, sectionKey);

        resolveWiAndSi(taskInstanceId).ifPresent(ctx ->
                eventPublisher.publishEvent(new TaskSectionItemAssignedEvent(
                        ctx.wi().getId(), ctx.si().getId(),
                        taskInstanceId, sectionKey, assignedToUserId, itemIds)));
    }

    @Transactional
    public void completeItem(Long taskInstanceId, String sectionKey, Long itemId,
                             Long completedBy, String outcome, String notes,
                             String artifactType, Long artifactId) {

        TaskSectionItem item = itemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("TaskSectionItem", itemId));
        item.setStatus("COMPLETED");
        itemRepository.save(item);

        itemCompletionRepository.save(TaskSectionItemCompletion.builder()
                .itemId(itemId)
                .taskInstanceId(taskInstanceId)
                .sectionKey(sectionKey)
                .completedBy(completedBy)
                .completedAt(LocalDateTime.now())
                .outcome(outcome)
                .notes(notes)
                .artifactType(artifactType)
                .artifactId(artifactId)
                .build());

        long done  = itemCompletionRepository.countByTaskInstanceIdAndSectionKey(taskInstanceId, sectionKey);
        long total = itemRepository.countByTaskInstanceIdAndSectionKey(taskInstanceId, sectionKey);

        log.info("[CASE3] Item {} done | section={} | progress={}/{}", itemId, sectionKey, done, total);

        resolveWiAndSi(taskInstanceId).ifPresent(ctx ->
                eventPublisher.publishEvent(new TaskSectionItemCompletedEvent(
                        ctx.wi().getId(), ctx.si().getId(), taskInstanceId,
                        sectionKey, itemId, completedBy, (int) done, (int) total)));
    }

    // ════════════════════════════════════════════════════════════════
    // CASE 3: auto-complete item-tracked section
    // ════════════════════════════════════════════════════════════════

    /**
     * Completes a section item identified by its domain entity ID (itemRefId)
     * rather than the internal task_section_items PK.
     * Used by ProjectEngagementsTab after assigning a lead auditor — the frontend
     * knows the engagementId but not the internal item PK.
     */
    @Transactional
    public void completeItemByRef(Long taskInstanceId, String sectionKey, Long itemRefId, Long completedBy) {
        TaskSectionItem item = itemRepository
                .findByTaskInstanceIdAndSectionKeyAndItemRefId(taskInstanceId, sectionKey, itemRefId)
                .orElse(null);

        if (item == null) {
            log.warn("[CASE3-BY-REF] No item found for taskId={} sectionKey={} itemRefId={} — skipping",
                    taskInstanceId, sectionKey, itemRefId);
            return;
        }

        if ("COMPLETED".equals(item.getStatus())) {
            log.debug("[CASE3-BY-REF] Item {} already completed — skipping", item.getId());
            return;
        }

        completeItem(taskInstanceId, sectionKey, item.getId(), completedBy, null, null, null, null);
    }

    public void autoCompleteItemTrackedSection(Long taskInstanceId, String sectionKey, Long completedBy) {
        TaskInstance task = taskInstanceRepository.findById(taskInstanceId).orElse(null);
        if (task == null) {
            log.warn("[CASE3-AUTO] Task {} not found — cannot auto-complete section {}",
                    taskInstanceId, sectionKey);
            return;
        }

        completionRepository
                .findByTaskInstanceIdAndSnapSectionKey(taskInstanceId, sectionKey)
                .ifPresentOrElse(snap -> {
                    if (snap.isCompleted()) {
                        log.debug("[CASE3-AUTO] Section {} on task {} already completed — skipping",
                                sectionKey, taskInstanceId);
                        return;
                    }
                    String completionEvent = snap.getSnapCompletionEvent();
                    log.info("[CASE3-AUTO] Firing completionEvent='{}' for section={} | task={}",
                            completionEvent, sectionKey, taskInstanceId);
                    eventPublisher.publishEvent(
                            TaskSectionEvent.sectionDone(completionEvent, taskInstanceId, completedBy));
                }, () -> log.warn("[CASE3-AUTO] No snapshot found for section={} task={}",
                        sectionKey, taskInstanceId));
    }

    // ════════════════════════════════════════════════════════════════
    // DRAFT
    // ════════════════════════════════════════════════════════════════

    @Transactional
    public void saveDraft(Long taskInstanceId, String draftJson, Long userId) {
        TaskInstance task = taskInstanceRepository.findById(taskInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("TaskInstance", taskInstanceId));
        if (task.getStatus() != TaskStatus.PENDING && task.getStatus() != TaskStatus.IN_PROGRESS) {
            throw new BusinessException("TASK_NOT_EDITABLE", "Task is in terminal state: " + task.getStatus());
        }
        if (task.getStatus() == TaskStatus.PENDING) task.setStatus(TaskStatus.IN_PROGRESS);
        task.setDraftData(draftJson);
        task.setDraftSavedAt(LocalDateTime.now());
        taskInstanceRepository.save(task);
        log.debug("[DRAFT] Saved | taskId={} | userId={}", taskInstanceId, userId);
    }

    public String getDraft(Long taskInstanceId) {
        return taskInstanceRepository.findById(taskInstanceId)
                .map(TaskInstance::getDraftData)
                .orElse(null);
    }

    // ════════════════════════════════════════════════════════════════
    // PROGRESS QUERY — reads snap_* only, no blueprint join
    // NOW: returns UI fields + populated items list
    // ════════════════════════════════════════════════════════════════

    public List<TaskSectionProgressResponse> getProgress(Long taskInstanceId) {
        List<TaskSectionCompletion> snapshots = completionRepository
                .findByTaskInstanceIdOrderBySnapSectionOrderAsc(taskInstanceId);

        if (snapshots.isEmpty()) return List.of();

        return snapshots.stream().map(snap -> {
            // ── Case 2 & 3 progress counting (unchanged) ─────────────
            long itemsTotal = 0, itemsDone = 0;
            if (snap.isSnapTracksItems()) {
                itemsTotal = itemRepository.countByTaskInstanceIdAndSectionKey(
                        taskInstanceId, snap.getSnapSectionKey());
                itemsDone  = itemCompletionRepository.countByTaskInstanceIdAndSectionKey(
                        taskInstanceId, snap.getSnapSectionKey());
            }

            long assigneesTotal = 0, assigneesDone = 0;
            if (snap.isSnapRequiresAssignment()) {
                List<TaskSectionAssignment> assignments = assignmentRepository
                        .findByTaskInstanceIdAndSectionKey(taskInstanceId, snap.getSnapSectionKey());
                assigneesTotal = assignments.size();
                assigneesDone  = assignments.stream()
                        .filter(a -> "COMPLETED".equals(a.getStatus())).count();
            }

            // ── NEW: populate item list when tracksItems = true ──────
            List<TaskSectionProgressResponse.SectionItemResponse> itemResponses = List.of();
            if (snap.isSnapTracksItems()) {
                List<TaskSectionItem> items = itemRepository
                        .findByTaskInstanceIdAndSectionKey(taskInstanceId, snap.getSnapSectionKey());

                itemResponses = items.stream().map(item -> {
                    // Check for open action items on this specific item (for badge)
                    boolean hasOpenAi = actionItemRepository.existsOpenForEntity(
                            snap.getSnapItemRefType(),
                            item.getItemRefId());

                    // Resolve assignee name if set
                    String assigneeName = null;
                    if (item.getAssignedToUserId() != null) {
                        assigneeName = userRepository.findById(item.getAssignedToUserId())
                                .map(u -> u.getFirstName() + " " + u.getLastName())
                                .orElse(null);
                    }

                    return TaskSectionProgressResponse.SectionItemResponse.builder()
                            .id(item.getId())
                            .itemRefType(snap.getSnapItemRefType())
                            .itemRefId(item.getItemRefId())
                            .itemLabel(item.getItemLabel())
                            .status(item.getStatus())
                            .assignedToUserId(item.getAssignedToUserId())
                            .assignedToUserName(assigneeName)
                            .hasOpenActionItem(hasOpenAi)
                            .build();
                }).toList();
            }

            // ── Build full response ───────────────────────────────────
            return TaskSectionProgressResponse.builder()
                    // Existing fields (unchanged)
                    .sectionKey(snap.getSnapSectionKey())
                    .sectionOrder(snap.getSnapSectionOrder())
                    .label(snap.getSnapLabel())
                    .description(snap.getSnapDescription())
                    .required(snap.isSnapRequired())
                    .requiresAssignment(snap.isSnapRequiresAssignment())
                    .tracksItems(snap.isSnapTracksItems())
                    .completionEvent(snap.getSnapCompletionEvent())
                    .completed(snap.isCompleted())
                    .completedAt(snap.getCompletedAt())
                    .completedBy(snap.getCompletedBy())
                    .artifactType(snap.getArtifactType())
                    .artifactId(snap.getArtifactId())
                    .itemsTotal((int) itemsTotal)
                    .itemsCompleted((int) itemsDone)
                    .assigneesTotal((int) assigneesTotal)
                    .assigneesCompleted((int) assigneesDone)
                    // NEW fields
                    .sectionScreenKey(snap.getSnapSectionScreenKey())
                    .itemScreenKey(snap.getSnapItemScreenKey())
                    .itemRefType(snap.getSnapItemRefType())
                    .sectionUiJson(snap.getSnapSectionUiJson())
                    .itemUiJson(snap.getSnapItemUiJson())
                    .items(itemResponses)
                    .build();
        }).toList();
    }

    // ════════════════════════════════════════════════════════════════
    // GATE CHECK
    // ════════════════════════════════════════════════════════════════

    public boolean hasSections(Long taskInstanceId) {
        return completionRepository.existsByTaskInstanceId(taskInstanceId);
    }

    public void validateReadyForApproval(Long taskInstanceId) {
        List<TaskSectionCompletion> incomplete =
                completionRepository.findIncompleteRequired(taskInstanceId);

        if (!incomplete.isEmpty()) {
            String missing = incomplete.stream()
                    .map(TaskSectionCompletion::getSnapLabel)
                    .collect(Collectors.joining(", "));
            throw new BusinessException("TASK_SECTIONS_INCOMPLETE",
                    "Complete these sections before approving: " + missing);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ════════════════════════════════════════════════════════════════

    @Transactional
    public void markAllSectionsCompleteForTask(Long taskId, Long performedBy) {
        TaskInstance task = taskInstanceRepository.findById(taskId).orElse(null);
        if (task == null) { log.warn("[SECTION] markAllSections: task {} not found", taskId); return; }
        if (task.getStatus() != TaskStatus.PENDING && task.getStatus() != TaskStatus.IN_PROGRESS) {
            log.debug("[SECTION] markAllSections: task {} already terminal ({})", taskId, task.getStatus()); return;
        }

        List<TaskSectionCompletion> sections =
                completionRepository.findByTaskInstanceIdOrderBySnapSectionOrderAsc(taskId);
        if (sections.isEmpty()) { log.warn("[SECTION] markAllSections: no snapshotted sections for task {}", taskId); return; }

        LocalDateTime now = LocalDateTime.now();
        if (task.getStatus() == TaskStatus.PENDING) { task.setStatus(TaskStatus.IN_PROGRESS); taskInstanceRepository.save(task); }

        for (TaskSectionCompletion sec : sections) {
            if (!sec.isCompleted()) {
                sec.setCompleted(true);
                sec.setCompletedAt(now);
                sec.setCompletedBy(performedBy);
                sec.setArtifactType("VENDOR_ASSESSMENT");
                completionRepository.save(sec);
                log.info("[SECTION] Marked complete | taskId={} | section='{}'", taskId, sec.getSnapSectionKey());
            }
        }

        boolean allDone = isAllRequiredComplete(taskId);
        log.info("[SECTION] Gate check after markAll | taskId={} | allDone={}", taskId, allDone);

        if (!sections.isEmpty()) pushProgressEvent(task, sections.get(sections.size() - 1), allDone);
        if (allDone) autoApproveTask(task, performedBy);
    }

    private boolean isAllRequiredComplete(Long taskInstanceId) {
        long total = completionRepository.countTotalRequired(taskInstanceId);
        long done  = completionRepository.countCompletedRequired(taskInstanceId);
        return total > 0 && done >= total;
    }

    private void autoApproveTask(TaskInstance task, Long performedBy) {
        log.info("[SECTION] All required sections done — auto-approving task={}", task.getId());
        try {
            TaskActionRequest req = new TaskActionRequest();
            req.setTaskInstanceId(task.getId());
            req.setActionType(ActionType.APPROVE);
            req.setRemarks("All required sections completed — auto-approved");
            workflowEngineService.performAction(req, performedBy);
        } catch (BusinessException ex) {
            if ("TASK_TERMINAL".equals(ex.getErrorCode())) {
                log.debug("[SECTION] Task {} already approved — idempotent", task.getId());
            } else {
                log.error("[SECTION] Auto-approve failed task={}: {}", task.getId(), ex.getMessage());
                throw ex;
            }
        }
    }

    private void pushProgressEvent(TaskInstance task, TaskSectionCompletion snap, boolean allDone) {
        long total = completionRepository.countTotalRequired(task.getId());
        long done  = completionRepository.countCompletedRequired(task.getId());

        stepInstanceRepository.findById(task.getStepInstanceId()).ifPresent(si ->
                workflowInstanceRepository.findById(si.getWorkflowInstanceId()).ifPresent(wi ->
                        eventPublisher.publishEvent(new TaskSectionProgressEvent(
                                wi.getId(), si.getId(), task.getId(), task.getAssignedUserId(),
                                snap.getSnapSectionKey(), snap.getSnapLabel(),
                                (int) done, (int) total, allDone))));
    }

    private Optional<WiSiContext> resolveWiAndSi(Long taskInstanceId) {
        return taskInstanceRepository.findById(taskInstanceId)
                .flatMap(t -> stepInstanceRepository.findById(t.getStepInstanceId()))
                .flatMap(si -> workflowInstanceRepository.findById(si.getWorkflowInstanceId())
                        .map(wi -> new WiSiContext(wi, si)));
    }

    private record WiSiContext(WorkflowInstance wi, StepInstance si) {}

    public record ItemRegistration(String itemRefType, Long itemRefId, String label) {}
}