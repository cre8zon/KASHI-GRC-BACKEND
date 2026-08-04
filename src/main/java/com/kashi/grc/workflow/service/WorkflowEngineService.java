package com.kashi.grc.workflow.service;

import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.repository.DbRepository;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.notification.service.NotificationService;
import com.kashi.grc.workflow.domain.*;
import com.kashi.grc.workflow.dto.request.*;
import com.kashi.grc.workflow.dto.response.*;
import com.kashi.grc.workflow.enums.*;
import com.kashi.grc.workflow.spi.WorkflowActorResolverRegistry;
import com.kashi.grc.workflow.enums.ApprovalType;
import com.kashi.grc.workflow.spi.WorkflowEntityResolverRegistry;
import com.kashi.grc.workflow.repository.WorkflowStepObserverRoleRepository;
import com.kashi.grc.workflow.domain.WorkflowStepObserverRole;
import com.kashi.grc.workflow.enums.TaskRole;
import com.kashi.grc.workflow.enums.StepAction;
import com.kashi.grc.workflow.enums.AssignerResolution;
import com.kashi.grc.workflow.domain.WorkflowStepAssignerRole;
import com.kashi.grc.workflow.repository.WorkflowStepAssignerRoleRepository;
import com.kashi.grc.workflow.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.context.ApplicationEventPublisher;
import com.kashi.grc.workflow.event.WorkflowEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * WorkflowEngineService — single service for all workflow operations.
 *
 * ══ TASK GENERATION (current architecture) ═══════════════════════════════════
 *
 * Every non-SYSTEM, non-direct-user step creates TWO sets of tasks simultaneously
 * when it becomes active:
 *
 *   ASSIGNER tasks — for all users holding assignerRoles.
 *     Coordinators: they can monitor, reassign, or escalate.
 *     Approving an ASSIGNER task records acknowledgement but does NOT advance
 *     the step — only ACTOR task approvals count toward isStepApprovalSatisfied.
 *
 *   ACTOR tasks — for all users holding actorRoles (the step's role column).
 *     These users do the actual work: FILL, REVIEW, ACKNOWLEDGE, EVALUATE, APPROVE.
 *     resolvedStepAction (from snapStepAction) drives which page they navigate to.
 *     Approving an ACTOR task counts toward step completion.
 *
 * Both sets are created in assignTasksForStep() — the step starts IN_PROGRESS.
 * No manual DELEGATE action is required before actors see their tasks.
 *
 * ══ BLUEPRINT ISOLATION ════════════════════════════════════════════════════════
 *
 * StepInstance snap_* fields copy all WorkflowStep values at instance creation.
 * After that, runtime logic reads exclusively from snap_* and instance fields —
 * never from workflow_steps. Blueprint edits never affect running instances.
 * The one exception: role lookups (actorRoles, assignerRoles) still use
 * step.getId() against join tables — roles are global and intentionally live.
 *
 * ══ SUB-TASK NAV KEY OVERRIDE ════════════════════════════════════════════════
 *
 * createSubTask() now accepts an optional navKeyOverride parameter.
 * This is used by ReviewController.doCreateAssistantSubTask() to route review
 * assistant sub-tasks to /assessments/:id/assistant-review instead of the
 * reviewer's /assessments/:id/review page.
 *
 * The overloaded createSubTask(si, userId, role, tenantId, contextNote) delegates
 * to createSubTask(..., null) — all existing callers are unaffected.
 *
 * BLUEPRINT MANAGEMENT (Platform Admin only — enforced at controller):
 *   createWorkflow, updateWorkflow, createNewVersion, activateWorkflow, deactivateWorkflow
 *
 * INSTANCE MANAGEMENT (org users):
 *   startWorkflow, getInstanceById, cancelInstance, holdInstance, resumeInstance
 *
 * TASK ACTIONS (org users — 8 action types):
 *   APPROVE, REJECT, SEND_BACK, REASSIGN, DELEGATE, ESCALATE, COMMENT, WITHDRAW
 *
 * NOTIFICATIONS:
 *   Every task assignment triggers NotificationService.send() — persists to DB.
 *   Org user's notification inbox is populated automatically.
 *
 * HISTORY:
 *   Every significant state change records an entry in workflow_instance_history.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowEngineService {

    private final WorkflowRepository              workflowRepository;
    private final WorkflowStepRepository          stepRepository;
    private final WorkflowStepRoleRepository          stepRoleRepository;   // actor roles
    private final WorkflowStepAssignerRoleRepository  stepAssignerRoleRepository; // assigner roles
    private final WorkflowStepObserverRoleRepository  stepObserverRoleRepository; // observer roles
    private final WorkflowEntityResolverRegistry       entityResolverRegistry;     // artifact resolution
    private final WorkflowActorResolverRegistry        actorResolverRegistry;      // assignment-scoped actor resolution
    private final WorkflowStepUserRepository          stepUserRepository;
    private final WorkflowInstanceRepository      instanceRepository;
    private final StepInstanceRepository          stepInstanceRepository;
    private final TaskInstanceRepository          taskInstanceRepository;
    private final WorkflowTaskActionRepository    actionRepository;
    private final WorkflowInstanceHistoryRepository historyRepository;
    private final NotificationService             notificationService;
    private final DbRepository                    dbRepository;
    private final UtilityService                  utilityService;
    private final com.kashi.grc.usermanagement.repository.RoleRepository roleRepository;
    private final com.kashi.grc.usermanagement.repository.UserRepository  userRepository;
    private final com.kashi.grc.usermanagement.service.user.UserDisplayNameService userDisplayNameService;
    private final com.kashi.grc.workflow.automation.AutomatedActionRegistry automatedActionRegistry;
    // ObjectProvider, not a direct dependency — KafkaEventPublisher only
    // exists as a bean when kashi.kafka.enabled=true. getIfAvailable()
    // returns null when it's off, which is the signal to fall back to
    // synchronous dispatch (see createStepInstance).
    private final org.springframework.beans.factory.ObjectProvider<com.kashi.grc.common.kafka.KafkaEventPublisher> kafkaEventPublisherProvider;
    private final ApplicationEventPublisher eventPublisher;
    private final TaskSectionCompletionService sectionCompletionService;
    private final WorkflowStepSectionRepository stepSectionRepository;
    private final com.kashi.grc.workflow.repository.TaskSectionCompletionRepository taskSectionCompletionRepository;
    private final com.kashi.grc.workflow.repository.TaskSectionItemRepository       taskSectionItemRepository;

    // Vendor repositories — used only in getPendingTasksForUser for batch artifactId resolution
    // (avoids N×2 per-instance DB calls that caused 75s response times on the inbox)
    private final com.kashi.grc.assessment.repository.VendorAssessmentCycleRepository vendorAssessmentCycleRepository;
    private final com.kashi.grc.assessment.repository.VendorAssessmentRepository      vendorAssessmentRepository;
    // ══════════════════════════════════════════════════════════════

    @Transactional
    public WorkflowResponse createWorkflow(WorkflowCreateRequest req) {
        log.info("[WORKFLOW] Creating blueprint | name='{}' | entityType='{}' | steps={}",
                req.getName(), req.getEntityType(), req.getSteps().size());

        if (workflowRepository.existsByTenantIdIsNullAndNameAndVersion(req.getName(), 1)) {
            throw new BusinessException("WORKFLOW_DUPLICATE",
                    "Workflow '" + req.getName() + "' already exists at version 1");
        }

        Workflow workflow = Workflow.builder()
                .tenantId(null)   // global — Platform Admin owned
                .name(req.getName())
                .entityType(req.getEntityType())
                .description(req.getDescription())
                .version(1)
                .isActive(false)
                .build();
        workflowRepository.save(workflow);

        saveSteps(workflow.getId(), req.getSteps());
        log.info("[WORKFLOW] Blueprint created | id={} | name='{}' | steps={}",
                workflow.getId(), workflow.getName(), req.getSteps().size());

        return buildWorkflowResponse(workflow);
    }

    @Transactional
    public WorkflowResponse updateWorkflow(Long workflowId, WorkflowCreateRequest req) {
        log.info("[WORKFLOW] Updating blueprint | id={} | name='{}'", workflowId, req.getName());

        Workflow workflow = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new ResourceNotFoundException("Workflow", workflowId));

        // No guard on active instances — blueprints are templates, independent of their
        // running instances. Existing instances already have step_instances as a runtime
        // snapshot and continue unaffected by blueprint changes. This is the same design
        // as Camunda, Activiti, and all major workflow engines.
        workflow.setName(req.getName());
        workflow.setEntityType(req.getEntityType());
        workflow.setDescription(req.getDescription());
        workflowRepository.save(workflow);

        // Upsert steps — update existing by ID, insert new ones, delete removed ones.
        // This preserves fields not included in the request (e.g. assignerResolution
        // on steps the user didn't touch) instead of wiping them on every save.
        upsertSteps(workflowId, req.getSteps());

        log.info("[WORKFLOW] Blueprint updated | id={} | newStepCount={}", workflowId, req.getSteps().size());
        return buildWorkflowResponse(workflow);
    }

    @Transactional
    public void deleteWorkflow(Long workflowId) {
        log.info("[WORKFLOW] Deleting | id={}", workflowId);
        Workflow w = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new ResourceNotFoundException("Workflow", workflowId));

        long instanceCount = instanceRepository.countByWorkflowId(workflowId);
        if (instanceCount > 0) {
            throw new BusinessException("WORKFLOW_HAS_INSTANCES",
                    "Cannot delete workflow — " + instanceCount + " instance(s) exist. Deactivate it instead.",
                    HttpStatus.CONFLICT);
        }

        List<WorkflowStep> steps = stepRepository.findByWorkflowIdOrderByStepOrderAsc(workflowId);
        steps.forEach(s -> {
            stepRoleRepository.deleteByStepId(s.getId());
            stepUserRepository.deleteByStepId(s.getId());
            stepAssignerRoleRepository.deleteByStepId(s.getId());
            stepSectionRepository.deleteByStepId(s.getId());   // Gap 1+2
        });
        stepRepository.deleteByWorkflowId(workflowId);
        workflowRepository.delete(w);
        log.info("[WORKFLOW] Deleted | id={}", workflowId);
    }

    @Transactional
    public WorkflowResponse createNewVersion(Long workflowId) {
        log.info("[WORKFLOW] Creating new version | sourceId={}", workflowId);

        Workflow original = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new ResourceNotFoundException("Workflow", workflowId));

        int newVersion = workflowRepository
                .findTopByTenantIdIsNullAndNameOrderByVersionDesc(original.getName())
                .map(w -> w.getVersion() + 1).orElse(2);

        Workflow newWorkflow = Workflow.builder()
                .tenantId(null)
                .name(original.getName())
                .entityType(original.getEntityType())
                .description(original.getDescription())
                .version(newVersion)
                .isActive(false)
                .build();
        workflowRepository.save(newWorkflow);

        // Clone all steps, roles, and users
        stepRepository.findByWorkflowIdOrderByStepOrderAsc(workflowId).forEach(step -> {
            WorkflowStep cloned = WorkflowStep.builder()
                    .workflowId(newWorkflow.getId())
                    .name(step.getName()).description(step.getDescription())
                    .stepOrder(step.getStepOrder()).approvalType(step.getApprovalType())
                    .minApprovalsRequired(step.getMinApprovalsRequired())
                    .isParallel(step.isParallel()).isOptional(step.isOptional())
                    .slaHours(step.getSlaHours())
                    .automatedAction(step.getAutomatedAction())
                    .assignerResolution(step.getAssignerResolution())
                    .allowOverride(step.isAllowOverride())
                    .stepAction(step.getStepAction())
                    .navKey(step.getNavKey())
                    .assignerNavKey(step.getAssignerNavKey())
                    .build();
            stepRepository.save(cloned);

            stepRoleRepository.findByStepId(step.getId()).forEach(r ->
                    stepRoleRepository.save(WorkflowStepRole.builder().stepId(cloned.getId()).roleId(r.getRoleId()).build()));
            stepUserRepository.findByStepId(step.getId()).forEach(u ->
                    stepUserRepository.save(WorkflowStepUser.builder().stepId(cloned.getId()).userId(u.getUserId()).build()));
            stepAssignerRoleRepository.findByStepId(step.getId()).forEach(r ->
                    stepAssignerRoleRepository.save(WorkflowStepAssignerRole.builder().stepId(cloned.getId()).roleId(r.getRoleId()).build()));
            stepObserverRoleRepository.findByStepId(step.getId()).forEach(r ->
                    stepObserverRoleRepository.save(WorkflowStepObserverRole.builder().stepId(cloned.getId()).roleId(r.getRoleId()).build()));
            // Gap 1+2: clone sections into the new version
            stepSectionRepository.findByStepIdOrderBySectionOrderAsc(step.getId()).forEach(sec ->
                    stepSectionRepository.save(com.kashi.grc.workflow.domain.WorkflowStepSection.builder()
                            .stepId(cloned.getId())
                            .sectionKey(sec.getSectionKey()).sectionOrder(sec.getSectionOrder())
                            .label(sec.getLabel()).description(sec.getDescription())
                            .required(sec.isRequired()).completionEvent(sec.getCompletionEvent())
                            .requiresAssignment(sec.isRequiresAssignment()).tracksItems(sec.isTracksItems())
                            .build()));
        });

        log.info("[WORKFLOW] New version created | id={} | name='{}' | version={}",
                newWorkflow.getId(), newWorkflow.getName(), newVersion);
        return buildWorkflowResponse(newWorkflow);
    }

    @Transactional
    public void activateWorkflow(Long workflowId) {
        log.info("[WORKFLOW] Activating | id={}", workflowId);
        Workflow w = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new ResourceNotFoundException("Workflow", workflowId));

        List<WorkflowStep> steps = stepRepository.findByWorkflowIdOrderByStepOrderAsc(workflowId);
        if (steps.isEmpty()) throw new BusinessException("WORKFLOW_NO_STEPS", "Cannot activate workflow with no steps");

        // Validate every step has the assignments needed for task fan-out:
        //   SYSTEM steps with automatedAction — exempt, no human tasks needed.
        //   Direct-user steps (userIds) — valid, tasks created for those users.
        //   Role-based steps — must have BOTH actorRoles (who does the work) AND
        //     assignerRoles (who coordinates). Without actorRoles, no ACTOR tasks are
        //     created and the step can never reach approval. Without assignerRoles and
        //     no assignerResolution-based fallback, ASSIGNER tasks go to initiator.
        List<String> missingAssignments = new java.util.ArrayList<>();
        for (WorkflowStep s : steps) {
            if ("SYSTEM".equals(s.getSide()) && s.getAutomatedAction() != null) continue;
            boolean hasDirectUsers = !stepUserRepository.findByStepId(s.getId()).isEmpty();
            if (hasDirectUsers) continue;
            boolean hasActorRoles    = !stepRoleRepository.findByStepId(s.getId()).isEmpty();
            boolean hasAssignerRoles = !stepAssignerRoleRepository.findByStepId(s.getId()).isEmpty();
            // AssignerResolution of INITIATOR/PREVIOUS_ACTOR doesn't need explicit assignerRoles.
            boolean assignerResolutionIsRoleBased = s.getAssignerResolution() == AssignerResolution.PUSH_TO_ROLES;
            if (!hasActorRoles) {
                missingAssignments.add("Step '" + s.getName() + "' has no actorRoles — ACTOR tasks cannot be created");
            }
            if (assignerResolutionIsRoleBased && !hasAssignerRoles) {
                missingAssignments.add("Step '" + s.getName() + "' uses PUSH_TO_ROLES but has no assignerRoles");
            }
            // Gap 10: navKey mandatory — actors need a page to open or they see a dead button
            if (s.getNavKey() == null) {
                missingAssignments.add("Step '" + s.getName() + "' has no navKey — set navKey in the blueprint step configuration");
            }
            if (!stepAssignerRoleRepository.findByStepId(s.getId()).isEmpty() && s.getAssignerNavKey() == null) {
                missingAssignments.add("Step '" + s.getName() + "' has assignerRoles but no assignerNavKey — set assignerNavKey in the blueprint step configuration");
            }
        }
        if (!missingAssignments.isEmpty()) {
            throw new BusinessException("WORKFLOW_STEP_NO_ASSIGNMENTS",
                    "Cannot activate — " + String.join("; ", missingAssignments));
        }

        w.setActive(true);
        workflowRepository.save(w);
        log.info("[WORKFLOW] Activated | id={} | name='{}' | steps={}", workflowId, w.getName(), steps.size());
    }

    @Transactional
    public void deactivateWorkflow(Long workflowId) {
        log.info("[WORKFLOW] Deactivating | id={}", workflowId);
        Workflow w = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new ResourceNotFoundException("Workflow", workflowId));
        w.setActive(false);
        workflowRepository.save(w);
        log.info("[WORKFLOW] Deactivated | id={} — existing instances continue unaffected", workflowId);
    }

    // ══════════════════════════════════════════════════════════════
    // INSTANCE MANAGEMENT (logic unchanged)
    // ══════════════════════════════════════════════════════════════

    @Transactional
    public WorkflowInstanceResponse startWorkflow(StartWorkflowRequest req, Long tenantId, Long initiatedBy) {
        log.info("[WORKFLOW-INSTANCE] Starting | workflowId={} | entityType='{}' | entityId={} | tenantId={}",
                req.getWorkflowId(), req.getEntityType(), req.getEntityId(), tenantId);

        Workflow workflow = workflowRepository.findById(req.getWorkflowId())
                .orElseThrow(() -> new ResourceNotFoundException("Workflow", req.getWorkflowId()));

        if (!workflow.isActive()) throw new BusinessException("WORKFLOW_INACTIVE",
                "Workflow '" + workflow.getName() + "' is not active");

        // Prevent duplicate active instance for same entity within this org
        boolean alreadyActive = instanceRepository.existsByTenantIdAndEntityTypeAndEntityIdAndStatusIn(
                tenantId, req.getEntityType(), req.getEntityId(),
                List.of(WorkflowStatus.IN_PROGRESS, WorkflowStatus.PENDING, WorkflowStatus.ON_HOLD));
        if (alreadyActive) throw new BusinessException("INSTANCE_ALREADY_ACTIVE",
                "An active workflow instance already exists for this entity");

        WorkflowStep firstStep = stepRepository.findFirstByWorkflowIdOrderByStepOrderAsc(req.getWorkflowId())
                .orElseThrow(() -> new BusinessException("WORKFLOW_NO_STEPS", "Workflow has no steps"));

        WorkflowInstance instance = WorkflowInstance.builder()
                .tenantId(tenantId)
                .workflowId(req.getWorkflowId())
                .entityType(req.getEntityType())
                .entityId(req.getEntityId())
                .currentStepId(null)  // set after StepInstance is created below
                .status(WorkflowStatus.IN_PROGRESS)
                .startedAt(LocalDateTime.now())
                .initiatedBy(initiatedBy)
                .priority(req.getPriority() != null ? req.getPriority() : "MEDIUM")
                .dueDate(req.getDueDate())
                .remarks(req.getRemarks())
                .build();
        instanceRepository.save(instance);
        log.info("[WORKFLOW-INSTANCE] Instance created | id={} | firstStep='{}'",
                instance.getId(), firstStep.getName());

        // createStepInstance creates the StepInstance and takes the blueprint snapshot.
        // For SYSTEM steps with an automatedAction, it fires the action immediately,
        // auto-approves the step, and advances instance.currentStepId to the next step.
        // We must NOT overwrite currentStepId if that already happened.
        StepInstance stepInstance = createStepInstance(instance, firstStep);

        // Only update currentStepId if it wasn't already advanced by an automated action.
        // If currentStepId is still null, the step didn't auto-advance → point to this step.
        // If currentStepId is already set, the automated action advanced it → leave it alone.
        if (instance.getCurrentStepId() == null) {
            instance.setCurrentStepId(stepInstance.getId());
            instanceRepository.save(instance);
        }

        // assignTasksForStep now defers task creation for role-based side steps;
        // tasks are only created for explicitly named direct users on gated steps.
        assignTasksForStep(stepInstance, firstStep, instance);

        // ── Auto-complete Step 1 on workflow start if autoCompleteActorOnSubmit=true ──
        // For step 1: entity creation IS the submission (issue raised, policy drafted, etc.)
        // Steps 2-4 with the same flag fire when the owner clicks the action button.
        log.info("[WORKFLOW-AUTO] firstStep.name={} | autoCompleteActorOnSubmit={}",
                firstStep.getName(), firstStep.isAutoCompleteActorOnSubmit());
        if (firstStep.isAutoCompleteActorOnSubmit()) {
            log.info("[WORKFLOW-AUTO] Step 1 auto-complete on start | step='{}' | instanceId={}",
                    firstStep.getName(), instance.getId());
            taskInstanceRepository.findByStepInstanceId(stepInstance.getId()).stream()
                    .filter(t -> t.getStatus() == TaskStatus.PENDING && t.getTaskRole() != TaskRole.ASSIGNER)
                    .forEach(t -> {
                        t.setStatus(TaskStatus.APPROVED);
                        t.setActedAt(java.time.LocalDateTime.now());
                        t.setRemarks("Auto-completed on entity creation");
                        taskInstanceRepository.save(t);
                    });
            completeStep(stepInstance, StepStatus.APPROVED, "Auto-completed on entity creation");
            recordHistory(instance, stepInstance, null, "STEP_AUTO_COMPLETED_ON_SUBMIT",
                    StepStatus.IN_PROGRESS.name(), StepStatus.APPROVED.name(),
                    initiatedBy, "Step 1 auto-completed on workflow start");
            eventPublisher.publishEvent(new WorkflowEvent.StepCompleted(
                    instance.getId(), stepInstance.getId(), firstStep.getName(), "APPROVED", initiatedBy));
            Optional<WorkflowStep> nextStep = stepRepository
                    .findFirstByWorkflowIdAndStepOrderGreaterThanOrderByStepOrderAsc(
                            instance.getWorkflowId(), firstStep.getStepOrder());
            if (nextStep.isPresent()) {
                StepInstance nextSI = createStepInstance(instance, nextStep.get());
                instance.setCurrentStepId(nextSI.getId());
                instanceRepository.save(instance);
                assignTasksForStep(nextSI, nextStep.get(), instance);
                log.info("[WORKFLOW-AUTO] Advanced to step 2 after start | instanceId={} | to='{}'",
                        instance.getId(), nextStep.get().getName());
            }
        }
        // ── end Step 1 auto-complete ───────────────────────────────────────────

        recordHistory(instance, stepInstance, null, "WORKFLOW_STARTED",
                null, WorkflowStatus.IN_PROGRESS.name(), initiatedBy, "Workflow started");
        eventPublisher.publishEvent(new WorkflowEvent.WorkflowStarted(
                instance.getId(), workflow.getName(), instance.getEntityId(),
                instance.getEntityType(), initiatedBy));

        // Notify the initiator that the workflow is running. Step-1 assignees
        // are already notified per-task by createTask (TASK_ASSIGNMENT).
        // Email fanout for both happens inside NotificationService (Kafka).
        if (initiatedBy != null) {
            notificationService.send(initiatedBy, "WORKFLOW_STARTED",
                    "Workflow started: " + workflow.getName(),
                    instance.getEntityType(), instance.getEntityId());
        }

        log.info("[WORKFLOW-INSTANCE] Started successfully | instanceId={} | workflowName='{}' | firstStepStatus='{}'",
                instance.getId(), workflow.getName(), stepInstance.getStatus());
        return buildInstanceResponse(instance);
    }

    public WorkflowInstanceResponse getInstanceById(Long instanceId) {
        log.debug("[WORKFLOW-INSTANCE] Fetching | id={}", instanceId);
        WorkflowInstance instance = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowInstance", instanceId));
        return buildInstanceResponse(instance);
    }

    public WorkflowInstanceResponse getActiveInstanceForEntity(Long tenantId, String entityType, Long entityId) {
        log.debug("[WORKFLOW-INSTANCE] Fetching active | tenantId={} | entityType='{}' | entityId={}",
                tenantId, entityType, entityId);
        WorkflowInstance instance = instanceRepository
                .findByTenantIdAndEntityTypeAndEntityIdAndStatusIn(
                        tenantId, entityType, entityId,
                        List.of(WorkflowStatus.IN_PROGRESS, WorkflowStatus.PENDING, WorkflowStatus.ON_HOLD))
                .orElseThrow(() -> new BusinessException("NO_ACTIVE_INSTANCE",
                        "No active workflow instance for this entity"));
        return buildInstanceResponse(instance);
    }

    @Transactional
    public void cancelInstance(Long instanceId, Long performedBy, String remarks) {
        log.info("[WORKFLOW-INSTANCE] Cancelling | id={} | performedBy={}", instanceId, performedBy);
        WorkflowInstance instance = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowInstance", instanceId));
        if (instance.getStatus() == WorkflowStatus.COMPLETED || instance.getStatus() == WorkflowStatus.CANCELLED)
            throw new BusinessException("INSTANCE_TERMINAL",
                    "Cannot cancel a workflow that is already " + instance.getStatus());

        String prev = instance.getStatus().name();
        instance.setStatus(WorkflowStatus.CANCELLED);
        instance.setCompletedAt(LocalDateTime.now());
        // Null out current_step_id so the step row can be deleted if the blueprint
        // is later updated (no dangling FK on workflow_instances.current_step_id).
        instance.setCurrentStepId(null);
        instanceRepository.save(instance);

        // Cancel all step instances — they are children of this instance and should
        // reflect the terminal state. Also clears FK references from step_instances
        // to workflow_steps, allowing blueprint edits after cancellation.
        List<StepInstance> stepInstances = stepInstanceRepository.findByWorkflowInstanceId(instanceId);
        stepInstances.forEach(si -> {
            if (si.getStatus() != StepStatus.APPROVED && si.getStatus() != StepStatus.REJECTED) {
                si.setStatus(StepStatus.REJECTED); // use REJECTED as the cancelled-step marker
            }
        });
        if (!stepInstances.isEmpty()) {
            stepInstanceRepository.saveAll(stepInstances);
        }

        // Expire ALL non-terminal tasks (PENDING, IN_PROGRESS, DELEGATED) so they
        // never appear in any user's inbox after cancellation. Only APPROVED/REJECTED/
        // EXPIRED/REASSIGNED tasks are already terminal and don't need updating.
        List<Long> stepInstanceIds = stepInstances.stream().map(si -> si.getId()).toList();
        if (!stepInstanceIds.isEmpty()) {
            List<TaskInstance> staleTasks = taskInstanceRepository
                    .findByStepInstanceIdIn(stepInstanceIds)
                    .stream()
                    .filter(t -> t.getStatus() == TaskStatus.PENDING
                            || t.getStatus() == TaskStatus.IN_PROGRESS
                            || t.getStatus() == TaskStatus.DELEGATED)
                    .toList();
            if (!staleTasks.isEmpty()) {
                staleTasks.forEach(t -> {
                    t.setStatus(TaskStatus.EXPIRED);
                    t.setActedAt(LocalDateTime.now());
                    t.setRemarks("Expired — workflow instance cancelled");
                });
                taskInstanceRepository.saveAll(staleTasks);
                log.info("[WORKFLOW-INSTANCE] Expired {} stale task(s) on cancel | instanceId={}",
                        staleTasks.size(), instanceId);
            }
        }

        recordHistory(instance, null, null, "WORKFLOW_CANCELLED",
                prev, WorkflowStatus.CANCELLED.name(), performedBy, remarks);
        eventPublisher.publishEvent(new WorkflowEvent.WorkflowCancelled(
                instanceId, instance.getEntityId(), instance.getEntityType(), performedBy));
        log.info("[WORKFLOW-INSTANCE] Cancelled | id={} | previousStatus={}", instanceId, prev);
    }

    @Transactional
    public void holdInstance(Long instanceId, Long performedBy, String remarks) {
        log.info("[WORKFLOW-INSTANCE] Putting on hold | id={} | performedBy={}", instanceId, performedBy);
        WorkflowInstance instance = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowInstance", instanceId));
        if (instance.getStatus() != WorkflowStatus.IN_PROGRESS)
            throw new BusinessException("INSTANCE_NOT_IN_PROGRESS",
                    "Only IN_PROGRESS workflows can be put on hold");

        instance.setStatus(WorkflowStatus.ON_HOLD);
        instanceRepository.save(instance);
        recordHistory(instance, null, null, "WORKFLOW_ON_HOLD",
                WorkflowStatus.IN_PROGRESS.name(), WorkflowStatus.ON_HOLD.name(), performedBy, remarks);
        log.info("[WORKFLOW-INSTANCE] On hold | id={}", instanceId);
    }

    @Transactional
    public void resumeInstance(Long instanceId, Long performedBy) {
        log.info("[WORKFLOW-INSTANCE] Resuming | id={} | performedBy={}", instanceId, performedBy);
        WorkflowInstance instance = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowInstance", instanceId));
        if (instance.getStatus() != WorkflowStatus.ON_HOLD)
            throw new BusinessException("INSTANCE_NOT_ON_HOLD",
                    "Only ON_HOLD workflows can be resumed");

        instance.setStatus(WorkflowStatus.IN_PROGRESS);
        instanceRepository.save(instance);
        recordHistory(instance, null, null, "WORKFLOW_RESUMED",
                WorkflowStatus.ON_HOLD.name(), WorkflowStatus.IN_PROGRESS.name(), performedBy, "Workflow resumed");
        log.info("[WORKFLOW-INSTANCE] Resumed | id={}", instanceId);
    }

    // ══════════════════════════════════════════════════════════════
    // TASK ACTIONS — the core engine
    // ══════════════════════════════════════════════════════════════

    @Transactional
    public WorkflowInstanceResponse performAction(TaskActionRequest req, Long performedBy) {
        log.info("[WORKFLOW-ACTION] {} | taskId={} | performedBy={}",
                req.getActionType(), req.getTaskInstanceId(), performedBy);

        // Guard: taskInstanceId must be provided and valid
        if (req.getTaskInstanceId() == null) {
            throw new BusinessException("TASK_ID_REQUIRED",
                    "taskInstanceId is required for action " + req.getActionType());
        }

        TaskInstance task = taskInstanceRepository.findById(req.getTaskInstanceId())
                .orElseThrow(() -> new ResourceNotFoundException("TaskInstance", req.getTaskInstanceId()));

        // Validate task is actionable
        if (task.getStatus() != TaskStatus.PENDING && task.getStatus() != TaskStatus.IN_PROGRESS)
            throw new BusinessException("TASK_TERMINAL",
                    "Task is already in terminal state: " + task.getStatus());

        // Pessimistic write lock — serialises concurrent approvals on the same step.
        // Without this, two actors approving within milliseconds of each other both
        // read status=IN_PROGRESS, both satisfy isStepApprovalSatisfied(), and both
        // call createStepInstance for the next step → duplicate step instances → false
        // "+N revisits" badge on every subsequent workflow instance.
        // With this lock the second transaction blocks until the first commits; by then
        // the step is already APPROVED and the guard in handleApprove() exits early.
        StepInstance stepInstance = stepInstanceRepository.findByIdForUpdate(task.getStepInstanceId())
                .orElseThrow(() -> new ResourceNotFoundException("StepInstance", task.getStepInstanceId()));
        WorkflowInstance instance = instanceRepository.findById(stepInstance.getWorkflowInstanceId())
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowInstance",
                        stepInstance.getWorkflowInstanceId()));

        if (instance.getStatus() != WorkflowStatus.IN_PROGRESS)
            throw new BusinessException("INSTANCE_NOT_ACTIVE",
                    "Workflow is not IN_PROGRESS. Current: " + instance.getStatus());

        // ── NO live blueprint read here ───────────────────────────────────────
        // All routing uses instance.getWorkflowId() and stepInstance.getSnap*() fields.
        // The blueprint WorkflowStep is only loaded when we need to look up role join
        // tables (actor/assigner role IDs), which are not snapshotted on StepInstance.
        // That lookup is done inside assignTasksForStep via step.getId() — isolated there.

        WorkflowInstanceResponse response = switch (req.getActionType()) {
            case APPROVE   -> handleApprove(task, stepInstance, instance, req, performedBy);
            case REJECT    -> handleReject(task, stepInstance, instance, req, performedBy);
            case SEND_BACK -> handleSendBack(task, stepInstance, instance, req, performedBy);
            case REASSIGN  -> handleReassign(task, stepInstance, instance, req, performedBy);
            case DELEGATE  -> handleDelegate(task, stepInstance, instance, req, performedBy);
            case ESCALATE  -> handleEscalate(task, stepInstance, instance, req, performedBy);
            case COMMENT   -> handleComment(task, stepInstance, instance, req, performedBy);
            case WITHDRAW  -> handleWithdraw(task, stepInstance, instance, req, performedBy);
        };

        log.info("[WORKFLOW-ACTION] Completed | {} | taskId={} | instanceStatus={}",
                req.getActionType(), task.getId(), response.getStatus());
        return response;
    }

    // ── APPROVE (logic unchanged) ─────────────────────────────────────────────

    private WorkflowInstanceResponse handleApprove(TaskInstance task, StepInstance stepInstance,
                                                   WorkflowInstance instance, TaskActionRequest req, Long performedBy) {

        log.info("[WORKFLOW-ACTION] APPROVE | instanceId={} | step='{}' | userId={} | taskRole={}",
                instance.getId(), stepInstance.getSnapName(), performedBy, task.getTaskRole());

        // ── ASSIGNER task: record the action but do NOT advance the step ──────
        // ASSIGNER tasks are coordination tasks — the assigner has acknowledged or
        // coordinated their part. The step only advances when ACTOR tasks are approved.
        // Without this guard, an assigner approving their task would satisfy ANY_ONE
        // and skip the step entirely before any actor has done the real work.
        if (task.getTaskRole() == TaskRole.ASSIGNER) {
            updateTask(task, TaskStatus.APPROVED, req.getRemarks());
            recordAction(task, stepInstance, instance, ActionType.APPROVE, performedBy, req);
            recordHistory(instance, stepInstance, task, "ASSIGNER_ACKNOWLEDGED",
                    null, null, performedBy, req.getRemarks());
            log.info("[WORKFLOW-ACTION] ASSIGNER task approved — step not advanced | stepInstanceId={}",
                    stepInstance.getId());
            return buildInstanceResponse(instance);
        }

        // ── Section gate (Option A — full blueprint isolation) ────────────────
        // hasSections() checks task_section_completions for this taskId — no blueprint read.
        // validateReadyForApproval() reads snap_required + completed from the same table.
        // If any required section is incomplete, throws TASK_SECTIONS_INCOMPLETE with
        // the snapshotted label names so the user sees exactly what's missing.
        if (sectionCompletionService.hasSections(task.getId())) {
            sectionCompletionService.validateReadyForApproval(task.getId());
        }
        // ── End section gate ──────────────────────────────────────────────────

        updateTask(task, TaskStatus.APPROVED, req.getRemarks());
        recordAction(task, stepInstance, instance, ActionType.APPROVE, performedBy, req);

        // ── autoCompleteActorOnSubmit ─────────────────────────────────────────
        // When this flag is set on the step, submitting a FILL form IS the approval.
        // Auto-approve all remaining PENDING tasks on this step so the workflow
        // advances immediately — no separate inbox action needed.
        // Covers: issue creation/triage, policy draft submission, evidence upload, etc.
        if (Boolean.TRUE.equals(stepInstance.getSnapAutoCompleteActorOnSubmit())
                && task.getTaskRole() != TaskRole.ASSIGNER) {
            log.info("[WORKFLOW-AUTO] autoCompleteActorOnSubmit — expiring all pending tasks | stepInstanceId={}",
                    stepInstance.getId());
            // Expire all other PENDING tasks on this step (they are redundant now)
            taskInstanceRepository.findByStepInstanceId(stepInstance.getId()).stream()
                    .filter(t -> t.getStatus() == TaskStatus.PENDING && !t.getId().equals(task.getId()))
                    .forEach(t -> {
                        t.setStatus(TaskStatus.EXPIRED);
                        t.setActedAt(java.time.LocalDateTime.now());
                        taskInstanceRepository.save(t);
                    });
            // Force step completion regardless of approval_type
            completeStep(stepInstance, StepStatus.APPROVED, "Auto-completed on form submit");
            expirePendingTasks(stepInstance, task.getId());
            recordHistory(instance, stepInstance, task, "STEP_AUTO_COMPLETED_ON_SUBMIT",
                    StepStatus.IN_PROGRESS.name(), StepStatus.APPROVED.name(),
                    performedBy, "Form submitted — step auto-completed");
            eventPublisher.publishEvent(new WorkflowEvent.StepCompleted(
                    instance.getId(), stepInstance.getId(), stepInstance.getSnapName(), "APPROVED", performedBy));
            // Advance to next step (same logic as normal approval path)
            Optional<WorkflowStep> nextStep = stepRepository
                    .findFirstByWorkflowIdAndStepOrderGreaterThanOrderByStepOrderAsc(
                            instance.getWorkflowId(), stepInstance.getSnapStepOrder());
            if (nextStep.isPresent()) {
                Long currentStepIdBeforeCreate = instance.getCurrentStepId();
                StepInstance newSI = createStepInstance(instance, nextStep.get());
                if (instance.getCurrentStepId() == null ||
                        instance.getCurrentStepId().equals(currentStepIdBeforeCreate)) {
                    instance.setCurrentStepId(newSI.getId());
                    instanceRepository.save(instance);
                }
                assignTasksForStep(newSI, nextStep.get(), instance);
                recordHistory(instance, newSI, null, "STEP_STARTED",
                        null, newSI.getStatus().name(), performedBy,
                        "Moved to: " + nextStep.get().getName());
                eventPublisher.publishEvent(new WorkflowEvent.StepAdvanced(
                        instance.getId(), newSI.getId(), nextStep.get().getName(),
                        nextStep.get().getStepOrder(), newSI.getStatus().name(), performedBy));
                log.info("[WORKFLOW-AUTO] Advanced after submit | instanceId={} | to='{}'",
                        instance.getId(), nextStep.get().getName());
            } else {
                instance.setStatus(WorkflowStatus.COMPLETED);
                instance.setCurrentStepId(null);
                instance.setCompletedAt(LocalDateTime.now());
                instanceRepository.save(instance);
                recordHistory(instance, stepInstance, null, "WORKFLOW_COMPLETED",
                        WorkflowStatus.IN_PROGRESS.name(), WorkflowStatus.COMPLETED.name(),
                        performedBy, "All steps completed");
                log.info("[WORKFLOW-AUTO] COMPLETED after submit | instanceId={}", instance.getId());
            }
            return buildInstanceResponse(instance);
        }
        // ── end autoCompleteActorOnSubmit ─────────────────────────────────────

        boolean stepComplete = isStepApprovalSatisfied(stepInstance);
        log.debug("[WORKFLOW-ACTION] Step approval check | stepInstanceId={} | type={} | satisfied={}",
                stepInstance.getId(), stepInstance.getSnapApprovalType(), stepComplete);

        if (stepComplete) {
            // ── Concurrent-advance guard ──────────────────────────────────────
            // Re-read the step status from the DB after acquiring the lock.
            // If another transaction already completed this step (status is a terminal
            // approval/rejection), skip the advance to avoid duplicate next-step instances.
            // SLA_BREACHED is NOT terminal — it means the SLA timer expired but the step
            // is still running. Allow advance through SLA_BREACHED so late approvals work.
            StepStatus freshStatus = stepInstanceRepository.findById(stepInstance.getId())
                    .map(StepInstance::getStatus)
                    .orElse(StepStatus.IN_PROGRESS);
            boolean alreadyTerminated = freshStatus == StepStatus.APPROVED
                    || freshStatus == StepStatus.REJECTED
                    || freshStatus == StepStatus.SKIPPED;
            if (alreadyTerminated) {
                log.warn("[WORKFLOW-ACTION] Step already advanced by concurrent approval — skipping duplicate advance | stepInstanceId={} | freshStatus={}",
                        stepInstance.getId(), freshStatus);
                return buildInstanceResponse(instance);
            }
            // ── End concurrent-advance guard ──────────────────────────────────

            completeStep(stepInstance, StepStatus.APPROVED, req.getRemarks());
            expirePendingTasks(stepInstance, task.getId());

            recordHistory(instance, stepInstance, task, "STEP_APPROVED",
                    StepStatus.IN_PROGRESS.name(), StepStatus.APPROVED.name(),
                    performedBy, req.getRemarks());
            eventPublisher.publishEvent(new WorkflowEvent.StepCompleted(
                    instance.getId(), stepInstance.getId(), stepInstance.getSnapName(), "APPROVED", performedBy));

            // Advance to next step — use instance.workflowId + snapshot stepOrder
            // Never read from workflow_steps at runtime; blueprint may have changed.
            Optional<WorkflowStep> nextStep = stepRepository
                    .findFirstByWorkflowIdAndStepOrderGreaterThanOrderByStepOrderAsc(
                            instance.getWorkflowId(), stepInstance.getSnapStepOrder());

            if (nextStep.isPresent()) {
                Long currentStepIdBeforeCreate = instance.getCurrentStepId();
                StepInstance newSI = createStepInstance(instance, nextStep.get());
                if (instance.getCurrentStepId() == null ||
                        instance.getCurrentStepId().equals(currentStepIdBeforeCreate)) {
                    instance.setCurrentStepId(newSI.getId());
                    instanceRepository.save(instance);
                }

                assignTasksForStep(newSI, nextStep.get(), instance);

                recordHistory(instance, newSI, null, "STEP_STARTED",
                        null, newSI.getStatus().name(), performedBy,
                        "Moved to: " + nextStep.get().getName());
                eventPublisher.publishEvent(new WorkflowEvent.StepAdvanced(
                        instance.getId(), newSI.getId(), nextStep.get().getName(),
                        nextStep.get().getStepOrder(), newSI.getStatus().name(), performedBy));

                log.info("[WORKFLOW-ACTION] Advanced | instanceId={} | from='{}' | to='{}' | newStepStatus='{}'",
                        instance.getId(), stepInstance.getSnapName(), nextStep.get().getName(), newSI.getStatus());
            } else {
                instance.setStatus(WorkflowStatus.COMPLETED);
                instance.setCurrentStepId(null);
                instance.setCompletedAt(LocalDateTime.now());
                instanceRepository.save(instance);

                recordHistory(instance, stepInstance, null, "WORKFLOW_COMPLETED",
                        WorkflowStatus.IN_PROGRESS.name(), WorkflowStatus.COMPLETED.name(),
                        performedBy, "All steps completed");

                log.info("[WORKFLOW-ACTION] COMPLETED | instanceId={} | entityType='{}' | entityId={}",
                        instance.getId(), instance.getEntityType(), instance.getEntityId());
            }
        } else {
            log.debug("[WORKFLOW-ACTION] Waiting for more approvals | stepInstanceId={}",
                    stepInstance.getId());
        }
        return buildInstanceResponse(instance);
    }

    // ── REJECT (logic unchanged) ──────────────────────────────────────────────

    private WorkflowInstanceResponse handleReject(TaskInstance task, StepInstance stepInstance,
                                                  WorkflowInstance instance, TaskActionRequest req, Long performedBy) {

        log.warn("[WORKFLOW-ACTION] REJECT | instanceId={} | step='{}' | userId={} | remarks='{}'",
                instance.getId(), stepInstance.getStepId(), performedBy, req.getRemarks());

        updateTask(task, TaskStatus.REJECTED, req.getRemarks());
        recordAction(task, stepInstance, instance, ActionType.REJECT, performedBy, req);
        completeStep(stepInstance, StepStatus.REJECTED, req.getRemarks());
        expirePendingTasks(stepInstance, task.getId());

        instance.setStatus(WorkflowStatus.REJECTED);
        instance.setCompletedAt(LocalDateTime.now());
        instance.setCurrentStepId(null);
        instanceRepository.save(instance);

        recordHistory(instance, stepInstance, task, "WORKFLOW_REJECTED",
                WorkflowStatus.IN_PROGRESS.name(), WorkflowStatus.REJECTED.name(),
                performedBy, req.getRemarks());

        log.info("[WORKFLOW-ACTION] REJECTED | instanceId={}", instance.getId());
        return buildInstanceResponse(instance);
    }

    // ── SEND_BACK — supports N steps back via targetStepId ───────────────────
    // IMPORTANT: Send Back is a nuclear operation — it rolls back the ENTIRE step,
    // cancels all other actors' tasks, and restarts from a previous step.
    // This is restricted to ORG_ADMIN / ORG_OWNER only.
    // All other roles should use Action Items (Revision Request / Remediation Request / Reopen Section).

    private WorkflowInstanceResponse handleSendBack(TaskInstance task, StepInstance stepInstance,
                                                    WorkflowInstance instance, TaskActionRequest req, Long performedBy) {

        // Role guard — only ORG_ADMIN / ORG_OWNER may roll back a workflow step.
        // Use roleRepository.findByNameAndSide to avoid lazy-loading User.roles.
        Long tenantId = instance.getTenantId();
        boolean isAdmin = java.util.stream.Stream.of("ORG_ADMIN", "ORG_OWNER")
                .map(name -> roleRepository.findByNameAndSide(name, com.kashi.grc.usermanagement.domain.RoleSide.ORGANIZATION))
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .anyMatch(r -> dbRepository.findUserIdsByRoleAndTenant(r.getId(), tenantId).contains(performedBy));
        if (!isAdmin) {
            throw new BusinessException("SEND_BACK_NOT_AUTHORIZED",
                    "Only ORG_ADMIN or ORG_OWNER can roll back a workflow step. " +
                            "Use Revision Request, Reopen Section, or Remediation Request for content-level corrections.",
                    HttpStatus.FORBIDDEN);
        }

        log.info("[WORKFLOW-ACTION] SEND_BACK | instanceId={} | from='{}' | targetStepId={}",
                instance.getId(), stepInstance.getSnapName(), req.getTargetStepId());

        updateTask(task, TaskStatus.REJECTED, req.getRemarks());
        recordAction(task, stepInstance, instance, ActionType.SEND_BACK, performedBy, req);
        completeStep(stepInstance, StepStatus.REASSIGNED, req.getRemarks());
        expirePendingTasks(stepInstance, task.getId());

        // Resolve target step using instance.workflowId + snapshot stepOrder — no live blueprint read.
        WorkflowStep targetStep;
        if (req.getTargetStepId() != null) {
            targetStep = stepRepository.findById(req.getTargetStepId())
                    .orElseThrow(() -> new ResourceNotFoundException("WorkflowStep", req.getTargetStepId()));
            if (!targetStep.getWorkflowId().equals(instance.getWorkflowId())) {
                throw new BusinessException("SEND_BACK_INVALID_STEP",
                        "Target step does not belong to the same workflow");
            }
            if (targetStep.getStepOrder() >= stepInstance.getSnapStepOrder()) {
                throw new BusinessException("SEND_BACK_FORWARD_NOT_ALLOWED",
                        "SEND_BACK target must be a step with a lower stepOrder than the current step");
            }
            log.info("[WORKFLOW-ACTION] SEND_BACK — explicit target | step='{}' | stepsBack={}",
                    targetStep.getName(), stepInstance.getSnapStepOrder() - targetStep.getStepOrder());
        } else {
            targetStep = stepRepository.findFirstByWorkflowIdAndStepOrderLessThanOrderByStepOrderDesc(
                            instance.getWorkflowId(), stepInstance.getSnapStepOrder())
                    .orElseThrow(() -> new BusinessException("NO_PREVIOUS_STEP",
                            "No previous step to send back to"));
            log.info("[WORKFLOW-ACTION] SEND_BACK — previous step | step='{}'", targetStep.getName());
        }

        long previousVisits = stepInstanceRepository
                .findByWorkflowInstanceIdAndStepId(instance.getId(), targetStep.getId())
                .size();

        StepInstance newSI = createStepInstance(instance, targetStep);
        newSI.setIterationCount((int) previousVisits + 1);
        stepInstanceRepository.save(newSI);

        instance.setCurrentStepId(newSI.getId());
        instanceRepository.save(instance);

        assignTasksForStep(newSI, targetStep, instance);

        recordHistory(instance, newSI, task, "STEP_SENT_BACK",
                stepInstance.getSnapName(), targetStep.getName(), performedBy, req.getRemarks());

        log.info("[WORKFLOW-ACTION] Sent back | instanceId={} | from='{}' | to='{}' | iteration={}",
                instance.getId(), stepInstance.getSnapName(), targetStep.getName(), newSI.getIterationCount());
        return buildInstanceResponse(instance);
    }

    // ── REASSIGN ──────────────────────────────────────────────────────────────
    // The reassigned task carries the same taskRole as the original — if an ASSIGNER
    // reassigns their coordination task the replacement is also ASSIGNER; same for ACTOR.

    private WorkflowInstanceResponse handleReassign(TaskInstance task, StepInstance stepInstance,
                                                    WorkflowInstance instance, TaskActionRequest req, Long performedBy) {

        if (req.getTargetUserId() == null)
            throw new BusinessException("TARGET_USER_REQUIRED", "targetUserId is required for REASSIGN");
        log.info("[WORKFLOW-ACTION] REASSIGN | taskId={} | role={} | from={} | to={}",
                task.getId(), task.getTaskRole(), task.getAssignedUserId(), req.getTargetUserId());

        Long previousUser = task.getAssignedUserId();
        updateTask(task, TaskStatus.REASSIGNED, req.getRemarks());
        recordAction(task, stepInstance, instance, ActionType.REASSIGN, performedBy, req);

        // New task inherits the same role as the original — ASSIGNER stays ASSIGNER, ACTOR stays ACTOR
        TaskRole inheritedRole = task.getTaskRole() != null ? task.getTaskRole() : TaskRole.ACTOR;
        String inheritedSide   = inheritedRole == TaskRole.ASSIGNER
                ? task.getAssignerSide()
                : stepInstance.getSnapSide();
        TaskInstance newTask = createTask(stepInstance, req.getTargetUserId(), false,
                inheritedRole, inheritedSide, instance.getTenantId());
        newTask.setReassignedFromUserId(previousUser);
        taskInstanceRepository.save(newTask);

        transitionStepToInProgressIfAwaiting(stepInstance, instance, performedBy, "REASSIGN");

        recordHistory(instance, stepInstance, task, "TASK_REASSIGNED",
                previousUser.toString(), req.getTargetUserId().toString(), performedBy, req.getRemarks());

        log.info("[WORKFLOW-ACTION] Reassigned | newTaskId={} | role={} | from={} | to={}",
                newTask.getId(), inheritedRole, previousUser, req.getTargetUserId());
        return buildInstanceResponse(instance);
    }

    // ── DELEGATE ──────────────────────────────────────────────────────────────
    // Original task stays DELEGATED (owner keeps accountability).
    // Delegate task inherits the same role as the original.
    //
    // Special case — ACTOR task on an ASSIGN step:
    //   When the actor's job IS to pick someone (stepAction=ASSIGN), performing
    //   DELEGATE is completing that work. The step advances after the delegation
    //   exactly as if the actor had APPROVED — the "assign" work is done.
    //   The delegatee receives a task on the NEXT step, not a copy of this one.
    //
    //   Example: VRM's job on step 3 is to assign the CISO. VRM delegates to CISO.
    //   Step 3 is now complete. Step 4 starts — CISO gets their task there.
    //
    // All other cases (FILL, REVIEW, EVALUATE, etc.):
    //   Delegation hands the work to someone else on the same step.
    //   The step does NOT advance — the delegatee must still do and approve the work.

    private WorkflowInstanceResponse handleDelegate(TaskInstance task, StepInstance stepInstance,
                                                    WorkflowInstance instance, TaskActionRequest req, Long performedBy) {

        if (req.getTargetUserId() == null)
            throw new BusinessException("TARGET_USER_REQUIRED", "targetUserId is required for DELEGATE");
        if (req.getTargetUserId().equals(task.getAssignedUserId()))
            throw new BusinessException("SELF_DELEGATION_NOT_ALLOWED",
                    "You cannot delegate a task to yourself");
        log.info("[WORKFLOW-ACTION] DELEGATE | taskId={} | role={} | stepAction={} | from={} | to={}",
                task.getId(), task.getTaskRole(), stepInstance.getSnapStepAction(),
                task.getAssignedUserId(), req.getTargetUserId());

        // Mark the original task DELEGATED
        task.setStatus(TaskStatus.DELEGATED);
        task.setDelegatedToUserId(req.getTargetUserId());
        task.setActedAt(LocalDateTime.now());
        task.setRemarks(req.getRemarks());
        taskInstanceRepository.save(task);

        recordAction(task, stepInstance, instance, ActionType.DELEGATE, performedBy, req);

        // ── ASSIGN step: delegation IS the completion — advance workflow ──────
        // The actor's entire job was to pick someone. Now that they've delegated,
        // the step is done. Expire all pending tasks and move to next step.
        // The target user will receive tasks from the next step's fan-out.
        if (task.getTaskRole() == TaskRole.ACTOR
                && stepInstance.getSnapStepAction() == StepAction.ASSIGN) {

            expirePendingTasks(stepInstance, task.getId());
            completeStep(stepInstance, StepStatus.APPROVED, req.getRemarks());

            recordHistory(instance, stepInstance, task, "STEP_APPROVED",
                    StepStatus.IN_PROGRESS.name(), StepStatus.APPROVED.name(),
                    performedBy, "Assigned to userId=" + req.getTargetUserId()
                            + (req.getRemarks() != null ? " — " + req.getRemarks() : ""));

            eventPublisher.publishEvent(new WorkflowEvent.StepCompleted(
                    instance.getId(), stepInstance.getId(), stepInstance.getSnapName(),
                    "APPROVED", performedBy));

            // Advance to next step
            Optional<WorkflowStep> nextStep = stepRepository
                    .findFirstByWorkflowIdAndStepOrderGreaterThanOrderByStepOrderAsc(
                            instance.getWorkflowId(), stepInstance.getSnapStepOrder());

            if (nextStep.isPresent()) {
                Long currentStepIdBeforeCreate = instance.getCurrentStepId();
                StepInstance newSI = createStepInstance(instance, nextStep.get());
                if (instance.getCurrentStepId() == null ||
                        instance.getCurrentStepId().equals(currentStepIdBeforeCreate)) {
                    instance.setCurrentStepId(newSI.getId());
                    instanceRepository.save(instance);
                }
                assignTasksForStep(newSI, nextStep.get(), instance);
                recordHistory(instance, newSI, null, "STEP_STARTED",
                        null, newSI.getStatus().name(), performedBy,
                        "Moved to: " + nextStep.get().getName());
                log.info("[WORKFLOW-ACTION] DELEGATE+ADVANCE | step='{}' assigned to userId={} | next='{}'",
                        stepInstance.getSnapName(), req.getTargetUserId(), nextStep.get().getName());
            } else {
                instance.setStatus(WorkflowStatus.COMPLETED);
                instance.setCurrentStepId(null);
                instance.setCompletedAt(LocalDateTime.now());
                instanceRepository.save(instance);
                recordHistory(instance, stepInstance, null, "WORKFLOW_COMPLETED",
                        WorkflowStatus.IN_PROGRESS.name(), WorkflowStatus.COMPLETED.name(),
                        performedBy, "All steps completed");
            }
            return buildInstanceResponse(instance);
        }

        // ── All other steps: delegate work to target on this same step ────────
        TaskRole inheritedRole = task.getTaskRole() != null ? task.getTaskRole() : TaskRole.ACTOR;
        String inheritedSide   = inheritedRole == TaskRole.ASSIGNER
                ? task.getAssignerSide()
                : stepInstance.getSnapSide();
        TaskInstance delegateTask = createTask(stepInstance, req.getTargetUserId(), false,
                inheritedRole, inheritedSide, instance.getTenantId());
        delegateTask.setReassignedFromUserId(task.getAssignedUserId());
        taskInstanceRepository.save(delegateTask);

        transitionStepToInProgressIfAwaiting(stepInstance, instance, performedBy, "DELEGATE");

        recordHistory(instance, stepInstance, task, "TASK_DELEGATED",
                task.getAssignedUserId().toString(), req.getTargetUserId().toString(),
                performedBy, req.getRemarks());

        log.info("[WORKFLOW-ACTION] Delegated | delegateTaskId={} | role={} | to={}",
                delegateTask.getId(), inheritedRole, req.getTargetUserId());
        return buildInstanceResponse(instance);
    }

    // ── ESCALATE (logic unchanged) ────────────────────────────────────────────

    private WorkflowInstanceResponse handleEscalate(TaskInstance task, StepInstance stepInstance,
                                                    WorkflowInstance instance, TaskActionRequest req, Long performedBy) {

        log.warn("[WORKFLOW-ACTION] ESCALATE | instanceId={} | taskId={} | previousPriority={}",
                instance.getId(), task.getId(), instance.getPriority());

        recordAction(task, stepInstance, instance, ActionType.ESCALATE, performedBy, req);
        recordHistory(instance, stepInstance, task, "TASK_ESCALATED",
                instance.getPriority(), "CRITICAL", performedBy, req.getRemarks());

        instance.setPriority("CRITICAL");
        instanceRepository.save(instance);

        log.info("[WORKFLOW-ACTION] Escalated to CRITICAL | instanceId={}", instance.getId());
        return buildInstanceResponse(instance);
    }

    // ── COMMENT (logic unchanged) ─────────────────────────────────────────────

    private WorkflowInstanceResponse handleComment(TaskInstance task, StepInstance stepInstance,
                                                   WorkflowInstance instance, TaskActionRequest req, Long performedBy) {

        log.info("[WORKFLOW-ACTION] COMMENT | instanceId={} | taskId={} | userId={}",
                instance.getId(), task.getId(), performedBy);
        recordAction(task, stepInstance, instance, ActionType.COMMENT, performedBy, req);
        recordHistory(instance, stepInstance, task, "COMMENT_ADDED", null, null, performedBy, req.getRemarks());
        return buildInstanceResponse(instance);
    }

    // ── WITHDRAW (logic unchanged) ────────────────────────────────────────────

    private WorkflowInstanceResponse handleWithdraw(TaskInstance task, StepInstance stepInstance,
                                                    WorkflowInstance instance, TaskActionRequest req, Long performedBy) {

        log.warn("[WORKFLOW-ACTION] WITHDRAW | instanceId={} | taskId={} | initiatedBy={}",
                instance.getId(), task.getId(), performedBy);

        updateTask(task, TaskStatus.REJECTED, req.getRemarks());
        expirePendingTasks(stepInstance, task.getId());
        completeStep(stepInstance, StepStatus.REJECTED, "Withdrawn by initiator");

        recordAction(task, stepInstance, instance, ActionType.WITHDRAW, performedBy, req);

        instance.setStatus(WorkflowStatus.CANCELLED);
        instance.setCompletedAt(LocalDateTime.now());
        instance.setCurrentStepId(null);
        instanceRepository.save(instance);

        recordHistory(instance, stepInstance, task, "WORKFLOW_WITHDRAWN",
                WorkflowStatus.IN_PROGRESS.name(), WorkflowStatus.CANCELLED.name(),
                performedBy, req.getRemarks());

        log.info("[WORKFLOW-ACTION] Withdrawn | instanceId={}", instance.getId());
        return buildInstanceResponse(instance);
    }

    // ══════════════════════════════════════════════════════════════
    // TASK QUERIES
    // ══════════════════════════════════════════════════════════════

    /**
     * Returns enriched pending tasks for a user — includes stepName, entityType,
     * entityId, priority, workflowName so the frontend inbox can render cards
     * and resolve navigation routes correctly.
     *
     * CHANGED: now uses toEnrichedTaskResponse() instead of toTaskResponse().
     * CHANGED: filters out tasks whose parent WorkflowInstance is in a terminal
     *   status (CANCELLED, COMPLETED, REJECTED). Those tasks are stale — the
     *   workflow is done and performAction would reject any action on them anyway.
     *   This is a belt-and-suspenders filter; cancelInstance() now also expires
     *   tasks immediately on cancel, so they should already be EXPIRED by the time
     *   this query runs. The filter handles any tasks that slipped through before
     *   the cancelInstance fix was deployed.
     */
    public List<TaskInstanceResponse> getPendingTasksForUser(Long userId) {
        long t0 = System.currentTimeMillis();
        log.info("[WORKFLOW-TASK] Fetching pending tasks | userId={}", userId);

        // ── Step 1: load active tasks — single query with IN clause ──────────
        List<TaskInstance> allActiveTasks = taskInstanceRepository
                .findByAssignedUserIdAndStatusIn(userId,
                        List.of(TaskStatus.PENDING, TaskStatus.IN_PROGRESS, TaskStatus.DELEGATED));
        if (allActiveTasks.isEmpty()) {
            log.info("[WORKFLOW-TASK] No active tasks | userId={} | {}ms", userId, System.currentTimeMillis() - t0);
            return List.of();
        }

        // ── Step 2: bulk-load StepInstances ───────────────────────────────────
        Set<Long> stepIds = allActiveTasks.stream()
                .map(TaskInstance::getStepInstanceId)
                .collect(java.util.stream.Collectors.toSet());
        Map<Long, StepInstance> stepMap = stepInstanceRepository.findAllById(stepIds)
                .stream().collect(java.util.stream.Collectors.toMap(StepInstance::getId, s -> s));

        // ── Step 3: bulk-load WorkflowInstances ───────────────────────────────
        Set<Long> instanceIds = stepMap.values().stream()
                .map(StepInstance::getWorkflowInstanceId)
                .collect(java.util.stream.Collectors.toSet());
        Map<Long, WorkflowInstance> instanceMap = instanceRepository.findAllById(instanceIds)
                .stream().collect(java.util.stream.Collectors.toMap(WorkflowInstance::getId, wi -> wi));

        // ── Step 4: filter to active workflow instances ───────────────────────
        Set<WorkflowStatus> activeStatuses = Set.of(
                WorkflowStatus.IN_PROGRESS, WorkflowStatus.ON_HOLD, WorkflowStatus.PENDING);
        List<TaskInstance> filtered = allActiveTasks.stream()
                .filter(t -> {
                    StepInstance si = stepMap.get(t.getStepInstanceId());
                    if (si == null) return false;
                    WorkflowInstance wi = instanceMap.get(si.getWorkflowInstanceId());
                    return wi != null && activeStatuses.contains(wi.getStatus());
                })
                .toList();

        // ── Step 5: bulk-load Workflows (names) ───────────────────────────────
        Set<Long> workflowIds = instanceMap.values().stream()
                .map(WorkflowInstance::getWorkflowId)
                .collect(java.util.stream.Collectors.toSet());
        Map<Long, Workflow> workflowMap = workflowRepository.findAllById(workflowIds)
                .stream().collect(java.util.stream.Collectors.toMap(Workflow::getId, w -> w));

        // ── Step 6: resolve artifactIds and entity titles — ZERO per-instance DB calls ──
        //
        // The biggest performance problem: calling entityResolverRegistry.resolveArtifactId()
        // per instance fires 1-2 DB queries per instance (VendorAssessmentEntityResolver
        // does 2 queries; IssueEntityResolver does 1 existence check). With 37 tasks this
        // caused 75+ second response times.
        //
        // Fast path: for all entity types where artifactId === entityId (AUDIT_ENGAGEMENT,
        // AUDIT_PROJECT, AUDIT_POLICY, ISSUE), skip the resolver entirely. For VENDOR
        // (where artifactId is an assessmentId, not the vendorId), do a single bulk query.
        Map<Long, Long>   artifactIds   = new java.util.HashMap<>();
        Map<Long, String> entityTitles  = new java.util.HashMap<>();

        // Partition by entityType
        Map<String, List<WorkflowInstance>> byType = instanceMap.values().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        wi -> wi.getEntityType() != null ? wi.getEntityType() : "UNKNOWN"));

        // ── Generic artifact resolution via registered resolvers ─────────────────
        // No hardcoded entity type names. Each module registers a WorkflowEntityResolver.
        // If a resolver exists for the entity type, use it for the bulk fallback map.
        // Per-task step+user-aware resolution happens in Step 7 via the three-arg call.
        // For entity types with no resolver (currently only VENDOR), the legacy
        // batch-load path below handles it. Once a VendorEntityResolver is registered,
        // the default branch below can be removed entirely.
        for (Map.Entry<String, List<WorkflowInstance>> entry : byType.entrySet()) {
            String entityType = entry.getKey();
            List<WorkflowInstance> typeInstances = entry.getValue();

            if (entityResolverRegistry.supports(entityType)) {
                // Resolver registered — call single-arg for the bulk fallback map.
                // Step 7 will override with step+user-aware resolution per task.
                for (WorkflowInstance wi : typeInstances) {
                    Long resolved = entityResolverRegistry.resolveArtifactId(wi);
                    artifactIds.put(wi.getId(), resolved != null ? resolved : wi.getEntityId());
                }
            } else {
                // No resolver registered — legacy VENDOR batch-load path.
                // TODO: extract to VendorWorkflowEntityResolver @Component to eliminate this.
                Set<Long> wfIds = typeInstances.stream()
                        .map(WorkflowInstance::getId)
                        .collect(java.util.stream.Collectors.toSet());
                try {
                    var cycles = vendorAssessmentCycleRepository.findByWorkflowInstanceIdIn(wfIds);
                    Set<Long> cycleIds = cycles.stream()
                            .map(c -> c.getId())
                            .collect(java.util.stream.Collectors.toSet());
                    var assessments = vendorAssessmentRepository.findByCycleIdIn(cycleIds);
                    Map<Long, Long> cycleToAssessment = assessments.stream()
                            .collect(java.util.stream.Collectors.toMap(
                                    a -> a.getCycleId(), a -> a.getId(),
                                    (a, b) -> a));
                    Map<Long, Long> wfToAssessment = cycles.stream()
                            .filter(c -> cycleToAssessment.containsKey(c.getId()))
                            .collect(java.util.stream.Collectors.toMap(
                                    c -> c.getWorkflowInstanceId(),
                                    c -> cycleToAssessment.get(c.getId()),
                                    (a, b) -> a));
                    for (WorkflowInstance wi : typeInstances) {
                        Long aid = wfToAssessment.getOrDefault(wi.getId(), wi.getEntityId());
                        artifactIds.put(wi.getId(), aid);
                    }
                } catch (Exception e) {
                    log.warn("[WORKFLOW-TASK] Batch resolution failed — using entityId fallback: {}", e.getMessage());
                    for (WorkflowInstance wi : typeInstances) {
                        artifactIds.put(wi.getId(), wi.getEntityId());
                    }
                }
            }
        }

        // ── Step 7: build responses from pre-loaded maps — zero additional queries ──
        List<TaskInstanceResponse> result = filtered.stream().map(t -> {
            StepInstance si        = stepMap.get(t.getStepInstanceId());
            WorkflowInstance wi    = si != null ? instanceMap.get(si.getWorkflowInstanceId()) : null;
            Workflow workflow       = wi != null ? workflowMap.get(wi.getWorkflowId()) : null;
            // Always use the step-aware three-arg resolver — entity-type-agnostic.
            // Each WorkflowEntityResolver implementation decides how to resolve artifactId
            // for its own entity type and step. The engine has no knowledge of modules.
            // VENDOR artifactIds pre-loaded above are used as fallback when resolver throws.
            Long artifactId;
            try {
                artifactId = wi != null
                        ? entityResolverRegistry.resolveArtifactId(wi, si, t.getAssignedUserId())
                        : null;
            } catch (Exception ex) {
                artifactId = wi != null ? artifactIds.get(wi.getId()) : null;
            }
            String entityTitle     = wi != null ? entityTitles.get(wi.getId()) : null;

            TaskRole taskRole = t.getTaskRole() != null ? t.getTaskRole() : TaskRole.ACTOR;
            String resolvedStepSide = taskRole == TaskRole.ASSIGNER
                    ? (t.getAssignerSide() != null ? t.getAssignerSide() : "ORGANIZATION")
                    : (si != null ? si.getSnapSide() : null);
            String resolvedStepAction = taskRole == TaskRole.ASSIGNER
                    ? StepAction.ASSIGN.name()
                    : (si != null && si.getSnapStepAction() != null ? si.getSnapStepAction().name() : StepAction.APPROVE.name());
            String resolvedNavKey = taskRole == TaskRole.ASSIGNER
                    ? (si != null ? si.getSnapAssignerNavKey() : null)
                    : (si != null ? si.getSnapNavKey() : null);

            return TaskInstanceResponse.builder()
                    .id(t.getId())
                    .stepInstanceId(t.getStepInstanceId())
                    .assignedUserId(t.getAssignedUserId())
                    .status(t.getStatus())
                    .taskRole(taskRole)
                    .actedAt(t.getActedAt())
                    .dueAt(t.getDueAt())
                    .remarks(t.getRemarks())
                    .delegatedToUserId(t.getDelegatedToUserId())
                    .reassignedFromUserId(t.getReassignedFromUserId())
                    .isAutoAssigned(t.isAutoAssigned())
                    .assignedAt(t.getCreatedAt())
                    .stepName(si != null ? si.getSnapName() : null)
                    .stepOrder(si != null ? si.getSnapStepOrder() : null)
                    .resolvedStepSide(resolvedStepSide)
                    .resolvedStepAction(resolvedStepAction)
                    .actorRoleName(t.getActorRoleName())
                    .navKey(resolvedNavKey)
                    .workflowInstanceId(wi != null ? wi.getId() : null)
                    .entityType(wi != null ? wi.getEntityType() : null)
                    .entityId(wi != null ? wi.getEntityId() : null)
                    .workflowName(workflow != null ? workflow.getName() : null)
                    .workflowId(wi != null ? wi.getWorkflowId() : null)
                    .priority(wi != null ? wi.getPriority() : null)
                    .workflowInstanceStatus(wi != null ? wi.getStatus().name() : null)
                    .artifactId(artifactId)
                    .entityTitle(entityTitle)
                    .build();
        }).sorted(java.util.Comparator.comparing(
                TaskInstanceResponse::getAssignedAt,
                java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())
        )).toList();

        log.info("[WORKFLOW-TASK] Returning {} tasks | userId={} | {}ms",
                result.size(), userId, System.currentTimeMillis() - t0);
        return result;
    }

    /**
     * Returns all tasks for a user across all statuses — enriched with context.
     *
     * CHANGED: now uses toEnrichedTaskResponse() instead of toTaskResponse().
     */
    public List<TaskInstanceResponse> getAllTasksForUser(Long userId) {
        long t0 = System.currentTimeMillis();
        log.info("[WORKFLOW-TASK] Fetching ALL tasks | userId={}", userId);

        List<TaskInstance> tasks = taskInstanceRepository.findByAssignedUserId(userId);
        if (tasks.isEmpty()) return List.of();

        // Bulk-load all context in the same way as getPendingTasksForUser
        Set<Long> stepIds = tasks.stream().map(TaskInstance::getStepInstanceId)
                .collect(java.util.stream.Collectors.toSet());
        Map<Long, StepInstance> stepMap = stepInstanceRepository.findAllById(stepIds)
                .stream().collect(java.util.stream.Collectors.toMap(StepInstance::getId, s -> s));

        Set<Long> instanceIds = stepMap.values().stream().map(StepInstance::getWorkflowInstanceId)
                .collect(java.util.stream.Collectors.toSet());
        Map<Long, WorkflowInstance> instanceMap = instanceRepository.findAllById(instanceIds)
                .stream().collect(java.util.stream.Collectors.toMap(WorkflowInstance::getId, wi -> wi));

        Set<Long> workflowIds = instanceMap.values().stream().map(WorkflowInstance::getWorkflowId)
                .collect(java.util.stream.Collectors.toSet());
        Map<Long, Workflow> workflowMap = workflowRepository.findAllById(workflowIds)
                .stream().collect(java.util.stream.Collectors.toMap(Workflow::getId, w -> w));

        Map<Long, Long>   artifactIds  = new java.util.HashMap<>();
        Map<Long, String> entityTitles = new java.util.HashMap<>();
        for (WorkflowInstance wi : instanceMap.values()) {
            try { Long aid = entityResolverRegistry.resolveArtifactId(wi);
                if (aid != null) artifactIds.put(wi.getId(), aid); } catch (Exception ignored) {}
            try { String t = entityResolverRegistry.resolveEntityTitle(wi);
                if (t != null) entityTitles.put(wi.getId(), t); } catch (Exception ignored) {}
        }

        List<TaskInstanceResponse> result = tasks.stream().map(t -> {
            StepInstance si     = stepMap.get(t.getStepInstanceId());
            WorkflowInstance wi = si != null ? instanceMap.get(si.getWorkflowInstanceId()) : null;
            Workflow wf         = wi != null ? workflowMap.get(wi.getWorkflowId()) : null;
            TaskRole role = t.getTaskRole() != null ? t.getTaskRole() : TaskRole.ACTOR;
            String side = role == TaskRole.ASSIGNER
                    ? (t.getAssignerSide() != null ? t.getAssignerSide() : "ORGANIZATION")
                    : (si != null ? si.getSnapSide() : null);
            String action = role == TaskRole.ASSIGNER ? StepAction.ASSIGN.name()
                    : (si != null && si.getSnapStepAction() != null ? si.getSnapStepAction().name() : StepAction.APPROVE.name());
            String navKey = role == TaskRole.ASSIGNER
                    ? (si != null ? si.getSnapAssignerNavKey() : null)
                    : (si != null ? si.getSnapNavKey() : null);
            return TaskInstanceResponse.builder()
                    .id(t.getId()).stepInstanceId(t.getStepInstanceId())
                    .assignedUserId(t.getAssignedUserId()).status(t.getStatus()).taskRole(role)
                    .actedAt(t.getActedAt()).dueAt(t.getDueAt()).assignedAt(t.getCreatedAt())
                    .remarks(t.getRemarks()).delegatedToUserId(t.getDelegatedToUserId())
                    .reassignedFromUserId(t.getReassignedFromUserId()).isAutoAssigned(t.isAutoAssigned())
                    .stepName(si != null ? si.getSnapName() : null)
                    .stepOrder(si != null ? si.getSnapStepOrder() : null)
                    .resolvedStepSide(side).resolvedStepAction(action)
                    .actorRoleName(t.getActorRoleName()).navKey(navKey)
                    .workflowInstanceId(wi != null ? wi.getId() : null)
                    .entityType(wi != null ? wi.getEntityType() : null)
                    .entityId(wi != null ? wi.getEntityId() : null)
                    .workflowName(wf != null ? wf.getName() : null)
                    .workflowId(wi != null ? wi.getWorkflowId() : null)
                    .priority(wi != null ? wi.getPriority() : null)
                    .workflowInstanceStatus(wi != null ? wi.getStatus().name() : null)
                    .artifactId(wi != null ? artifactIds.get(wi.getId()) : null)
                    .entityTitle(wi != null ? entityTitles.get(wi.getId()) : null)
                    .build();
        }).toList();

        log.info("[WORKFLOW-TASK] Returning {} tasks | userId={} | {}ms",
                result.size(), userId, System.currentTimeMillis() - t0);
        return result;
    }

    /**
     * Returns all task instances in a step — for step-level detail views.
     * Uses the flat toTaskResponse() since step context is already known by the caller.
     * Logic unchanged.
     */
    public List<TaskInstanceResponse> getTasksForStepInstance(Long stepInstanceId) {
        return taskInstanceRepository.findByStepInstanceId(stepInstanceId)
                .stream().map(this::toTaskResponse).toList();
    }

    /**
     * Manually assigns a task to a specific user — used after external role resolution
     * and also when an org user explicitly assigns from the inbox.
     *
     * CHANGED: now transitions the step from AWAITING_ASSIGNMENT → IN_PROGRESS
     * when the first task is created on a gated step.
     */
    /**
     * Advances a step directly without requiring a task.
     * Used by users with workflow:step:override permission (e.g. lead auditor).
     * Records the override in history and publishes step events.
     */
    /**
     * Retries all stuck IN_PROGRESS SYSTEM steps whose automated action was not
     * executed (e.g. because the action handler returned false on creation, or the
     * backend was restarted mid-execution). Called by StepSlaMonitor every 5 minutes.
     */
    @Transactional
    public void retryStuckSystemSteps() {
        List<StepInstance> stuckSteps = stepInstanceRepository
                .findByStatus(StepStatus.IN_PROGRESS).stream()
                .filter(s -> "SYSTEM".equals(s.getSnapSide()))
                .toList();

        for (StepInstance si : stuckSteps) {
            try {
                WorkflowStep step = stepRepository.findById(si.getStepId()).orElse(null);
                if (step == null || step.getAutomatedAction() == null) continue;

                WorkflowInstance instance = instanceRepository
                        .findById(si.getWorkflowInstanceId()).orElse(null);
                if (instance == null) continue;

                com.kashi.grc.workflow.automation.AutomatedActionContext ctx =
                        com.kashi.grc.workflow.automation.AutomatedActionContext.builder()
                                .workflowInstance(instance)
                                .step(step)
                                .stepInstance(si)
                                .tenantId(instance.getTenantId())
                                .initiatedBy(instance.getInitiatedBy())
                                .build();

                Optional<Boolean> result = automatedActionRegistry.dispatch(step.getAutomatedAction(), ctx);

                if (result.isPresent() && Boolean.TRUE.equals(result.get())) {
                    completeStep(si, StepStatus.APPROVED,
                            "Auto-approved by " + step.getAutomatedAction() + " (retry)");
                    recordHistory(instance, si, null, "STEP_AUTO_APPROVED",
                            StepStatus.IN_PROGRESS.name(), StepStatus.APPROVED.name(),
                            instance.getInitiatedBy(),
                            "Retry: automated action '" + step.getAutomatedAction() + "' completed");
                    eventPublisher.publishEvent(new WorkflowEvent.StepCompleted(
                            instance.getId(), si.getId(), si.getSnapName(), "APPROVED",
                            instance.getInitiatedBy()));
                    stepRepository.findFirstByWorkflowIdAndStepOrderGreaterThanOrderByStepOrderAsc(
                                    instance.getWorkflowId(), si.getSnapStepOrder())
                            .ifPresent(nextStep -> {
                                StepInstance nextSI = createStepInstance(instance, nextStep);
                                instance.setCurrentStepId(nextSI.getId());
                                instanceRepository.save(instance);
                                assignTasksForStep(nextSI, nextStep, instance);
                                log.info("[WORKFLOW-RETRY] Advanced | instanceId={} | to='{}'",
                                        instance.getId(), nextStep.getName());
                            });
                } else {
                    log.debug("[WORKFLOW-RETRY] System step still not ready | stepInstanceId={} | action={}",
                            si.getId(), step.getAutomatedAction());
                }
            } catch (Exception e) {
                log.error("[WORKFLOW-RETRY] Error retrying stepInstanceId={} | {}", si.getId(), e.getMessage(), e);
            }
        }
    }

    public WorkflowInstanceResponse overrideAdvanceStep(Long stepInstanceId, String action,
                                                        String remarks, Long performedBy) {
        StepInstance si = stepInstanceRepository.findById(stepInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("StepInstance", stepInstanceId));

        if (si.getStatus() != StepStatus.IN_PROGRESS) {
            throw new BusinessException("STEP_NOT_ACTIVE",
                    "Step is not currently active — cannot override.");
        }

        WorkflowInstance instance = instanceRepository.findById(si.getWorkflowInstanceId())
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowInstance", si.getWorkflowInstanceId()));

        StepStatus newStatus = "REJECT".equalsIgnoreCase(action)
                ? StepStatus.REJECTED : StepStatus.APPROVED;

        String overrideNote = "Step override by userId=" + performedBy
                + (remarks != null && !remarks.isBlank() ? ": " + remarks : "");

        // Expire all pending tasks on this step
        expirePendingTasks(si, null);

        // Complete the step
        completeStep(si, newStatus, overrideNote);

        recordHistory(instance, si, null, "STEP_OVERRIDE",
                StepStatus.IN_PROGRESS.name(), newStatus.name(),
                performedBy, overrideNote);

        eventPublisher.publishEvent(new WorkflowEvent.StepCompleted(
                instance.getId(), si.getId(), si.getSnapName(), newStatus.name(), performedBy));

        log.info("[WORKFLOW-OVERRIDE] Step {} | stepInstanceId={} | by={}",
                newStatus, stepInstanceId, performedBy);

        if (newStatus == StepStatus.APPROVED) {
            // Advance to next step
            Optional<WorkflowStep> nextStep = stepRepository
                    .findFirstByWorkflowIdAndStepOrderGreaterThanOrderByStepOrderAsc(
                            instance.getWorkflowId(), si.getSnapStepOrder());
            if (nextStep.isPresent()) {
                Long prevCurrentStepId = instance.getCurrentStepId();
                StepInstance newSI = createStepInstance(instance, nextStep.get());
                if (instance.getCurrentStepId() == null ||
                        instance.getCurrentStepId().equals(prevCurrentStepId)) {
                    instance.setCurrentStepId(newSI.getId());
                    instanceRepository.save(instance);
                }
                assignTasksForStep(newSI, nextStep.get(), instance);
                recordHistory(instance, newSI, null, "STEP_STARTED",
                        null, newSI.getStatus().name(), performedBy,
                        "Moved to: " + nextStep.get().getName());
                eventPublisher.publishEvent(new WorkflowEvent.StepAdvanced(
                        instance.getId(), newSI.getId(), nextStep.get().getName(),
                        nextStep.get().getStepOrder(), newSI.getStatus().name(), performedBy));
                log.info("[WORKFLOW-OVERRIDE] Advanced | instanceId={} | to='{}'",
                        instance.getId(), nextStep.get().getName());
            } else {
                instance.setStatus(WorkflowStatus.COMPLETED);
                instance.setCurrentStepId(null);
                instanceRepository.save(instance);
                log.info("[WORKFLOW-OVERRIDE] Workflow COMPLETED | instanceId={}", instance.getId());
            }
        }

        return buildInstanceResponse(instance);
    }

    public TaskInstanceResponse assignTaskToUser(Long stepInstanceId, Long userId, Long assignedBy) {
        log.info("[WORKFLOW-TASK] Manual assignment | stepInstanceId={} | userId={} | assignedBy={}",
                stepInstanceId, userId, assignedBy);

        StepInstance si = stepInstanceRepository.findById(stepInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("StepInstance", stepInstanceId));
        WorkflowInstance instance = instanceRepository.findById(si.getWorkflowInstanceId())
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowInstance",
                        si.getWorkflowInstanceId()));

        TaskInstance task = createTask(si, userId, true, instance.getTenantId());

        // Snapshot sections for this manually assigned task — same isolation guarantee
        sectionCompletionService.snapshotSectionsForTask(task, si, instance);

        // Transition step to IN_PROGRESS if it was AWAITING_ASSIGNMENT
        transitionStepToInProgressIfAwaiting(si, instance, assignedBy, "Manually assigned");

        recordHistory(instance, si, task, "TASK_ASSIGNED",
                null, TaskStatus.PENDING.name(), assignedBy, "Manually assigned");

        log.info("[WORKFLOW-TASK] Task assigned | taskId={} | userId={} | stepStatus={}",
                task.getId(), userId, si.getStatus());
        return toEnrichedTaskResponse(task);
    }

    // ══════════════════════════════════════════════════════════════
    // HISTORY QUERIES (logic unchanged)
    // ══════════════════════════════════════════════════════════════

    public List<WorkflowHistoryResponse> getFullHistory(Long instanceId) {
        return toHistoryResponses(
                historyRepository.findByWorkflowInstanceIdOrderByPerformedAtAsc(instanceId));
    }

    /**
     * Batch counterpart to getFullHistory — for callers that need history
     * across MULTIPLE workflow instances (e.g. every engagement under a
     * project). Was previously done by calling getFullHistory() once per
     * instance in a loop and concatenating the results instance-by-instance
     * (so two engagements running in parallel would NOT interleave
     * chronologically). This issues one query ordered by performedAt across
     * the WHOLE set of instances — a true merged timeline — plus one
     * batched name resolution, instead of N of each.
     */
    public List<WorkflowHistoryResponse> getFullHistoryForInstances(Collection<Long> instanceIds) {
        if (instanceIds == null || instanceIds.isEmpty()) return List.of();
        return toHistoryResponses(
                historyRepository.findByWorkflowInstanceIdInOrderByPerformedAtAsc(instanceIds));
    }

    public List<WorkflowHistoryResponse> getHistoryByStep(Long instanceId, Long stepId) {
        return toHistoryResponses(
                historyRepository.findByWorkflowInstanceIdAndStepIdOrderByPerformedAtAsc(instanceId, stepId));
    }

    public Workflow getWorkflowById(Long id) {
        return workflowRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Workflow", id));
    }

    /**
     * Completes a SYSTEM step that is stuck IN_PROGRESS (its automated action
     * returned false while waiting for human input) and advances the workflow to
     * the next step.
     *
     * Used by the template-selection endpoint: after the ORG_ADMIN/ORG_OWNER picks
     * a template, the QUEUE_ASSESSMENT_CANDIDATES step can be approved so
     * EXECUTE_ASSESSMENT fires automatically on the next step.
     *
     * Mirrors the auto-advance block inside createStepInstance() but is exposed
     * publicly so non-engine code can drive the advance without duplicating the logic.
     */
    @org.springframework.transaction.annotation.Transactional
    public void completeSystemStepAndAdvance(Long stepInstanceId, Long performedBy, String remarks) {
        StepInstance si = stepInstanceRepository.findById(stepInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("StepInstance", stepInstanceId));
        WorkflowInstance instance = instanceRepository.findById(si.getWorkflowInstanceId())
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowInstance", si.getWorkflowInstanceId()));
        WorkflowStep step = stepRepository.findById(si.getStepId())
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowStep", si.getStepId()));

        completeStep(si, StepStatus.APPROVED, remarks);
        recordHistory(instance, si, null, "STEP_MANUALLY_ADVANCED",
                StepStatus.IN_PROGRESS.name(), StepStatus.APPROVED.name(),
                performedBy, remarks);
        eventPublisher.publishEvent(new WorkflowEvent.StepCompleted(
                instance.getId(), si.getId(), si.getSnapName(), "APPROVED", performedBy));

        stepRepository.findFirstByWorkflowIdAndStepOrderGreaterThanOrderByStepOrderAsc(
                        step.getWorkflowId(), step.getStepOrder())
                .ifPresentOrElse(
                        nextStep -> {
                            StepInstance nextSI = createStepInstance(instance, nextStep);
                            instance.setCurrentStepId(nextSI.getId());
                            instanceRepository.save(instance);
                            assignTasksForStep(nextSI, nextStep, instance);
                            recordHistory(instance, nextSI, null, "STEP_STARTED",
                                    null, nextSI.getStatus().name(), performedBy,
                                    "Moved to: " + nextStep.getName());
                            log.info("[WORKFLOW] System step manually advanced | instanceId={} | nextStep='{}'",
                                    instance.getId(), nextStep.getName());
                        },
                        () -> {
                            instance.setStatus(WorkflowStatus.COMPLETED);
                            instance.setCurrentStepId(null);
                            instance.setCompletedAt(java.time.LocalDateTime.now());
                            instanceRepository.save(instance);
                            recordHistory(instance, si, null, "WORKFLOW_COMPLETED",
                                    WorkflowStatus.IN_PROGRESS.name(), WorkflowStatus.COMPLETED.name(),
                                    performedBy, "All steps completed");
                            log.info("[WORKFLOW] Workflow COMPLETED after manual advance | instanceId={}", instance.getId());
                        }
                );
    }


    public List<WorkflowHistoryResponse> getHistoryByUser(Long userId, Long tenantId) {
        return toHistoryResponses(
                historyRepository.findByPerformedByAndTenantId(userId, tenantId));
    }

    /**
     * GET /v1/workflow-instances/{id}/progress
     *
     * Returns a rich per-step progress summary for a workflow instance.
     * Designed to scale across any number of workflows — each step entry contains:
     *
     *   - Blueprint step metadata (name, order, side, roles)
     *   - StepInstance runtime state (status, started, completed, SLA, iteration)
     *   - All tasks on this step: who was assigned, their name, status, when they acted
     *   - Derived fields: isCurrentStep, isSlaBreached, durationMinutes
     *
     * This replaces the need to query the DB directly to understand workflow state.
     * The frontend can render a full timeline/kanban/gantt from this single response.
     */
    public List<Map<String, Object>> getInstanceProgress(Long instanceId) {
        WorkflowInstance instance = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowInstance", instanceId));

        Workflow workflow = workflowRepository.findById(instance.getWorkflowId()).orElse(null);
        // Progress is driven by what actually ran (step instances + snapshots).
        // Blueprint steps are still loaded for: role display (join table) and totalSteps count.
        List<WorkflowStep> blueprintSteps =
                stepRepository.findByWorkflowIdOrderByStepOrderAsc(instance.getWorkflowId());
        List<StepInstance> stepInstances =
                stepInstanceRepository.findByWorkflowInstanceIdOrderByCreatedAtAsc(instanceId);

        // Group step instances by blueprint stepId (for send-back revisit tracking)
        // Step instances with same stepId are grouped as iterations of the same logical step
        Map<Long, List<StepInstance>> instancesByStepId = stepInstances.stream()
                .collect(Collectors.groupingBy(StepInstance::getStepId));

        // ── Bulk-load to eliminate N+1 queries ────────────────────────────────
        List<Long> allStepIds = blueprintSteps.stream().map(WorkflowStep::getId).toList();
        List<Long> allStepInstanceIds = stepInstances.stream().map(StepInstance::getId).toList();

        Map<Long, List<Long>> roleIdsByStepId = stepRoleRepository.findByStepIdIn(allStepIds)
                .stream()
                .collect(Collectors.groupingBy(
                        WorkflowStepRole::getStepId,
                        Collectors.mapping(WorkflowStepRole::getRoleId, Collectors.toList())));

        List<TaskInstance> allTasks = allStepInstanceIds.isEmpty()
                ? List.of()
                : taskInstanceRepository.findByStepInstanceIdIn(allStepInstanceIds);

        Map<Long, List<TaskInstance>> tasksByStepInstanceId = allTasks.stream()
                .collect(Collectors.groupingBy(TaskInstance::getStepInstanceId));

        Set<Long> allUserIds = allTasks.stream()
                .flatMap(t -> java.util.stream.Stream.of(t.getAssignedUserId(), t.getDelegatedToUserId()))
                .filter(id -> id != null)
                .collect(java.util.stream.Collectors.toSet());
        Map<Long, String> globalUserNameMap = userRepository.findAllById(allUserIds).stream()
                .collect(java.util.stream.Collectors.toMap(
                        com.kashi.grc.usermanagement.domain.User::getId,
                        u -> {
                            String fn = u.getFirstName() != null ? u.getFirstName() : "";
                            String ln = u.getLastName()  != null ? u.getLastName()  : "";
                            String full = (fn + " " + ln).trim();
                            return full.isEmpty() ? u.getEmail() : full;
                        }));
        // ── end bulk-load ─────────────────────────────────────────────────────

        List<Map<String, Object>> progress = new ArrayList<>();

        for (WorkflowStep step : blueprintSteps) {
            List<StepInstance> instances_for_step =
                    instancesByStepId.getOrDefault(step.getId(), List.of());

            // Role IDs from bulk-loaded map — no per-step DB query
            List<Long> roleIds = roleIdsByStepId.getOrDefault(step.getId(), List.of());

            // Build per-iteration data for this step
            List<Map<String, Object>> iterations = new ArrayList<>();
            for (StepInstance si : instances_for_step) {
                // Use bulk-loaded map — zero per-step-instance DB queries
                List<TaskInstance> tasks = tasksByStepInstanceId.getOrDefault(si.getId(), List.of());
                Map<Long, String> userNameMap = globalUserNameMap;

                List<Map<String, Object>> taskDetails = tasks.stream().map(t -> {
                    String userName       = userNameMap.getOrDefault(t.getAssignedUserId(),
                            "User #" + t.getAssignedUserId());
                    String delegatedToName = t.getDelegatedToUserId() != null
                            ? userNameMap.getOrDefault(t.getDelegatedToUserId(),
                            "User #" + t.getDelegatedToUserId()) : null;

                    // Use LinkedHashMap instead of Map.of — Map.of rejects null values
                    // and has a 10-key limit which we hit exactly.
                    Map<String, Object> taskMap = new LinkedHashMap<>();
                    taskMap.put("taskId",            t.getId());
                    taskMap.put("assignedUserId",    t.getAssignedUserId());
                    taskMap.put("assignedUserName",  userName);
                    taskMap.put("taskRole",          t.getTaskRole() != null ? t.getTaskRole().name() : "ACTOR");
                    taskMap.put("status",            t.getStatus().name());
                    taskMap.put("assignedAt",        t.getCreatedAt());
                    taskMap.put("actedAt",           t.getActedAt());
                    taskMap.put("delegatedToUserId", t.getDelegatedToUserId());
                    taskMap.put("delegatedToName",   delegatedToName);
                    taskMap.put("remarks",           t.getRemarks());
                    return taskMap;
                }).toList();

                boolean slaBreached = si.getSlaDueAt() != null
                        && si.getCompletedAt() == null
                        && LocalDateTime.now().isAfter(si.getSlaDueAt());
                boolean slaBreachedOnComplete = si.getSlaDueAt() != null
                        && si.getCompletedAt() != null
                        && si.getCompletedAt().isAfter(si.getSlaDueAt());

                Long durationMinutes = null;
                if (si.getStartedAt() != null) {
                    LocalDateTime end = si.getCompletedAt() != null
                            ? si.getCompletedAt() : LocalDateTime.now();
                    durationMinutes = java.time.Duration.between(si.getStartedAt(), end).toMinutes();
                }

                Map<String, Object> iter = new LinkedHashMap<>();
                iter.put("stepInstanceId",   si.getId());
                iter.put("status",           si.getStatus().name());
                iter.put("iterationCount",   si.getIterationCount());
                iter.put("startedAt",        si.getStartedAt());
                iter.put("completedAt",      si.getCompletedAt());
                iter.put("slaDueAt",         si.getSlaDueAt());
                iter.put("slaBreached",      slaBreached || slaBreachedOnComplete);
                iter.put("durationMinutes",  durationMinutes);
                iter.put("remarks",          si.getRemarks());
                iter.put("tasks",            taskDetails);
                iterations.add(iter);
            }

            // Latest iteration is the current/most-recent one
            StepInstance latestSI = instances_for_step.isEmpty() ? null
                    : instances_for_step.get(instances_for_step.size() - 1);

            boolean isCurrentStep = instance.getCurrentStepId() != null
                    && latestSI != null
                    && instance.getCurrentStepId().equals(latestSI.getId());

            // Display fields from snapshot if step has run, else fall back to blueprint
            // (unvisited future steps haven't created an instance yet)
            String displayName   = latestSI != null ? latestSI.getSnapName()   : step.getName();
            Integer displayOrder = latestSI != null ? latestSI.getSnapStepOrder() : step.getStepOrder();
            String displaySide   = latestSI != null ? latestSI.getSnapSide()   : step.getSide();
            String displayAction = latestSI != null && latestSI.getSnapAutomatedAction() != null
                    ? latestSI.getSnapAutomatedAction() : step.getAutomatedAction();
            String displayApproval = latestSI != null && latestSI.getSnapApprovalType() != null
                    ? latestSI.getSnapApprovalType().name() : step.getApprovalType().name();
            Integer displaySla   = latestSI != null ? latestSI.getSnapSlaHours() : step.getSlaHours();

            Map<String, Object> stepProgress = new LinkedHashMap<>();
            stepProgress.put("stepId",          step.getId());
            stepProgress.put("stepInstanceId",  latestSI != null ? latestSI.getId() : null);
            stepProgress.put("stepName",        displayName);
            stepProgress.put("stepOrder",       displayOrder);
            stepProgress.put("side",            displaySide);
            stepProgress.put("automatedAction", displayAction);
            stepProgress.put("roleIds",         roleIds);
            stepProgress.put("approvalType",    displayApproval);
            stepProgress.put("slaHours",        displaySla);
            stepProgress.put("isCurrentStep",   isCurrentStep);
            stepProgress.put("visited",         !instances_for_step.isEmpty());
            stepProgress.put("timesVisited",    instances_for_step.size());
            stepProgress.put("currentStatus",   latestSI != null ? latestSI.getStatus().name() : "PENDING");
            stepProgress.put("iterations",      iterations);
            progress.add(stepProgress);
        }

        // Summary header
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("workflowInstanceId", instanceId);
        summary.put("workflowName",       workflow != null ? workflow.getName() : null);
        summary.put("instanceStatus",     instance.getStatus().name());
        summary.put("startedAt",          instance.getStartedAt());
        summary.put("completedAt",        instance.getCompletedAt());
        summary.put("priority",           instance.getPriority());
        summary.put("totalSteps",         blueprintSteps.size());
        summary.put("stepsVisited",       (int) progress.stream().filter(s -> (boolean) s.get("visited")).count());
        summary.put("stepsCompleted",     (int) progress.stream()
                .filter(s -> "APPROVED".equals(s.get("currentStatus"))).count());
        summary.put("steps",              progress);

        return List.of(summary);
    }

    /**
     * One-time (and idempotent) maintenance operation — fixes terminal workflow instances
     * that still have current_step_id set and step instances that are still non-terminal.
     *
     * WHY THIS EXISTS:
     *   The old cancelInstance() code did not null out current_step_id or cancel step
     *   instances. This left FK references from workflow_instances.current_step_id to
     *   workflow_steps.id, causing a constraint violation when the blueprint was later
     *   edited (delete+recreate of steps). This method retroactively cleans that data.
     *
     * WHAT IT DOES:
     *   For every CANCELLED/COMPLETED/REJECTED workflow instance:
     *     1. Nulls current_step_id (removes the FK reference to workflow_steps)
     *     2. Marks any non-terminal step instances as REJECTED
     *     3. Expires any lingering PENDING tasks
     *
     * IDEMPOTENT: safe to run multiple times — already-clean instances are skipped.
     *
     * @return summary map with counts of instances fixed
     */
    @Transactional
    public Map<String, Object> fixTerminalInstances() {
        log.info("[MAINTENANCE] fixTerminalInstances started");

        List<WorkflowInstance> dirty = instanceRepository.findAll().stream()
                .filter(wi -> (wi.getStatus() == WorkflowStatus.CANCELLED
                        || wi.getStatus() == WorkflowStatus.COMPLETED
                        || wi.getStatus() == WorkflowStatus.REJECTED)
                        && wi.getCurrentStepId() != null)
                .toList();

        int instancesFixed    = 0;
        int stepInstancesFixed = 0;
        int tasksExpired      = 0;

        for (WorkflowInstance wi : dirty) {
            wi.setCurrentStepId(null);
            instanceRepository.save(wi);
            instancesFixed++;

            List<StepInstance> stepInstances =
                    stepInstanceRepository.findByWorkflowInstanceId(wi.getId());

            List<StepInstance> nonTerminal = stepInstances.stream()
                    .filter(si -> si.getStatus() != StepStatus.APPROVED
                            && si.getStatus() != StepStatus.REJECTED)
                    .toList();
            nonTerminal.forEach(si -> si.setStatus(StepStatus.REJECTED));
            if (!nonTerminal.isEmpty()) {
                stepInstanceRepository.saveAll(nonTerminal);
                stepInstancesFixed += nonTerminal.size();
            }

            List<Long> stepInstanceIds = stepInstances.stream().map(StepInstance::getId).toList();
            if (!stepInstanceIds.isEmpty()) {
                List<TaskInstance> pending = taskInstanceRepository
                        .findByStepInstanceIdInAndStatus(stepInstanceIds, TaskStatus.PENDING);
                pending.forEach(t -> {
                    t.setStatus(TaskStatus.EXPIRED);
                    t.setActedAt(LocalDateTime.now());
                    t.setRemarks("Expired by maintenance fix — workflow instance is " + wi.getStatus());
                });
                if (!pending.isEmpty()) {
                    taskInstanceRepository.saveAll(pending);
                    tasksExpired += pending.size();
                }
            }
        }

        log.info("[MAINTENANCE] fixTerminalInstances done | instancesFixed={} | stepInstancesFixed={} | tasksExpired={}",
                instancesFixed, stepInstancesFixed, tasksExpired);

        return Map.of(
                "instancesFixed",     instancesFixed,
                "stepInstancesFixed", stepInstancesFixed,
                "tasksExpired",       tasksExpired,
                "message",            instancesFixed == 0
                        ? "No dirty instances found — data is already clean"
                        : "Fixed " + instancesFixed + " terminal instance(s)"
        );
    }

    // ══════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ══════════════════════════════════════════════════════════════

    /** Saves steps, roles and users for a new or updated workflow blueprint. Logic unchanged. */
    /**
     * Upserts steps for a workflow blueprint.
     *
     * For each step in the request:
     *   - req.id present  → UPDATE that specific step row, preserving any fields not sent
     *   - req.id null     → INSERT a new step row
     * After → DELETE any existing steps whose IDs were not in the request (removed steps).
     *
     * This replaces the old delete-all/recreate pattern which wiped all step config
     * (assignerResolution, allowOverride, etc.) on every blueprint edit, even when
     * the user only changed one step.
     */
    private void upsertSteps(Long workflowId, List<WorkflowStepRequest> stepRequests) {
        // Collect IDs of steps present in the request
        Set<Long> incomingIds = stepRequests.stream()
                .filter(r -> r.getId() != null)
                .map(WorkflowStepRequest::getId)
                .collect(java.util.stream.Collectors.toSet());

        // Delete steps that were removed from the blueprint
        List<WorkflowStep> existing = stepRepository.findByWorkflowIdOrderByStepOrderAsc(workflowId);
        existing.stream()
                .filter(s -> !incomingIds.contains(s.getId()))
                .forEach(s -> {
                    stepRoleRepository.deleteByStepId(s.getId());
                    stepUserRepository.deleteByStepId(s.getId());
                    stepAssignerRoleRepository.deleteByStepId(s.getId());
                    stepObserverRoleRepository.deleteByStepId(s.getId());
                    stepRepository.delete(s);
                });

        // Upsert each step in the request
        for (WorkflowStepRequest req : stepRequests) {
            WorkflowStep step;

            if (req.getId() != null) {
                // UPDATE — load existing, apply only the fields that were sent
                step = stepRepository.findById(req.getId()).orElse(null);
                if (step == null || !step.getWorkflowId().equals(workflowId)) {
                    // ID doesn't match this workflow — treat as new
                    step = WorkflowStep.builder().workflowId(workflowId).build();
                }
            } else {
                // INSERT — new step
                step = WorkflowStep.builder().workflowId(workflowId).build();
            }

            // Apply all editable fields
            step.setName(req.getName());
            step.setDescription(req.getDescription());
            step.setStepOrder(req.getStepOrder());
            step.setSide(req.getSide());
            step.setApprovalType(req.getApprovalType() != null ? req.getApprovalType() : ApprovalType.ANY_ONE);
            step.setMinApprovalsRequired(req.getMinApprovalsRequired() != null ? req.getMinApprovalsRequired() : 1);
            step.setParallel(Boolean.TRUE.equals(req.getIsParallel()));
            step.setOptional(Boolean.TRUE.equals(req.getIsOptional()));
            step.setSlaHours(req.getSlaHours());
            step.setAutomatedAction(req.getAutomatedAction());
            if (req.getStepAction() != null) {
                step.setStepAction(req.getStepAction());
            }
            if (req.getNavKey() != null) {
                step.setNavKey(req.getNavKey());
            }
            if (req.getAssignerNavKey() != null) {
                step.setAssignerNavKey(req.getAssignerNavKey());
            }

            // Preserve existing assignerResolution if not explicitly sent
            if (req.getAssignerResolution() != null) {
                step.setAssignerResolution(req.getAssignerResolution());
            } else if (step.getAssignerResolution() == null) {
                // New step with no resolution — default to INITIATOR so workflow never stalls
                step.setAssignerResolution(AssignerResolution.INITIATOR);
            }
            // else: existing step, resolution not changed — keep whatever was there
            if (req.getActorResolution() != null) {
                step.setActorResolution(req.getActorResolution());
            } else if (step.getActorResolution() == null) {
                step.setActorResolution(ActorResolution.ROLE_BASED);
            }

            if (req.getAllowOverride() != null) {
                step.setAllowOverride(req.getAllowOverride());
            }

            stepRepository.save(step);

            // Capture as effectively final for use in lambdas below
            final Long savedStepId = step.getId();

            // Rebuild role associations (always replace — roles are a full set)
            stepRoleRepository.deleteByStepId(savedStepId);
            stepUserRepository.deleteByStepId(savedStepId);
            stepAssignerRoleRepository.deleteByStepId(savedStepId);

            if (req.getRoleIds() != null) req.getRoleIds().forEach(rid ->
                    stepRoleRepository.save(WorkflowStepRole.builder()
                            .stepId(savedStepId).roleId(rid).build()));
            if (req.getUserIds() != null) req.getUserIds().forEach(uid ->
                    stepUserRepository.save(WorkflowStepUser.builder()
                            .stepId(savedStepId).userId(uid).build()));
            if (req.getAssignerRoleIds() != null) req.getAssignerRoleIds().forEach(rid ->
                    stepAssignerRoleRepository.save(WorkflowStepAssignerRole.builder()
                            .stepId(savedStepId).roleId(rid).build()));
            stepObserverRoleRepository.deleteByStepId(savedStepId);
            if (req.getObserverRoleIds() != null) req.getObserverRoleIds().forEach(rid ->
                    stepObserverRoleRepository.save(WorkflowStepObserverRole.builder()
                            .stepId(savedStepId).roleId(rid).build()));

            // Gap 1+2: upsert sections — delete removed, update/insert the rest
            if (req.getSections() != null) {
                java.util.List<Long> keepIds = req.getSections().stream()
                        .filter(sr -> sr.getId() != null)
                        .map(com.kashi.grc.workflow.dto.request.StepSectionRequest::getId)
                        .collect(java.util.stream.Collectors.toList());
                if (keepIds.isEmpty()) {
                    stepSectionRepository.deleteByStepId(savedStepId);
                } else {
                    stepSectionRepository.deleteByStepIdAndIdNotIn(savedStepId, keepIds);
                }
                int sectionOrder = 1;
                for (com.kashi.grc.workflow.dto.request.StepSectionRequest sr : req.getSections()) {
                    if (sr.getSectionKey() == null || sr.getCompletionEvent() == null || sr.getLabel() == null) continue;
                    com.kashi.grc.workflow.domain.WorkflowStepSection section;
                    if (sr.getId() != null) {
                        section = stepSectionRepository.findById(sr.getId())
                                .orElse(com.kashi.grc.workflow.domain.WorkflowStepSection.builder().stepId(savedStepId).build());
                    } else {
                        section = com.kashi.grc.workflow.domain.WorkflowStepSection.builder().stepId(savedStepId).build();
                    }
                    section.setSectionKey(sr.getSectionKey());
                    section.setSectionOrder(sr.getSectionOrder() != null ? sr.getSectionOrder() : sectionOrder);
                    section.setLabel(sr.getLabel());
                    section.setDescription(sr.getDescription());
                    section.setRequired(sr.isRequired());
                    section.setCompletionEvent(sr.getCompletionEvent());
                    section.setRequiresAssignment(sr.isRequiresAssignment());
                    section.setTracksItems(sr.isTracksItems());
                    stepSectionRepository.save(section);
                    sectionOrder++;
                }
            }

            log.debug("[WORKFLOW] Step upserted | id={} | name='{}' | order={} | resolution={}",
                    savedStepId, step.getName(), step.getStepOrder(), step.getAssignerResolution());
        }
    }

    private void saveSteps(Long workflowId, List<WorkflowStepRequest> stepRequests) {
        for (WorkflowStepRequest req : stepRequests) {
            WorkflowStep step = WorkflowStep.builder()
                    .workflowId(workflowId).name(req.getName()).description(req.getDescription())
                    .stepOrder(req.getStepOrder()).approvalType(req.getApprovalType())
                    .side(req.getSide())
                    .minApprovalsRequired(req.getMinApprovalsRequired() != null
                            ? req.getMinApprovalsRequired() : 1)
                    .isParallel(Boolean.TRUE.equals(req.getIsParallel()))
                    .isOptional(Boolean.TRUE.equals(req.getIsOptional()))
                    .slaHours(req.getSlaHours())
                    .automatedAction(req.getAutomatedAction())
                    // Default assignerResolution to POOL if not explicitly set.
                    // POOL = shared queue, role holders self-select. Safest default
                    // since it never silently drops steps — role holders can always see it.
                    .assignerResolution(req.getAssignerResolution() != null
                            ? req.getAssignerResolution()
                            : AssignerResolution.POOL)
                    .actorResolution(req.getActorResolution() != null
                            ? req.getActorResolution()
                            : ActorResolution.ROLE_BASED)
                    .autoApproveAssignerOnFill(Boolean.TRUE.equals(req.getAutoApproveAssignerOnFill()))
                    .allowOverride(req.getAllowOverride() != null ? req.getAllowOverride() : true)
                    .stepAction(req.getStepAction())
                    .navKey(req.getNavKey())
                    .assignerNavKey(req.getAssignerNavKey())
                    .build();
            stepRepository.save(step);

            // Actor roles — who does the work
            if (req.getRoleIds() != null) req.getRoleIds().forEach(rid ->
                    stepRoleRepository.save(WorkflowStepRole.builder()
                            .stepId(step.getId()).roleId(rid).build()));
            if (req.getUserIds() != null) req.getUserIds().forEach(uid ->
                    stepUserRepository.save(WorkflowStepUser.builder()
                            .stepId(step.getId()).userId(uid).build()));
            // Assigner roles — who drives the assignment (PUSH_TO_ROLES only, any side)
            if (req.getAssignerRoleIds() != null) req.getAssignerRoleIds().forEach(rid ->
                    stepAssignerRoleRepository.save(WorkflowStepAssignerRole.builder()
                            .stepId(step.getId()).roleId(rid).build()));
            if (req.getObserverRoleIds() != null) req.getObserverRoleIds().forEach(rid ->
                    stepObserverRoleRepository.save(WorkflowStepObserverRole.builder()
                            .stepId(step.getId()).roleId(rid).build()));

            // Gap 1+2: persist compound-task sections alongside the step
            stepSectionRepository.deleteByStepId(step.getId());
            if (req.getSections() != null) {
                int sectionOrder = 1;
                for (com.kashi.grc.workflow.dto.request.StepSectionRequest sr : req.getSections()) {
                    if (sr.getSectionKey() == null || sr.getCompletionEvent() == null || sr.getLabel() == null) continue;
                    stepSectionRepository.save(com.kashi.grc.workflow.domain.WorkflowStepSection.builder()
                            .stepId(step.getId())
                            .sectionKey(sr.getSectionKey())
                            .sectionOrder(sr.getSectionOrder() != null ? sr.getSectionOrder() : sectionOrder)
                            .label(sr.getLabel())
                            .description(sr.getDescription())
                            .required(sr.isRequired())
                            .completionEvent(sr.getCompletionEvent())
                            .requiresAssignment(sr.isRequiresAssignment())
                            .tracksItems(sr.isTracksItems())
                            .build());
                    sectionOrder++;
                }
            }

            log.debug("[WORKFLOW] Step saved | name='{}' | order={} | roles={} | users={}",
                    step.getName(), step.getStepOrder(),
                    req.getRoleIds() != null ? req.getRoleIds().size() : 0,
                    req.getUserIds() != null ? req.getUserIds().size() : 0);
        }
    }

    /**
     * Creates a StepInstance for the given step within the workflow instance.
     *
     * Initial status is AWAITING_ASSIGNMENT — the step is created but not yet active.
     * assignTasksForStep() transitions the step to IN_PROGRESS once both ASSIGNER and
     * ACTOR tasks have been fanned out. For SYSTEM steps, the automated action handler
     * transitions directly to APPROVED without going through assignTasksForStep.
     *
     * This keeps status ownership clean:
     *   createStepInstance  → AWAITING_ASSIGNMENT  (step exists, no tasks yet)
     *   assignTasksForStep  → IN_PROGRESS           (tasks fanned out, work begins)
     *   automated action    → APPROVED              (SYSTEM step auto-completed)
     *
     * Blueprint snapshot fields are copied at this moment — after this the running
     * instance is isolated from any blueprint edits.
     */
    private StepInstance createStepInstance(WorkflowInstance instance, WorkflowStep step) {
        LocalDateTime slaDueAt = step.getSlaHours() != null
                ? LocalDateTime.now().plusHours(step.getSlaHours()) : null;

        StepInstance si = StepInstance.builder()
                .workflowInstanceId(instance.getId())
                .stepId(step.getId())        // soft ref for audit only
                .status(StepStatus.AWAITING_ASSIGNMENT)   // transitions to IN_PROGRESS in assignTasksForStep
                .startedAt(LocalDateTime.now())
                .slaDueAt(slaDueAt)
                .iterationCount(1)
                // ── Blueprint snapshot — instance is isolated from blueprint changes ──
                .snapName(step.getName())
                .snapDescription(step.getDescription())
                .snapStepOrder(step.getStepOrder())
                .snapSide(step.getSide())
                .snapApprovalType(step.getApprovalType())
                .snapMinApprovals(step.getMinApprovalsRequired())
                .snapIsParallel(step.isParallel())
                .snapIsOptional(step.isOptional())
                .snapSlaHours(step.getSlaHours())
                .snapAutomatedAction(step.getAutomatedAction())
                .snapAssignerResolution(step.getAssignerResolution())
                .snapActorResolution(step.getActorResolution())
                .snapAutoApproveAssignerOnFill(step.isAutoApproveAssignerOnFill())
                .snapAutoCompleteActorOnSubmit(step.isAutoCompleteActorOnSubmit())
                .snapAllowOverride(step.isAllowOverride())
                .snapStepAction(step.getStepAction())
                .snapNavKey(step.getNavKey())
                .snapAssignerNavKey(step.getAssignerNavKey())
                .snapUiOverrideJson(step.getStepUiOverrideJson())
                .snapSodRulesJson(step.getSodRulesJson())
                .snapAssignableSide(step.getAssignableSide())
                .snapAssignableRoleId(step.getAssignableRoleId())
                .build();
        stepInstanceRepository.save(si);

        log.debug("[WORKFLOW] StepInstance created | id={} | step='{}' | status='{}' | slaDueAt={}",
                si.getId(), step.getName(), si.getStatus(), slaDueAt);

        // ── Fire automated action for SYSTEM steps ────────────────────────────
        // If the step declares an automatedAction, dispatch it immediately.
        // On success the handler returns true → auto-approve the step and advance.
        // On failure (returns false) or no handler found → step stays IN_PROGRESS
        // so a human can investigate and manually approve or retry.
        //
        // EXCEPTION: EXECUTE_ASSESSMENT is dispatched via Kafka instead of
        // inline. This method (createStepInstance) runs on whatever thread
        // caused the PREVIOUS step to complete — often a live HTTP request
        // (e.g. an org admin picking a template, or vendor onboarding). Even
        // after batching the DB writes and caching the read side (see
        // AssessmentTemplateStructureCacheService / ExecuteAssessmentAction),
        // that handler is real work that has no business blocking an
        // unrelated HTTP response. Every other automated action stays
        // synchronous — this is a deliberate, narrow exception for the one
        // handler that does meaningful bulk work, not a general async
        // dispatch mechanism.
        boolean dispatchedAsync = false;
        if ("SYSTEM".equals(step.getSide()) && "EXECUTE_ASSESSMENT".equals(step.getAutomatedAction())) {
            com.kashi.grc.common.kafka.KafkaEventPublisher publisher = kafkaEventPublisherProvider.getIfAvailable();
            if (publisher != null) {
                // Same IN_PROGRESS status the synchronous failure branch below
                // already uses for "handler pending" — which means
                // retryStuckSystemSteps() (the existing 5-minute sweep) is an
                // automatic safety net here for free: if the Kafka message is
                // ever lost, or the consumer crashes mid-flight, that sweep
                // picks this step up and falls back to synchronous dispatch,
                // exactly as it already does today for any handler that
                // returned false. No new failure mode was introduced.
                si.setStatus(StepStatus.IN_PROGRESS);
                stepInstanceRepository.save(si);
                publisher.publish(
                        com.kashi.grc.common.kafka.KafkaTopics.ASSESSMENT_EXECUTE_REQUESTED,
                        "EXECUTE_ASSESSMENT_REQUESTED",
                        String.valueOf(instance.getId()),
                        java.util.Map.of(
                                "workflowInstanceId", instance.getId(),
                                "stepInstanceId", si.getId()),
                        instance.getTenantId(), instance.getInitiatedBy());
                log.info("[WORKFLOW-AUTO] EXECUTE_ASSESSMENT dispatched via Kafka | instanceId={} | stepInstanceId={}",
                        instance.getId(), si.getId());
                dispatchedAsync = true;
            }
            // publisher == null (kashi.kafka.enabled=false) — fall through to
            // the normal synchronous path below, same "flip a flag, zero
            // blast radius" contract as every other Kafka producer call site.
        }

        if (!dispatchedAsync && "SYSTEM".equals(step.getSide()) && step.getAutomatedAction() != null) {
            com.kashi.grc.workflow.automation.AutomatedActionContext ctx =
                    com.kashi.grc.workflow.automation.AutomatedActionContext.builder()
                            .workflowInstance(instance)
                            .step(step)
                            .stepInstance(si)
                            .tenantId(instance.getTenantId())
                            .initiatedBy(instance.getInitiatedBy())
                            .build();

            Optional<Boolean> result =
                    automatedActionRegistry.dispatch(step.getAutomatedAction(), ctx);

            if (result.isPresent() && Boolean.TRUE.equals(result.get())) {
                // Auto-approve: mark step APPROVED and advance to next step
                completeStep(si, StepStatus.APPROVED, "Auto-approved by " + step.getAutomatedAction());
                recordHistory(instance, si, null, "STEP_AUTO_APPROVED",
                        StepStatus.IN_PROGRESS.name(), StepStatus.APPROVED.name(),
                        instance.getInitiatedBy(),
                        "Automated action '" + step.getAutomatedAction() + "' completed successfully");

                // Advance workflow to next step
                stepRepository.findFirstByWorkflowIdAndStepOrderGreaterThanOrderByStepOrderAsc(
                                step.getWorkflowId(), step.getStepOrder())
                        .ifPresentOrElse(
                                nextStep -> {
                                    StepInstance nextSI = createStepInstance(instance, nextStep);
                                    instance.setCurrentStepId(nextSI.getId());
                                    instanceRepository.save(instance);
                                    assignTasksForStep(nextSI, nextStep, instance);
                                    recordHistory(instance, nextSI, null, "STEP_STARTED",
                                            null, nextSI.getStatus().name(), instance.getInitiatedBy(),
                                            "Moved to: " + nextStep.getName());
                                    log.info("[WORKFLOW-AUTO] Advanced after '{}' | instanceId={} | nextStep='{}'",
                                            step.getAutomatedAction(), instance.getId(), nextStep.getName());
                                },
                                () -> {
                                    // No next step — workflow complete
                                    instance.setStatus(WorkflowStatus.COMPLETED);
                                    instance.setCurrentStepId(null);
                                    instance.setCompletedAt(LocalDateTime.now());
                                    instanceRepository.save(instance);
                                    recordHistory(instance, si, null, "WORKFLOW_COMPLETED",
                                            WorkflowStatus.IN_PROGRESS.name(), WorkflowStatus.COMPLETED.name(),
                                            instance.getInitiatedBy(), "All steps completed");
                                    log.info("[WORKFLOW-AUTO] COMPLETED after final automated step | instanceId={}",
                                            instance.getId());
                                }
                        );
            } else {
                // BUGFIX: this branch previously only logged "step stays IN_PROGRESS"
                // without actually setting that status — SYSTEM steps were left at
                // their initial AWAITING_ASSIGNMENT status forever when the automated
                // action returned false/empty (e.g. MonitorProjectEngagementsAction
                // returning false because not all engagements are ready yet).
                // assignTasksForStep() does nothing for SYSTEM steps (early return),
                // so nothing downstream was ever fixing this either — the step had
                // no path forward except a manual DB update.
                si.setStatus(StepStatus.IN_PROGRESS);
                stepInstanceRepository.save(si);
                log.warn("[WORKFLOW-AUTO] Action '{}' failed or no handler — step '{}' set to IN_PROGRESS | instanceId={}",
                        step.getAutomatedAction(), step.getName(), instance.getId());
            }
        }

        return si;
    }

    /**
     * Creates all tasks for a step when it becomes active.
     *
     * ── WHAT CHANGED ──────────────────────────────────────────────────────────
     * Every non-SYSTEM, non-direct-user step now creates TWO sets of tasks at the
     * same time:
     *
     *   ASSIGNER tasks — for all users holding assignerRoles.
     *     taskRole = ASSIGNER, resolvedStepAction = ASSIGN
     *     These users coordinate: they can see who is doing the work, reassign,
     *     or escalate. Approving their ASSIGNER task is a no-op for step advancement.
     *
     *   ACTOR tasks — for all users holding actorRoles (the step's role column).
     *     taskRole = ACTOR, resolvedStepAction = step.stepAction (FILL/REVIEW/etc.)
     *     These users do the actual work. Approving their ACTOR task counts toward
     *     isStepApprovalSatisfied() and can advance the workflow.
     *
     * Both sets are created immediately — the step transitions from AWAITING_ASSIGNMENT
     * to IN_PROGRESS once tasks are fanned out. No manual DELEGATE action required.
     *
     * ── WHY ───────────────────────────────────────────────────────────────────
     * The previous model created only ASSIGNER tasks and required an explicit
     * DELEGATE action to create the ACTOR task. This meant:
     *   - Users with FILL/REVIEW/ACKNOWLEDGE roles never got tasks automatically.
     *   - The UI showed the step to everyone but blocked action until the assigner
     *     delegated, so "delegate" errors appeared on running instances.
     *   - Only APPROVE tasks were ever generated, collapsing all step types into one.
     *
     * ── UNCHANGED BEHAVIOUR ───────────────────────────────────────────────────
     *   - SYSTEM steps: no tasks, automated action fires.
     *   - Direct-user steps (userIds on blueprint): tasks created for those users only.
     *   - AssignerResolution is still read for PUSH_TO_ROLES to know which specific
     *     assigner roles to push to (vs INITIATOR/PREVIOUS_ACTOR fallbacks).
     *   - Role lookups still use step.getId() (blueprint join tables) — roles are
     *     global and not snapshotted, which is correct for the global-roles model.
     */
    private void assignTasksForStep(StepInstance si, WorkflowStep step, WorkflowInstance instance) {
        Long tenantId = instance.getTenantId();

        // ── Guard: skip already-terminal steps ────────────────────────────────
        if (si.getStatus() == StepStatus.APPROVED || si.getStatus() == StepStatus.REJECTED) {
            log.debug("[WORKFLOW] assignTasksForStep skipped — step '{}' is {} | stepInstanceId={}",
                    si.getSnapName(), si.getStatus(), si.getId());
            return;
        }

        // ── SYSTEM steps: automated, no tasks ───────────────────────────────
        if ("SYSTEM".equals(si.getSnapSide())) {
            log.debug("[WORKFLOW] Step '{}' side=SYSTEM — automated, no tasks", si.getSnapName());
            return;
        }

        // ── Direct-user steps: tasks for named users only ────────────────────
        // Blueprint has specific userIds — bypass role resolution entirely.
        List<WorkflowStepUser> directUsers = stepUserRepository.findByStepId(step.getId());
        if (!directUsers.isEmpty()) {
            directUsers.forEach(su -> {
                TaskInstance t = createTask(si, su.getUserId(), false, TaskRole.ACTOR, si.getSnapSide(), tenantId);
                sectionCompletionService.snapshotSectionsForTask(t, si, instance);
            });
            si.setStatus(StepStatus.IN_PROGRESS);
            stepInstanceRepository.save(si);
            log.info("[WORKFLOW] Tasks created for {} direct user(s) | step='{}'",
                    directUsers.size(), si.getSnapName());
            return;
        }

        // ── Role-based steps: create ASSIGNER + ACTOR tasks simultaneously ───
        //
        // Exception: ASSIGN steps skip the ASSIGNER task entirely.
        //   On an ASSIGN step the actor's job IS to pick someone — they are
        //   already both the actor and the coordinator. Creating an ASSIGNER task
        //   on top gives the same user two redundant tasks for the same work.
        //   The ACTOR task routes to the ASSIGN UI; performing DELEGATE on it
        //   advances the step (see handleDelegate). No separate coordinator needed.
        boolean isAssignStep = si.getSnapStepAction() == StepAction.ASSIGN;

        // ASSIGNER tasks first — based on assignerResolution strategy.
        AssignerResolution resolution = si.getSnapAssignerResolution() != null
                ? si.getSnapAssignerResolution()
                : AssignerResolution.INITIATOR;

        if (!isAssignStep) {
            log.info("[WORKFLOW] Role-based step '{}' | resolution={} | side={} | stepInstanceId={}",
                    si.getSnapName(), resolution, si.getSnapSide(), si.getId());

            switch (resolution) {

                case NONE -> {
                    // No assigner tasks — actor is resolved directly (ENTITY_CREATOR / ENTITY_OWNER).
                    // Skip coordinator tasks entirely to keep the step clean.
                    log.info("[WORKFLOW] Step '{}' assigner_resolution=NONE — skipping ASSIGNER tasks", si.getSnapName());
                }

                case POOL -> {
                    TaskInstance t = createTask(si, instance.getInitiatedBy(), true, TaskRole.ASSIGNER, "ORGANIZATION", tenantId);
                    sectionCompletionService.snapshotSectionsForTask(t, si, instance);
                    log.info("[WORKFLOW] Step '{}' POOL — ASSIGNER task for initiator (placeholder)", si.getSnapName());
                }

                case PUSH_TO_ROLES -> {
                    List<WorkflowStepAssignerRole> assignerRoles =
                            stepAssignerRoleRepository.findByStepId(step.getId());
                    if (assignerRoles.isEmpty()) {
                        log.warn("[WORKFLOW] Step '{}' PUSH_TO_ROLES but no assignerRoles — ASSIGNER task for initiator",
                                si.getSnapName());
                        TaskInstance t = createTask(si, instance.getInitiatedBy(), true, TaskRole.ASSIGNER, "ORGANIZATION", tenantId);
                        sectionCompletionService.snapshotSectionsForTask(t, si, instance);
                    } else {
                        int assignerTaskCount = 0;
                        for (WorkflowStepAssignerRole ar : assignerRoles) {
                            // For vendor-side steps scope to this workflow's specific vendor.
                            // Org-side assigner roles (e.g. ORG_CISO) are tenant-wide — no vendorId filter.
                            List<Long> uids = "VENDOR".equalsIgnoreCase(si.getSnapSide())
                                    ? dbRepository.findUserIdsByRoleAndVendor(ar.getRoleId(), tenantId, instance.getEntityId())
                                    : dbRepository.findUserIdsByRoleAndTenant(ar.getRoleId(), tenantId);
                            for (Long uid : uids) {
                                TaskInstance t = createTask(si, uid, true, TaskRole.ASSIGNER, "ORGANIZATION", tenantId);
                                sectionCompletionService.snapshotSectionsForTask(t, si, instance);
                                assignerTaskCount++;
                            }
                        }
                        log.info("[WORKFLOW] Step '{}' PUSH_TO_ROLES — {} ASSIGNER task(s) for {} role(s)",
                                si.getSnapName(), assignerTaskCount, assignerRoles.size());
                    }
                }

                case PREVIOUS_ACTOR -> {
                    Long previousActor = getPreviousStepActor(instance);
                    if (previousActor != null) {
                        // Deduplicate: skip ASSIGNER task if this user already has an ACTOR task
                        // on this step. Happens when PREVIOUS_ACTOR resolves to a user who is
                        // also an actor in the current step (e.g. responder in step 4 → step 5).
                        boolean alreadyActor = taskInstanceRepository
                                .findByStepInstanceId(si.getId())
                                .stream()
                                .anyMatch(t -> previousActor.equals(t.getAssignedUserId())
                                        && t.getTaskRole() == TaskRole.ACTOR);
                        if (alreadyActor) {
                            log.info("[WORKFLOW] Step '{}' PREVIOUS_ACTOR — skipping ASSIGNER task for userId={} (already ACTOR on this step)",
                                    si.getSnapName(), previousActor);
                        } else {
                            TaskInstance t = createTask(si, previousActor, true, TaskRole.ASSIGNER, si.getSnapSide(), tenantId);
                            sectionCompletionService.snapshotSectionsForTask(t, si, instance);
                            log.info("[WORKFLOW] Step '{}' PREVIOUS_ACTOR — ASSIGNER task for userId={}",
                                    si.getSnapName(), previousActor);
                        }
                    } else {
                        TaskInstance t = createTask(si, instance.getInitiatedBy(), true, TaskRole.ASSIGNER, "ORGANIZATION", tenantId);
                        sectionCompletionService.snapshotSectionsForTask(t, si, instance);
                        log.info("[WORKFLOW] Step '{}' PREVIOUS_ACTOR — no prior actor, ASSIGNER task for initiator",
                                si.getSnapName());
                    }
                }

                case INITIATOR -> {
                    TaskInstance t = createTask(si, instance.getInitiatedBy(), true, TaskRole.ASSIGNER, "ORGANIZATION", tenantId);
                    sectionCompletionService.snapshotSectionsForTask(t, si, instance);
                    log.info("[WORKFLOW] Step '{}' INITIATOR — ASSIGNER task for initiator userId={}",
                            si.getSnapName(), instance.getInitiatedBy());
                }
            }
        } else {
            log.info("[WORKFLOW] Step '{}' stepAction=ASSIGN — skipping ASSIGNER tasks; ACTOR delegate advances step",
                    si.getSnapName());
        }

        // ── ACTOR tasks: fan out based on actorResolution ────────────────────
        // ROLE_BASED (default): all role holders get a task (POOL behaviour).
        // ASSIGNMENT_SCOPED:    WorkflowActorResolverRegistry returns only the
        //                       users who were explicitly assigned work in a prior
        //                       step. Falls back to ROLE_BASED if resolver returns
        //                       empty (nobody assigned yet — step must not stall).
        List<WorkflowStepRole> actorRoles = stepRoleRepository.findByStepId(step.getId());
        int actorTaskCount = 0;

        ActorResolution actorResolution = si.getSnapActorResolution() != null
                ? si.getSnapActorResolution() : ActorResolution.ROLE_BASED;

        // ── ENTITY_CREATOR: one task for the workflow initiator only ─────────
        // Correct for step 1 of any workflow where the creator IS the actor.
        // Issue triage, policy draft, vendor onboarding step 1, etc.
        // Creates exactly 1 task — no pool fan-out.
        if (actorResolution == ActorResolution.ENTITY_CREATOR) {
            Long creatorId = instance.getInitiatedBy();
            if (creatorId != null) {
                TaskInstance t = createTask(si, creatorId, true, TaskRole.ACTOR, si.getSnapSide(), tenantId);
                sectionCompletionService.snapshotSectionsForTask(t, si, instance);
                actorTaskCount++;
                log.info("[WORKFLOW] Step '{}' ENTITY_CREATOR — 1 ACTOR task for creator userId={}",
                        si.getSnapName(), creatorId);
            } else {
                log.warn("[WORKFLOW] Step '{}' ENTITY_CREATOR — initiatedBy is null, falling back to ROLE_BASED",
                        si.getSnapName());
                actorResolution = ActorResolution.ROLE_BASED; // fall through
            }
        }

        // ── ENTITY_OWNER: one task for the entity's owner field ──────────────
        // Correct for steps 2+ of issue workflow where the assigned owner does the work.
        // Resolved via WorkflowEntityResolverRegistry.resolveOwnerId().
        // Falls back to PREVIOUS_ACTOR then ROLE_BASED if owner not set.
        if (actorResolution == ActorResolution.ENTITY_OWNER) {
            Long ownerId = entityResolverRegistry.resolveOwnerId(instance);
            if (ownerId != null) {
                TaskInstance t = createTask(si, ownerId, true, TaskRole.ACTOR, si.getSnapSide(), tenantId);
                sectionCompletionService.snapshotSectionsForTask(t, si, instance);
                actorTaskCount++;
                log.info("[WORKFLOW] Step '{}' ENTITY_OWNER — 1 ACTOR task for owner userId={}",
                        si.getSnapName(), ownerId);
            } else {
                // Owner not set — for AUTOMATED issues ingested by system, fall through to ROLE_BASED
                // so GRC_MANAGER pool gets the triage task instead of the system user.
                // For other cases fall back to previous actor or initiator.
                Long previousActor = getPreviousStepActor(instance);
                Long fallbackId = previousActor != null ? previousActor : instance.getInitiatedBy();
                Long systemUid = instance.getInitiatedBy();
                // If fallback resolves to the system/initiator and they are the same as
                // the workflow initiator (automated ingest), skip to ROLE_BASED pool.
                boolean fallbackIsSystemUser = fallbackId != null
                        && fallbackId.equals(systemUid)
                        && previousActor == null; // no real human acted before
                if (fallbackId != null && !fallbackIsSystemUser) {
                    TaskInstance t = createTask(si, fallbackId, true, TaskRole.ACTOR, si.getSnapSide(), tenantId);
                    sectionCompletionService.snapshotSectionsForTask(t, si, instance);
                    actorTaskCount++;
                    log.warn("[WORKFLOW] Step '{}' ENTITY_OWNER — owner not set, fell back to userId={}",
                            si.getSnapName(), fallbackId);
                } else {
                    log.warn("[WORKFLOW] Step '{}' ENTITY_OWNER — owner not set or fallback is system user, falling back to ROLE_BASED pool",
                            si.getSnapName());
                    actorResolution = ActorResolution.ROLE_BASED; // fall through to role pool
                }
            }
        }

        if (actorResolution == ActorResolution.ASSIGNMENT_SCOPED) {
            // Ask the domain resolver for the specifically-assigned users
            List<Long> assignedUserIds = actorResolverRegistry.resolve(instance, si);

            // OPTIONAL role filter: if this step has actor roles configured
            // (workflow_step_actor_roles — the SAME table ROLE_BASED steps use),
            // narrow the assignment-scoped candidates to only those who currently
            // hold one of those roles. This guards against stale assignments —
            // e.g. a user was recorded as a section's auditor, then their role
            // was changed/revoked, and they should no longer receive the task.
            //
            // Fully data-driven and optional: a step with NO actor roles configured
            // (the common case today) behaves exactly as before — no filtering.
            // To enable the filter for a step, just add rows to
            // workflow_step_actor_roles for it, same as configuring ROLE_BASED.
            if (!assignedUserIds.isEmpty() && !actorRoles.isEmpty()) {
                Set<Long> roleEligibleUserIds = new java.util.HashSet<>();
                for (WorkflowStepRole ar : actorRoles) {
                    roleEligibleUserIds.addAll(
                            dbRepository.findUserIdsByRoleAndTenant(ar.getRoleId(), tenantId));
                }
                List<Long> beforeFilter = assignedUserIds;
                assignedUserIds = assignedUserIds.stream()
                        .filter(roleEligibleUserIds::contains)
                        .toList();
                log.info("[WORKFLOW] Step '{}' ASSIGNMENT_SCOPED role filter applied | " +
                                "before={} | after={} | configured roles={}",
                        si.getSnapName(), beforeFilter.size(), assignedUserIds.size(), actorRoles.size());
            }

            if (!assignedUserIds.isEmpty()) {
                for (Long uid : assignedUserIds) {
                    TaskInstance t = createTask(si, uid, true, TaskRole.ACTOR, si.getSnapSide(), tenantId);
                    sectionCompletionService.snapshotSectionsForTask(t, si, instance);
                    actorTaskCount++;
                }
                log.info("[WORKFLOW] Step '{}' ASSIGNMENT_SCOPED — {} ACTOR task(s) from resolver",
                        si.getSnapName(), actorTaskCount);
            } else {
                log.warn("[WORKFLOW] Step '{}' ASSIGNMENT_SCOPED — resolver returned 0 users. " +
                        "Falling back to ROLE_BASED so step is not stuck.", si.getSnapName());
                actorResolution = ActorResolution.ROLE_BASED; // fall through to ROLE_BASED below
            }
        }

        if (actorResolution == ActorResolution.ROLE_BASED) {
            if (!actorRoles.isEmpty()) {
                // Collect all user IDs across all actor roles first, then deduplicate.
                // A user holding multiple matching roles (e.g. ORG_OWNER + ORG_ADMIN + CISO)
                // should receive exactly ONE task, not one per role.
                java.util.Set<Long> seenUids = new java.util.LinkedHashSet<>();

                for (WorkflowStepRole ar : actorRoles) {
                    String roleName = roleRepository.findById(ar.getRoleId())
                            .map(com.kashi.grc.usermanagement.domain.Role::getName).orElse(null);
                    // CRITICAL: vendor-side steps must resolve actors ONLY from the specific vendor.
                    // Use side-filtered query to prevent cross-side task assignment
                    // e.g. AUDITOR users must not get tasks on ORGANIZATION side steps
                    List<Long> uids;
                    if ("VENDOR".equalsIgnoreCase(si.getSnapSide())) {
                        uids = dbRepository.findUserIdsByRoleAndVendor(ar.getRoleId(), tenantId, instance.getEntityId());
                    } else if (si.getSnapSide() != null) {
                        uids = dbRepository.findUserIdsByRoleAndTenantAndSide(ar.getRoleId(), tenantId, si.getSnapSide());
                        if (uids.isEmpty()) {
                            // No users for this role on this side — skip entirely.
                            // NO fallback to tenant-wide — that would assign wrong-side users
                            // (e.g. LEAD_AUDITOR/AUDITOR side getting ORGANIZATION step tasks).
                            log.warn("[WORKFLOW] Step '{}' role '{}' (id={}) has no users on side='{}' — skipping.",
                                    si.getSnapName(), roleName, ar.getRoleId(), si.getSnapSide());
                        }
                    } else {
                        uids = dbRepository.findUserIdsByRoleAndTenant(ar.getRoleId(), tenantId);
                    }
                    seenUids.addAll(uids);
                }

                // ── SOD: Segregation of Duties ───────────────────────────────────
                // Driven by snap_sod_rules_json — no hardcoding in engine.
                // Add new rule types here as the platform evolves.
                evaluateSodRules(si, instance, seenUids);

                // Create exactly ONE task per unique user — regardless of how many roles matched
                // actorRoleName: use the LAST matched role name for the user.
                // For users with a single role (the common case) this is always correct.
                // For multi-role users, the last matching role wins — acceptable since the
                // frontend view map covers all expected role names.
                String lastRoleName = null;
                for (WorkflowStepRole ar2 : actorRoles) {
                    String rn = roleRepository.findById(ar2.getRoleId())
                            .map(com.kashi.grc.usermanagement.domain.Role::getName).orElse(null);
                    if (rn != null) lastRoleName = rn;
                }
                final String resolvedRoleName = lastRoleName;
                for (Long uid : seenUids) {
                    TaskInstance t = createTask(si, uid, true, TaskRole.ACTOR, si.getSnapSide(), tenantId, resolvedRoleName);
                    sectionCompletionService.snapshotSectionsForTask(t, si, instance);
                    actorTaskCount++;
                }
                log.info("[WORKFLOW] Step '{}' ROLE_BASED — {} ACTOR task(s) for {} actorRole(s) (deduplicated by userId)",
                        si.getSnapName(), actorTaskCount, actorRoles.size());
            } else {
                log.warn("[WORKFLOW] Step '{}' has no actorRoles — no ACTOR tasks created. " +
                                "Step will never reach approval. Add actorRoles in the blueprint.",
                        si.getSnapName());
            }
        }

        // ── FALLBACK: zero actor tasks resolved from roles ────────────────────
        // If actorRoles exist in the blueprint but nobody in the tenant holds any of
        // those roles yet, actorTaskCount is 0 — the step would be silently stuck forever.
        //
        // Fallback strategy:
        //   VENDOR side   → assign to whoever holds VENDOR_VRM role in this tenant.
        //                   VRM manages the vendor relationship and can re-delegate once
        //                   the right vendor person has their role assigned.
        //                   If VRM also has no users → fall through to initiatedBy.
        //
        //   ALL OTHER sides (ORGANIZATION, AUDITOR, AUDITEE, SYSTEM, etc.)
        //                 → assign to instance.initiatedBy — the org user who started
        //                   the workflow. They are responsible for resolving missing
        //                   role assignments and can reassign from their inbox.
        //
        // The fallback task is marked ACTOR with remarks explaining the fallback reason,
        // visible in the task inbox so the recipient knows why they received it.
        if (actorTaskCount == 0 && !actorRoles.isEmpty()) {
            log.warn("[WORKFLOW] Step '{}' — actorRoles configured but 0 users found. " +
                            "Applying fallback | side={} | tenantId={}",
                    si.getSnapName(), si.getSnapSide(), tenantId);

            Long fallbackUserId = null;
            String fallbackReason;

            if ("VENDOR".equalsIgnoreCase(si.getSnapSide())) {
                // Vendor-side fallback: find VRM user scoped to THIS specific vendor.
                // Use targeted findByNameAndSide instead of findAll() which loads every role.
                fallbackUserId = java.util.stream.Stream.of("VENDOR_VRM", "VRM")
                        .map(name -> roleRepository.findByNameAndSide(name,
                                com.kashi.grc.usermanagement.domain.RoleSide.VENDOR))
                        .filter(java.util.Optional::isPresent)
                        .map(java.util.Optional::get)
                        .flatMap(r -> dbRepository.findUserIdsByRoleAndVendor(r.getId(), tenantId, instance.getEntityId()).stream())
                        .findFirst()
                        .orElse(null);
                fallbackReason = fallbackUserId != null
                        ? "Fallback: required vendor role has no users — assigned to VRM for re-delegation"
                        : "Fallback: required vendor role and VRM role have no users — assigned to workflow initiator";
                if (fallbackUserId == null) {
                    fallbackUserId = instance.getInitiatedBy();
                }
            } else {
                // All other sides (ORGANIZATION, AUDITOR, AUDITEE, etc.) → initiatedBy
                fallbackUserId = instance.getInitiatedBy();
                fallbackReason = "Fallback: required role has no users — assigned to workflow initiator for resolution";
            }

            if (fallbackUserId != null) {
                TaskInstance fallbackTask = createTask(
                        si, fallbackUserId, true, TaskRole.ACTOR, si.getSnapSide(), tenantId,
                        "FALLBACK");
                fallbackTask.setRemarks(fallbackReason);
                taskInstanceRepository.save(fallbackTask);
                sectionCompletionService.snapshotSectionsForTask(fallbackTask, si, instance);
                actorTaskCount++;
                log.warn("[WORKFLOW] Step '{}' — fallback task → userId={} | {}",
                        si.getSnapName(), fallbackUserId, fallbackReason);
            } else {
                log.error("[WORKFLOW] Step '{}' — fallback failed: initiatedBy is null. " +
                        "Step will be stuck. | tenantId={}", si.getSnapName(), tenantId);
            }
        }

        // Step is IN_PROGRESS — both task sets are live
        si.setStatus(StepStatus.IN_PROGRESS);
        stepInstanceRepository.save(si);

        // FYI notifications to configured step observers (dormant
        // workflow_step_observer_roles table now live) — after tasks exist
        // so actor/assigner users can be excluded from the FYI list.
        notifyStepObservers(si, instance, tenantId);
    }

    /**
     * Notifies users holding this step's OBSERVER roles that the step went
     * active. Deliberately a separate eventKey (STEP_ACTIVATED_OBSERVER)
     * rather than a new email audience: observers are true recipients of
     * their own event type, which means (a) the email rule table can give
     * them FYI-toned templates per eventKey with zero pipeline changes and
     * (b) users can mute exactly this key in their notification preferences.
     *
     * Role→user resolution is tenant-wide (not side-filtered): observer
     * roles are often deliberately cross-side — e.g. an ORGANIZATION
     * compliance manager observing VENDOR assessment steps. The role's own
     * membership already scopes who holds it.
     *
     * Best-effort: observer notification failure must never fail step
     * activation.
     */
    private void notifyStepObservers(StepInstance si, WorkflowInstance instance, Long tenantId) {
        try {
            List<WorkflowStepObserverRole> observerRoles =
                    stepObserverRoleRepository.findByStepId(si.getStepId());
            if (observerRoles.isEmpty()) return;

            // Users already working this step (ACTOR/ASSIGNER tasks) don't
            // need an FYI about it.
            java.util.Set<Long> taskedUserIds = taskInstanceRepository
                    .findByStepInstanceId(si.getId()).stream()
                    .map(TaskInstance::getAssignedUserId)
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());

            List<Long> observerIds = observerRoles.stream()
                    .flatMap(o -> dbRepository.findUserIdsByRoleAndTenant(o.getRoleId(), tenantId).stream())
                    .distinct()
                    .filter(uid -> !taskedUserIds.contains(uid))
                    .toList();
            if (observerIds.isEmpty()) return;

            notificationService.sendToUsers(observerIds, "STEP_ACTIVATED_OBSERVER",
                    "FYI: Step '" + si.getSnapName() + "' is now active on "
                            + instance.getEntityType() + " #" + instance.getEntityId(),
                    instance.getEntityType(), instance.getEntityId(),
                    null, Map.of(
                            "stepName", si.getSnapName() != null ? si.getSnapName() : "",
                            "entityType", instance.getEntityType() != null ? instance.getEntityType() : ""));
            log.info("[WORKFLOW] Step '{}' — {} observer(s) notified (STEP_ACTIVATED_OBSERVER)",
                    si.getSnapName(), observerIds.size());
        } catch (Exception e) {
            log.warn("[WORKFLOW] Observer notification failed for step '{}': {}",
                    si.getSnapName(), e.getMessage());
        }
    }

    /**
     * Returns the userId of whoever last acted on a step in this instance.
     * Used by PREVIOUS_ACTOR resolution — creates a natural delegation chain.
     *
     * Search order:
     *   1. Most recent STEP_APPROVED (human approved a task)
     *   2. Most recent TASK_DELEGATED (someone delegated, counts as "last actor")
     *   3. Most recent STEP_AUTO_APPROVED performed by a non-system user
     *   4. null → caller falls back to INITIATOR
     *
     * SYSTEM auto-approvals are skipped because the "actor" should be the last
     * human who did real work, not the system process ID.
     */
    private Long getPreviousStepActor(WorkflowInstance instance) {
        List<com.kashi.grc.workflow.domain.WorkflowInstanceHistory> history =
                historyRepository.findByWorkflowInstanceIdOrderByPerformedAtAsc(instance.getId());

        // Walk history in reverse — most recent first
        for (int i = history.size() - 1; i >= 0; i--) {
            com.kashi.grc.workflow.domain.WorkflowInstanceHistory h = history.get(i);
            Long actor = h.getPerformedBy();
            if (actor == null) continue;

            if ("STEP_APPROVED".equals(h.getEventType())) {
                // Human approval — always valid as previous actor
                log.debug("[WORKFLOW] PREVIOUS_ACTOR resolved via STEP_APPROVED | userId={} | instanceId={}",
                        actor, instance.getId());
                return actor;
            }
            if ("TASK_DELEGATED".equals(h.getEventType())) {
                log.debug("[WORKFLOW] PREVIOUS_ACTOR resolved via TASK_DELEGATED | userId={} | instanceId={}",
                        actor, instance.getId());
                return actor;
            }
            if ("STEP_AUTO_APPROVED".equals(h.getEventType())) {
                // Only use auto-approval actor if it was a real human (not system process)
                // System steps record initiatedBy as performedBy — skip those
                if (!actor.equals(instance.getInitiatedBy())) {
                    log.debug("[WORKFLOW] PREVIOUS_ACTOR resolved via STEP_AUTO_APPROVED (human) | userId={}",
                            actor);
                    return actor;
                }
                // It was a system auto-approval — skip, keep searching backwards
            }
        }

        log.debug("[WORKFLOW] PREVIOUS_ACTOR: no human actor found in history | instanceId={}",
                instance.getId());
        return null;
    }

    /** Creates a task for a single resolved user with a descriptive log. */
    private void createTaskForUser(StepInstance si, Long userId, Long tenantId, String reason) {
        if (userId == null) {
            log.warn("[WORKFLOW] createTaskForUser: userId is null (reason={}) — no task created", reason);
            return;
        }
        createTask(si, userId, true, tenantId);
        log.info("[WORKFLOW] Task created for userId={} via {} | stepInstanceId={}",
                userId, reason, si.getId());
    }

    /**
     * Creates a TaskInstance and sends a DB notification to the assigned user.
     * Logic unchanged — called by direct user assignment and by action handlers.
     */
    private TaskInstance createTask(StepInstance si, Long userId, boolean isAutoAssigned, Long tenantId) {
        return createTask(si, userId, isAutoAssigned, TaskRole.ACTOR, tenantId);
    }

    private TaskInstance createTask(StepInstance si, Long userId, boolean isAutoAssigned, TaskRole role, Long tenantId) {
        return createTask(si, userId, isAutoAssigned, role, null, tenantId);
    }

    private TaskInstance createTask(StepInstance si, Long userId, boolean isAutoAssigned,
                                    TaskRole role, String assignerSide, Long tenantId) {
        return createTask(si, userId, isAutoAssigned, role, assignerSide, tenantId, null);
    }

    private TaskInstance createTask(StepInstance si, Long userId, boolean isAutoAssigned,
                                    TaskRole role, String assignerSide, Long tenantId, String actorRoleName) {
        TaskInstance task = TaskInstance.builder()
                .stepInstanceId(si.getId())
                .assignedUserId(userId)
                .status(TaskStatus.PENDING)
                .isAutoAssigned(isAutoAssigned)
                .taskRole(role)
                .assignerSide(assignerSide)
                .actorRoleName(actorRoleName)
                .build();
        taskInstanceRepository.save(task);

        // Send DB notification — step name from snapshot, no blueprint read needed
        String stepName = si.getSnapName() != null ? si.getSnapName() : "workflow step";
        notificationService.send(userId, "TASK_ASSIGNMENT",
                "New task assigned: " + stepName, "TASK", task.getId());

        // Publish WebSocket event — ALL routing fields from snapshot
        WorkflowInstance wi = instanceRepository.findById(si.getWorkflowInstanceId()).orElse(null);
        Long artifactId = wi != null ? entityResolverRegistry.resolveArtifactId(wi) : null;
        String resolvedSide   = role == TaskRole.ASSIGNER
                ? (assignerSide != null ? assignerSide : "ORGANIZATION")
                : si.getSnapSide();
        String resolvedAction = role == TaskRole.ASSIGNER
                ? StepAction.ASSIGN.name()
                : (si.getSnapStepAction() != null
                   ? si.getSnapStepAction().name() : StepAction.APPROVE.name());

        eventPublisher.publishEvent(new WorkflowEvent.TaskAssigned(
                wi != null ? wi.getId() : null,
                si.getId(), task.getId(), userId, stepName,
                wi != null ? wi.getEntityType() : null,
                wi != null ? wi.getEntityId() : null,
                artifactId,
                si.getSnapNavKey(),
                resolvedSide, resolvedAction,
                role != null ? role.name() : "ACTOR"
        ));

        log.debug("[WORKFLOW] Task+notification+WS created | taskId={} | userId={} | step='{}'",
                task.getId(), userId, stepName);
        return task;
    }

    /**
     * Transitions a StepInstance from AWAITING_ASSIGNMENT → IN_PROGRESS when a task
     * is explicitly created via REASSIGN, DELEGATE, or manual assignment.
     *
     * AWAITING_ASSIGNMENT is the initial status set by createStepInstance.
     * assignTasksForStep transitions to IN_PROGRESS after fanning out tasks.
     * This helper handles the edge case where an existing task is reassigned or
     * delegated on a step that somehow still carries AWAITING_ASSIGNMENT —
     * ensuring the step is always IN_PROGRESS when at least one task exists.
     *
     * Idempotent — no-op if step is already IN_PROGRESS.
     */
    private void transitionStepToInProgressIfAwaiting(StepInstance si, WorkflowInstance instance,
                                                      Long performedBy, String reason) {
        if (si.getStatus() == StepStatus.AWAITING_ASSIGNMENT) {
            si.setStatus(StepStatus.IN_PROGRESS);
            stepInstanceRepository.save(si);
            recordHistory(instance, si, null, "STEP_ASSIGNMENT_STARTED",
                    StepStatus.AWAITING_ASSIGNMENT.name(), StepStatus.IN_PROGRESS.name(),
                    performedBy, reason);
            log.info("[WORKFLOW] Step AWAITING_ASSIGNMENT → IN_PROGRESS | stepInstanceId={} | reason='{}'",
                    si.getId(), reason);
        }
    }

    /**
     * Evaluates SOD rules from snap_sod_rules_json and removes excluded user IDs from seenUids.
     * Called during ROLE_BASED actor resolution in assignTasksForStep.
     *
     * Supported rule types:
     *   EXCLUDE_ENTITY_OWNER   — removes the entity owner (e.g. issue.ownerId)
     *   EXCLUDE_PREVIOUS_ACTOR — removes whoever acted on the immediately preceding step
     *   EXCLUDE_ROLE           — removes all users holding a specific role (roleId field required)
     */
    private void evaluateSodRules(StepInstance si, WorkflowInstance instance,
                                  java.util.Set<Long> seenUids) {
        String rulesJson = si.getSnapSodRulesJson();
        if (rulesJson == null || rulesJson.isBlank()) return;
        try {
            com.fasterxml.jackson.databind.JsonNode rules =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(rulesJson);
            if (!rules.isArray()) return;
            for (com.fasterxml.jackson.databind.JsonNode rule : rules) {
                String type = rule.path("type").asText("");
                switch (type) {
                    case "EXCLUDE_ENTITY_OWNER" -> {
                        Long ownerId = entityResolverRegistry.resolveOwnerId(instance);
                        if (ownerId != null && seenUids.remove(ownerId)) {
                            log.info("[WORKFLOW-SOD] EXCLUDE_ENTITY_OWNER: removed userId={} from step '{}'",
                                    ownerId, si.getSnapName());
                        }
                    }
                    case "EXCLUDE_PREVIOUS_ACTOR" -> {
                        Long prevActor = getPreviousStepActor(instance);
                        if (prevActor != null && seenUids.remove(prevActor)) {
                            log.info("[WORKFLOW-SOD] EXCLUDE_PREVIOUS_ACTOR: removed userId={} from step '{}'",
                                    prevActor, si.getSnapName());
                        }
                    }
                    case "EXCLUDE_ROLE" -> {
                        long roleId = rule.path("roleId").asLong(0);
                        if (roleId > 0) {
                            List<Long> roleUsers = dbRepository.findUserIdsByRoleAndTenant(
                                    roleId, instance.getTenantId());
                            roleUsers.forEach(uid -> {
                                if (seenUids.remove(uid)) {
                                    log.info("[WORKFLOW-SOD] EXCLUDE_ROLE roleId={}: removed userId={} from step '{}'",
                                            roleId, uid, si.getSnapName());
                                }
                            });
                        }
                    }
                    default -> log.warn("[WORKFLOW-SOD] Unknown SOD rule type '{}' on step '{}' — ignored",
                            type, si.getSnapName());
                }
            }
        } catch (Exception e) {
            log.warn("[WORKFLOW-SOD] Failed to parse sod_rules_json on step '{}': {}",
                    si.getSnapName(), e.getMessage());
        }
    }

    /**
     * Returns true when enough ACTOR tasks have been approved to satisfy the step's approval policy.
     *
     * Only ACTOR tasks count toward approval — ASSIGNER tasks are excluded entirely.
     * An assigner approving their coordination task must NOT advance the step; only actors
     * completing their actual work (FILL, REVIEW, ACKNOWLEDGE, EVALUATE, APPROVE) can do that.
     *
     * Counts are scoped to TaskRole.ACTOR so mixed steps (both ASSIGNER + ACTOR tasks on the
     * same StepInstance) are evaluated correctly.
     */
    private boolean isStepApprovalSatisfied(StepInstance si) {
        // REJECTED tasks are intentionally excluded from both counts.
        // A REJECTED contributor task means the responder closed it (section locked before
        // contributor finished) — it is a terminal "done" state that should not block
        // the step from advancing once the real ACTOR tasks (responders) are all approved.
        // Including REJECTED in total but not approved would permanently stall the step.
        long total    = taskInstanceRepository.countByStepInstanceIdAndTaskRoleAndStatusNot(
                si.getId(), TaskRole.ACTOR, TaskStatus.REJECTED);
        long approved = taskInstanceRepository.countByStepInstanceIdAndTaskRoleAndStatus(
                si.getId(), TaskRole.ACTOR, TaskStatus.APPROVED);

        // No non-rejected actor tasks exist yet (step still AWAITING_ASSIGNMENT) → not satisfied
        if (total == 0) return false;

        ApprovalType approvalType = si.getSnapApprovalType() != null
                ? si.getSnapApprovalType() : ApprovalType.ANY_ONE;

        return switch (approvalType) {
            case ANY_ONE   -> approved >= 1;
            case ALL       -> approved == total;
            case MAJORITY  -> approved > (total / 2);
            case THRESHOLD -> {
                int required = si.getSnapMinApprovals() != null ? si.getSnapMinApprovals() : 1;
                yield approved >= required;
            }
        };
    }

    /** Updates a task's status and actedAt timestamp. Logic unchanged. */
    private void updateTask(TaskInstance task, TaskStatus status, String remarks) {
        task.setStatus(status);
        task.setActedAt(LocalDateTime.now());
        task.setRemarks(remarks);
        taskInstanceRepository.save(task);
        log.debug("[WORKFLOW] Task updated | taskId={} | status={}", task.getId(), status);
    }

    /** Marks a step instance as completed with the given status. Logic unchanged. */
    private void completeStep(StepInstance si, StepStatus status, String remarks) {
        si.setStatus(status);
        si.setCompletedAt(LocalDateTime.now());
        si.setRemarks(remarks);
        stepInstanceRepository.save(si);
        log.debug("[WORKFLOW] Step completed | stepInstanceId={} | status={}", si.getId(), status);
    }

    /**
     * Expires all remaining pending tasks on a step when the step completes.
     * Called after isStepApprovalSatisfied returns true (step advance) or on REJECT/SEND_BACK.
     *
     * Expires ALL pending tasks regardless of taskRole — when the step is done, both
     * outstanding ASSIGNER coordination tasks and any remaining ACTOR tasks are closed.
     * The excludeTaskId is the task that just acted (already updated to APPROVED/REJECTED
     * by the caller) so we skip it to avoid a redundant save.
     */
    private void expirePendingTasks(StepInstance si, Long excludeTaskId) {
        List<TaskInstance> pending = taskInstanceRepository
                .findByStepInstanceIdAndStatus(si.getId(), TaskStatus.PENDING)
                .stream().filter(t -> !t.getId().equals(excludeTaskId)).toList();

        if (!pending.isEmpty()) {
            pending.forEach(t -> {
                t.setStatus(TaskStatus.EXPIRED);
                t.setActedAt(LocalDateTime.now());
            });
            taskInstanceRepository.saveAll(pending);
            log.debug("[WORKFLOW] Expired {} pending task(s) on step completion | stepInstanceId={}",
                    pending.size(), si.getId());
        }
    }

    /** Records a granular task action in the audit log. Logic unchanged. */
    private void recordAction(TaskInstance task, StepInstance si, WorkflowInstance instance,
                              ActionType actionType, Long performedBy, TaskActionRequest req) {
        actionRepository.save(WorkflowTaskAction.builder()
                .tenantId(instance.getTenantId())
                .taskInstanceId(task.getId())
                .workflowInstanceId(instance.getId())
                .stepInstanceId(si.getId())
                .actionType(actionType)
                .performedBy(performedBy)
                .performedAt(LocalDateTime.now())
                .remarks(req.getRemarks())
                .targetUserId(req.getTargetUserId())
                .targetStepId(req.getTargetStepId())
                .build());
    }

    /** Records a high-level workflow event in the history log. Reads from snapshot — no blueprint load. */
    private void recordHistory(WorkflowInstance instance, StepInstance si, TaskInstance task,
                               String eventType, String fromStatus, String toStatus,
                               Long performedBy, String remarks) {
        historyRepository.save(WorkflowInstanceHistory.builder()
                .tenantId(instance.getTenantId())
                .workflowInstanceId(instance.getId())
                .stepInstanceId(si != null ? si.getId() : null)
                .taskInstanceId(task != null ? task.getId() : null)
                .eventType(eventType)
                .fromStatus(fromStatus).toStatus(toStatus)
                .performedBy(performedBy)
                .performedAt(LocalDateTime.now())
                .remarks(remarks)
                // Null-safe — si is null for workflow-level events (STARTED, CANCELLED, etc.)
                .stepId(si != null ? si.getStepId() : null)
                .stepName(si != null ? si.getSnapName() : null)
                .stepOrder(si != null ? si.getSnapStepOrder() : null)
                .build());
        log.debug("[WORKFLOW] History recorded | event={} | from={} | to={} | instance={}",
                eventType, fromStatus, toStatus, instance.getId());
    }

    // ══════════════════════════════════════════════════════════════
    // RESPONSE MAPPERS
    // ══════════════════════════════════════════════════════════════

    /** Builds full workflow blueprint response with all steps. Logic unchanged. */
    public WorkflowResponse buildWorkflowResponse(Workflow w) {
        List<WorkflowStep> steps = stepRepository.findByWorkflowIdOrderByStepOrderAsc(w.getId());

        if (steps.isEmpty()) {
            return WorkflowResponse.builder()
                    .id(w.getId()).name(w.getName()).entityType(w.getEntityType())
                    .description(w.getDescription()).version(w.getVersion()).isActive(w.isActive())
                    .createdAt(w.getCreatedAt()).steps(List.of()).build();
        }

        // ── Bulk-load all step associations in 5 queries instead of 5×N ──────
        // Previously: stepRoleRepository.findByStepId(s.getId()) was called once
        // per step for 5 different tables → 5×N queries for a workflow with N steps.
        // A 12-step workflow fired 60 queries just to render a single list row.
        // Now: one IN query per table, results grouped in memory — always 5 queries.
        List<Long> stepIds = steps.stream().map(WorkflowStep::getId).toList();

        Map<Long, List<Long>> roleIdsByStepId = stepRoleRepository.findByStepIdIn(stepIds)
                .stream().collect(java.util.stream.Collectors.groupingBy(
                        WorkflowStepRole::getStepId,
                        java.util.stream.Collectors.mapping(WorkflowStepRole::getRoleId,
                                java.util.stream.Collectors.toList())));

        Map<Long, List<Long>> userIdsByStepId = stepUserRepository.findByStepIdIn(stepIds)
                .stream().collect(java.util.stream.Collectors.groupingBy(
                        WorkflowStepUser::getStepId,
                        java.util.stream.Collectors.mapping(WorkflowStepUser::getUserId,
                                java.util.stream.Collectors.toList())));

        Map<Long, List<Long>> assignerRoleIdsByStepId = stepAssignerRoleRepository.findByStepIdIn(stepIds)
                .stream().collect(java.util.stream.Collectors.groupingBy(
                        WorkflowStepAssignerRole::getStepId,
                        java.util.stream.Collectors.mapping(WorkflowStepAssignerRole::getRoleId,
                                java.util.stream.Collectors.toList())));

        Map<Long, List<Long>> observerRoleIdsByStepId = stepObserverRoleRepository.findByStepIdIn(stepIds)
                .stream().collect(java.util.stream.Collectors.groupingBy(
                        WorkflowStepObserverRole::getStepId,
                        java.util.stream.Collectors.mapping(WorkflowStepObserverRole::getRoleId,
                                java.util.stream.Collectors.toList())));

        // Gap 1+2: return sections so admin UI can read them back
        Map<Long, List<com.kashi.grc.workflow.dto.response.StepSectionResponse>> sectionsByStepId =
                stepSectionRepository.findByStepIdInOrderBySectionOrderAsc(stepIds)
                        .stream().collect(java.util.stream.Collectors.groupingBy(
                                com.kashi.grc.workflow.domain.WorkflowStepSection::getStepId,
                                java.util.stream.Collectors.mapping(
                                        sec -> com.kashi.grc.workflow.dto.response.StepSectionResponse.builder()
                                                .id(sec.getId()).sectionKey(sec.getSectionKey())
                                                .sectionOrder(sec.getSectionOrder()).label(sec.getLabel())
                                                .description(sec.getDescription()).required(sec.isRequired())
                                                .completionEvent(sec.getCompletionEvent())
                                                .requiresAssignment(sec.isRequiresAssignment())
                                                .tracksItems(sec.isTracksItems())
                                                .build(),
                                        java.util.stream.Collectors.toList())));

        // ── Assemble responses from pre-loaded maps (zero additional DB hits) ─
        List<WorkflowStepResponse> stepResponses = steps.stream().map(s ->
                        WorkflowStepResponse.builder()
                                .id(s.getId()).workflowId(w.getId()).stepOrder(s.getStepOrder())
                                .name(s.getName()).side(s.getSide()).description(s.getDescription())
                                .approvalType(s.getApprovalType()).minApprovalsRequired(s.getMinApprovalsRequired())
                                .isParallel(s.isParallel()).isOptional(s.isOptional()).slaHours(s.getSlaHours())
                                .automatedAction(s.getAutomatedAction())
                                .roleIds(roleIdsByStepId.getOrDefault(s.getId(), List.of()))
                                .userIds(userIdsByStepId.getOrDefault(s.getId(), List.of()))
                                .assignerRoleIds(assignerRoleIdsByStepId.getOrDefault(s.getId(), List.of()))
                                .observerRoleIds(observerRoleIdsByStepId.getOrDefault(s.getId(), List.of()))
                                .assignerResolution(s.getAssignerResolution())
                                .actorResolution(s.getActorResolution())
                                .allowOverride(s.isAllowOverride())
                                .stepAction(s.getStepAction())
                                .navKey(s.getNavKey())
                                .assignerNavKey(s.getAssignerNavKey())
                                .sections(sectionsByStepId.getOrDefault(s.getId(), List.of()))
                                .build())
                .collect(java.util.stream.Collectors.toList());

        return WorkflowResponse.builder()
                .id(w.getId()).name(w.getName()).entityType(w.getEntityType())
                .description(w.getDescription()).version(w.getVersion()).isActive(w.isActive())
                .createdAt(w.getCreatedAt()).steps(stepResponses).build();
    }

    /** Builds full workflow instance response with all step and task instances. Logic unchanged. */
    public WorkflowInstanceResponse buildInstanceResponse(WorkflowInstance instance) {
        List<StepInstance> stepInstances = stepInstanceRepository
                .findByWorkflowInstanceIdOrderByCreatedAtAsc(instance.getId());

        // Step names, orders, and all display fields from snapshot — no blueprint load per step.
        List<StepInstanceResponse> stepResponses = stepInstances.stream().map(si -> {
            List<TaskInstanceResponse> tasks = taskInstanceRepository.findByStepInstanceId(si.getId())
                    .stream().map(this::toTaskResponse).toList();

            return StepInstanceResponse.builder()
                    .id(si.getId()).workflowInstanceId(instance.getId())
                    .stepId(si.getStepId())
                    .stepName(si.getSnapName())
                    .stepOrder(si.getSnapStepOrder())
                    .status(si.getStatus()).startedAt(si.getStartedAt())
                    .completedAt(si.getCompletedAt()).slaDueAt(si.getSlaDueAt())
                    .iterationCount(si.getIterationCount()).remarks(si.getRemarks())
                    .taskInstances(tasks).build();
        }).toList();

        // Current step context from snapshot — no blueprint lookup needed.
        StepInstance currentSI = instance.getCurrentStepId() != null
                ? stepInstanceRepository.findById(instance.getCurrentStepId()).orElse(null) : null;
        Workflow workflow = workflowRepository.findById(instance.getWorkflowId()).orElse(null);

        return WorkflowInstanceResponse.builder()
                .id(instance.getId()).tenantId(instance.getTenantId())
                .workflowId(instance.getWorkflowId())
                .workflowName(workflow != null ? workflow.getName() : null)
                .entityType(instance.getEntityType()).entityId(instance.getEntityId())
                .currentStepId(instance.getCurrentStepId())
                .currentStepName(currentSI != null ? currentSI.getSnapName() : null)
                .currentStepOrder(currentSI != null ? currentSI.getSnapStepOrder() : null)
                .status(instance.getStatus()).startedAt(instance.getStartedAt())
                .completedAt(instance.getCompletedAt()).dueDate(instance.getDueDate())
                .priority(instance.getPriority()).initiatedBy(instance.getInitiatedBy())
                .remarks(instance.getRemarks()).stepInstances(stepResponses).build();
    }

    /**
     * Flat task response — maps only TaskInstance fields.
     * Used internally by buildInstanceResponse() where step context is already provided
     * by the parent StepInstanceResponse. Logic unchanged.
     */
    private TaskInstanceResponse toTaskResponse(TaskInstance t) {
        return TaskInstanceResponse.builder()
                .id(t.getId()).stepInstanceId(t.getStepInstanceId())
                .assignedUserId(t.getAssignedUserId()).status(t.getStatus())
                .taskRole(t.getTaskRole())
                .actedAt(t.getActedAt()).dueAt(t.getDueAt()).remarks(t.getRemarks())
                .delegatedToUserId(t.getDelegatedToUserId())
                .reassignedFromUserId(t.getReassignedFromUserId())
                .isAutoAssigned(t.isAutoAssigned()).build();
    }

    /**
     * Enriched task response — joins StepInstance → WorkflowStep → WorkflowInstance → Workflow.
     *
     * NEW: Used by getPendingTasksForUser(), getAllTasksForUser(), and assignTaskToUser()
     * so the frontend TaskInbox receives stepName, entityType, entityId, priority, and
     * workflowName needed to render cards and resolve navigation routes.
     *
     * The flat toTaskResponse() is kept for internal usage in buildInstanceResponse()
     * where joining would be redundant (context already present in parent).
     */
    /**
     * Enriches a TaskInstance with all context the frontend needs to:
     *   1. Display the task in the inbox (step name, workflow, entity, priority)
     *   2. Route to the correct page (entityType + resolvedStepSide + resolvedStepAction → URL)
     *   3. Know the artifact to act on (artifactId from WorkflowEntityResolverRegistry)
     *
     * resolvedStepSide and resolvedStepAction are computed from taskRole:
     *   ACTOR    → resolvedStepSide = step.side, resolvedStepAction = step.stepAction
     *   ASSIGNER → resolvedStepSide = assigner's role side (org/vendor/auditor),
     *              resolvedStepAction = "ASSIGN" (always — assigners always assign)
     *
     * artifactId comes from WorkflowEntityResolverRegistry — pluggable per entityType.
     * Adding Audit/Risk/Policy module = register a new WorkflowEntityResolver bean.
     * This method never needs to change.
     */
    private TaskInstanceResponse toEnrichedTaskResponse(TaskInstance t) {
        StepInstance si   = stepInstanceRepository.findById(t.getStepInstanceId()).orElse(null);
        WorkflowInstance wi = si != null
                ? instanceRepository.findById(si.getWorkflowInstanceId()).orElse(null) : null;
        // WorkflowStep is no longer loaded for routing — all routing fields come from
        // si.snapXxx() which was captured at step instance creation time.
        // workflow is still loaded for workflowName in the response.
        Workflow workflow  = wi != null
                ? workflowRepository.findById(wi.getWorkflowId()).orElse(null) : null;

        // ── Artifact resolution — pluggable, zero hardcoding ─────────────────
        // Step-aware: some workflows route different steps to different entity pages
        // (e.g. WF16 routes engagement steps to the engagement page, not project page)
        Long artifactId = wi != null ? entityResolverRegistry.resolveArtifactId(wi, si, t.getAssignedUserId()) : null;
        String entityTitle = wi != null ? entityResolverRegistry.resolveEntityTitle(wi) : null;

        // ── Routing fields — derived from taskRole ────────────────────────────
        // ASSIGNER tasks always route to the ASSIGN UI on the assigner's own side.
        // ACTOR tasks route based on step.side + step.stepAction.
        String resolvedStepSide;
        String resolvedStepAction;
        TaskRole taskRole = t.getTaskRole() != null ? t.getTaskRole() : TaskRole.ACTOR;

        if (taskRole == TaskRole.ASSIGNER) {
            resolvedStepSide   = t.getAssignerSide() != null ? t.getAssignerSide() : "ORGANIZATION";
            resolvedStepAction = StepAction.ASSIGN.name();
        } else {
            // Actor: side and action from snapshot — 100% isolated from blueprint
            resolvedStepSide   = si != null ? si.getSnapSide() : null;
            resolvedStepAction = si != null && si.getSnapStepAction() != null
                    ? si.getSnapStepAction().name() : StepAction.APPROVE.name();
        }

        // ── navKey — role-specific ────────────────────────────────────────────
        // ASSIGNER tasks use snapAssignerNavKey → coordinator lands on their own page.
        // ACTOR tasks use snapNavKey → actor lands on the work page (FILL/REVIEW/etc.).
        // Both snapshotted at step instance creation, isolated from blueprint edits.
        String resolvedNavKey = (taskRole == TaskRole.ASSIGNER)
                ? (si != null ? si.getSnapAssignerNavKey() : null)
                : (si != null ? si.getSnapNavKey() : null);

        return TaskInstanceResponse.builder()
                .id(t.getId())
                .stepInstanceId(t.getStepInstanceId())
                .assignedUserId(t.getAssignedUserId())
                .status(t.getStatus())
                .taskRole(taskRole)
                .actedAt(t.getActedAt())
                .dueAt(t.getDueAt())
                .remarks(t.getRemarks())
                .delegatedToUserId(t.getDelegatedToUserId())
                .reassignedFromUserId(t.getReassignedFromUserId())
                .isAutoAssigned(t.isAutoAssigned())
                .assignedAt(t.getCreatedAt())
                // Step context from snapshot — never from blueprint
                .stepName(si != null ? si.getSnapName() : null)
                .stepOrder(si != null ? si.getSnapStepOrder() : null)
                .resolvedStepSide(resolvedStepSide)
                .resolvedStepAction(resolvedStepAction)
                .actorRoleName(t.getActorRoleName())
                .navKey(resolvedNavKey)
                .workflowInstanceId(wi != null ? wi.getId() : null)
                .entityType(wi != null ? wi.getEntityType() : null)
                .entityId(wi != null ? wi.getEntityId() : null)
                .workflowName(workflow != null ? workflow.getName() : null)
                .workflowId(wi != null ? wi.getWorkflowId() : null)
                .priority(wi != null ? wi.getPriority() : null)
                .workflowInstanceStatus(wi != null ? wi.getStatus().name() : null)
                .artifactId(artifactId)
                .entityTitle(entityTitle)
                .build();
    }



    /**
     * Expire a single stale task — called by the user from their inbox to dismiss
     * a task that belongs to a cancelled or otherwise terminal workflow instance.
     *
     * NEW — exposed via PATCH /v1/workflow-instances/tasks/{taskId}/expire.
     *
     * Only the assigned user or a platform admin may expire a task.
     * Tasks that are not PENDING cannot be expired (already terminal).
     * Tasks whose workflow instance is still IN_PROGRESS cannot be expired
     * (use WITHDRAW action instead).
     */
    @Transactional
    public TaskInstanceResponse expireTask(Long taskId, Long performedBy) {
        log.info("[WORKFLOW-TASK] Expiring stale task | taskId={} | performedBy={}", taskId, performedBy);

        TaskInstance task = taskInstanceRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("TaskInstance", taskId));

        // Only the assigned user may expire their own task
        if (!task.getAssignedUserId().equals(performedBy)) {
            throw new BusinessException("TASK_NOT_OWNED",
                    "You can only expire tasks assigned to you",
                    org.springframework.http.HttpStatus.FORBIDDEN);
        }

        if (task.getStatus() != TaskStatus.PENDING && task.getStatus() != TaskStatus.IN_PROGRESS) {
            throw new BusinessException("TASK_ALREADY_TERMINAL",
                    "Task is already in terminal status: " + task.getStatus());
        }

        // Guard: only allow expiry if the workflow instance is no longer active
        StepInstance si = stepInstanceRepository.findById(task.getStepInstanceId())
                .orElseThrow(() -> new ResourceNotFoundException("StepInstance", task.getStepInstanceId()));
        WorkflowInstance wi = instanceRepository.findById(si.getWorkflowInstanceId())
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowInstance", si.getWorkflowInstanceId()));

        if (wi.getStatus() == WorkflowStatus.IN_PROGRESS || wi.getStatus() == WorkflowStatus.ON_HOLD) {
            throw new BusinessException("INSTANCE_STILL_ACTIVE",
                    "Workflow instance is still active. Use WITHDRAW action to cancel participation.");
        }

        task.setStatus(TaskStatus.EXPIRED);
        task.setActedAt(LocalDateTime.now());
        task.setRemarks("Manually expired by user — workflow instance is " + wi.getStatus());
        taskInstanceRepository.save(task);

        log.info("[WORKFLOW-TASK] Task expired | taskId={} | instanceStatus={}",
                taskId, wi.getStatus());
        return toEnrichedTaskResponse(task);
    }

    /**
     * Maps a WorkflowInstanceHistory entity to its response DTO, given an
     * already-resolved userId -> displayName map. Callers must batch-resolve
     * names via toHistoryResponses() below rather than calling this directly
     * per-row — that's what caused the N+1 (one findById() per history row)
     * this method used to have inline.
     */
    private WorkflowHistoryResponse toHistoryResponse(WorkflowInstanceHistory h, Map<Long, String> nameMap) {
        String performedByName = h.getPerformedBy() != null ? nameMap.get(h.getPerformedBy()) : null;
        return WorkflowHistoryResponse.builder()
                .id(h.getId()).workflowInstanceId(h.getWorkflowInstanceId())
                .stepInstanceId(h.getStepInstanceId()).taskInstanceId(h.getTaskInstanceId())
                .eventType(h.getEventType()).fromStatus(h.getFromStatus()).toStatus(h.getToStatus())
                .performedBy(h.getPerformedBy()).performedByName(performedByName)
                .performedAt(h.getPerformedAt())
                .remarks(h.getRemarks())
                .stepId(h.getStepId()).stepName(h.getStepName()).stepOrder(h.getStepOrder())
                .build();
    }

    /**
     * Batch entry point for all three history queries (getFullHistory,
     * getHistoryByStep, getHistoryByUser). Resolves every distinct
     * performedBy id in ONE call to UserDisplayNameService (which itself
     * does at most one findAllById() for whatever isn't already
     * Redis-cached) instead of one userRepository.findById() per row —
     * a 200-row history page used to mean 200 remote DB round trips just
     * to show names.
     */
    private List<WorkflowHistoryResponse> toHistoryResponses(List<WorkflowInstanceHistory> rows) {
        Set<Long> userIds = rows.stream()
                .map(WorkflowInstanceHistory::getPerformedBy)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> nameMap = userDisplayNameService.resolveNames(userIds);
        return rows.stream().map(h -> toHistoryResponse(h, nameMap)).toList();
    }

    // ══════════════════════════════════════════════════════════════
    // COMPOUND TASK SUPPORT — called by TaskSectionCompletionService
    // ══════════════════════════════════════════════════════════════

    /**
     * Creates a sub-task for a section assignee on an existing StepInstance.
     *
     * Used by Case 2 (section-level assignment) when an assigner distributes
     * a section's work to other users within the same step.
     *
     * The sub-task appears in the assignee's inbox exactly like a regular task
     * but is linked to the same StepInstance as the parent task.
     * It does NOT count toward step ApprovalType — only the parent ACTOR task does.
     *
     * @param si          the active StepInstance
     * @param userId      the assignee receiving the sub-task
     * @param role        TaskRole.ACTOR always for sub-tasks
     * @param tenantId    org tenant
     * @param contextNote stored in remarks so the assignee sees why they received it
     */

    /**
     * Backward-compatible overload — delegates to the navKeyOverride variant with null.
     * All existing callers (contributor sub-tasks on FILL steps) are unaffected.
     */
    public TaskInstance createSubTask(StepInstance si, Long userId,
                                      TaskRole role, Long tenantId, String contextNote) {
        return createSubTask(si, userId, role, tenantId, contextNote, null);
    }

    /**
     * Creates a sub-task for a section assignee on an existing StepInstance.
     *
     * navKeyOverride (NEW):
     *   When non-null, the effective navKey for this sub-task is overridden to this value.
     *   Used by ReviewController.doCreateAssistantSubTask() to route review assistant
     *   sub-tasks to /assessments/:id/assistant-review (org_assistant_review navKey)
     *   instead of the reviewer's /assessments/:id/review page (org_assessment_review).
     *
     *   Pass null for all other callers — inherits the step's snap_nav_key as before.
     *
     * @param si             the active StepInstance
     * @param userId         the assignee
     * @param role           TaskRole.ACTOR always for sub-tasks
     * @param tenantId       org tenant
     * @param contextNote    stored in remarks (why this person received the sub-task)
     * @param navKeyOverride override navKey sent in the TaskAssigned event (null = inherit from step)
     */
    public TaskInstance createSubTask(StepInstance si, Long userId,
                                      TaskRole role, Long tenantId,
                                      String contextNote, String navKeyOverride) {
        TaskInstance subTask = TaskInstance.builder()
                .stepInstanceId(si.getId())
                .assignedUserId(userId)
                .status(TaskStatus.PENDING)
                .isAutoAssigned(true)
                .taskRole(role)
                .remarks(contextNote)
                .build();
        taskInstanceRepository.save(subTask);

        String stepName = si.getSnapName() != null ? si.getSnapName() : "workflow step";
        notificationService.send(userId, "SUB_TASK_ASSIGNMENT",
                "Work assigned: " + stepName, "TASK", subTask.getId());

        WorkflowInstance wi = instanceRepository.findById(si.getWorkflowInstanceId()).orElse(null);
        Long artifactId = wi != null ? entityResolverRegistry.resolveArtifactId(wi) : null;

        // Use navKeyOverride in the event so TaskInbox routes correctly.
        // The event carries the effective navKey the frontend will use.
        String effectiveNavKey = navKeyOverride != null ? navKeyOverride : si.getSnapNavKey();

        eventPublisher.publishEvent(new WorkflowEvent.TaskAssigned(
                wi != null ? wi.getId() : null,
                si.getId(), subTask.getId(), userId, stepName,
                wi != null ? wi.getEntityType() : null,
                wi != null ? wi.getEntityId() : null,
                artifactId,
                effectiveNavKey,
                si.getSnapSide(),
                si.getSnapStepAction() != null ? si.getSnapStepAction().name() : "FILL",
                role.name()
        ));

        log.info("[WORKFLOW] Sub-task created | id={} | userId={} | step='{}' | navKeyOverride={}",
                subTask.getId(), userId, stepName, navKeyOverride);
        return subTask;
    }

    // ══════════════════════════════════════════════════════════════
    // UNASSIGNED RECOVERY
    // ══════════════════════════════════════════════════════════════

    /**
     * Manually push an ACTOR task to a specific user on a step that is
     * IN_PROGRESS or UNASSIGNED with zero actor tasks.
     *
     * Called by the admin endpoint POST /{instanceId}/steps/{stepInstanceId}/manual-assign.
     * Also used as the self-healing path when an admin adds the missing role to a user
     * and the StepSlaMonitor retries assignment automatically.
     *
     * Idempotent — if the user already has a PENDING ACTOR task on this step, returns it.
     */
    public TaskInstanceResponse manualAssignTask(Long stepInstanceId, Long userId, Long assignedBy) {
        StepInstance si = stepInstanceRepository.findById(stepInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("StepInstance", stepInstanceId));
        WorkflowInstance instance = instanceRepository.findById(si.getWorkflowInstanceId())
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowInstance", si.getWorkflowInstanceId()));

        // Idempotent — skip if user already has a PENDING ACTOR task on this step
        boolean alreadyAssigned = taskInstanceRepository.findByStepInstanceId(stepInstanceId)
                .stream()
                .anyMatch(t -> userId.equals(t.getAssignedUserId())
                        && t.getTaskRole() == TaskRole.ACTOR
                        && t.getStatus() == TaskStatus.PENDING);

        if (alreadyAssigned) {
            log.info("[WF-MANUAL-ASSIGN] Idempotent — user {} already has ACTOR task on step {}",
                    userId, stepInstanceId);
            return taskInstanceRepository.findByStepInstanceId(stepInstanceId).stream()
                    .filter(t -> userId.equals(t.getAssignedUserId()) && t.getTaskRole() == TaskRole.ACTOR)
                    .findFirst()
                    .map(this::toEnrichedTaskResponse)
                    .orElseThrow(() -> new ResourceNotFoundException("TaskInstance", stepInstanceId));
        }

        TaskInstance task = createTask(si, userId, false, TaskRole.ACTOR,
                si.getSnapSide(), instance.getTenantId());
        task.setRemarks("Manually assigned by admin (userId=" + assignedBy + ")");
        taskInstanceRepository.save(task);
        sectionCompletionService.snapshotSectionsForTask(task, si, instance);

        // Transition UNASSIGNED → IN_PROGRESS on first assignment
        transitionStepToInProgressIfAwaiting(si, instance, assignedBy, "Manually assigned");

        notificationService.send(userId, "TASK_ASSIGNED",
                "You have been assigned to: " + si.getSnapName(), "STEP", si.getId());

        log.info("[WF-MANUAL-ASSIGN] Task created | taskId={} | stepInstanceId={} | userId={} | assignedBy={}",
                task.getId(), stepInstanceId, userId, assignedBy);

        return toEnrichedTaskResponse(task);
    }

    /**
     * Returns all IN_PROGRESS step instances platform-wide that have zero
     * PENDING or APPROVED ACTOR tasks — these steps cannot advance on their own.
     *
     * Each result includes the manual-assign endpoint URL so an admin can fix it
     * in one HTTP call without touching the database.
     */
    public List<Map<String, Object>> getStuckSteps() {
        List<StepInstance> inProgress = stepInstanceRepository.findByStatus(StepStatus.IN_PROGRESS);

        List<Map<String, Object>> stuck = new java.util.ArrayList<>();
        for (StepInstance si : inProgress) {
            long actorTasks = taskInstanceRepository.countByStepInstanceIdAndTaskRole(
                    si.getId(), TaskRole.ACTOR);
            if (actorTasks > 0) continue;  // has actors — not stuck

            WorkflowInstance wi = instanceRepository.findById(si.getWorkflowInstanceId()).orElse(null);
            if (wi == null) continue;

            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("stepInstanceId",    si.getId());
            entry.put("stepName",          si.getSnapName());
            entry.put("stepStatus",        si.getStatus());
            entry.put("startedAt",         si.getStartedAt());
            entry.put("slaDueAt",          si.getSlaDueAt());
            entry.put("workflowInstanceId", wi.getId());
            entry.put("entityType",        wi.getEntityType());
            entry.put("entityId",          wi.getEntityId());
            entry.put("tenantId",          wi.getTenantId());
            entry.put("initiatedBy",       wi.getInitiatedBy());
            entry.put("fixEndpoint",
                    "/v1/workflow-instances/" + wi.getId() +
                            "/steps/" + si.getId() +
                            "/manual-assign?userId=<TARGET_USER_ID>");
            stuck.add(entry);
        }

        log.info("[WF-ADMIN] getStuckSteps — found {} stuck step(s)", stuck.size());
        return stuck;
    }

    /**
     * Re-evaluates the approval gate on a specific step and advances the workflow
     * if isStepApprovalSatisfied() now returns true.
     *
     * Use case: step 4 got stuck because legacy contributor REJECTED tasks were
     * being counted in the approval denominator (now fixed). Call this endpoint
     * on affected instances to immediately unblock them without data migration.
     */
    @Transactional
    public Map<String, Object> reEvaluateStep(Long instanceId, Long stepInstanceId, Long performedBy) {
        WorkflowInstance instance = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowInstance", instanceId));
        StepInstance si = stepInstanceRepository.findById(stepInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("StepInstance", stepInstanceId));

        if (!si.getWorkflowInstanceId().equals(instanceId)) {
            throw new BusinessException("MISMATCH", "StepInstance does not belong to this workflow instance");
        }
        if (si.getStatus() != StepStatus.IN_PROGRESS) {
            return Map.of("advanced", false, "reason",
                    "Step is not IN_PROGRESS (status=" + si.getStatus() + ")");
        }

        boolean satisfied = isStepApprovalSatisfied(si);
        log.info("[WF-ADMIN] re-evaluate | stepInstanceId={} | satisfied={}", stepInstanceId, satisfied);

        if (!satisfied) {
            long total    = taskInstanceRepository.countByStepInstanceIdAndTaskRoleAndStatusNot(
                    si.getId(), TaskRole.ACTOR, TaskStatus.REJECTED);
            long approved = taskInstanceRepository.countByStepInstanceIdAndTaskRoleAndStatus(
                    si.getId(), TaskRole.ACTOR, TaskStatus.APPROVED);
            return Map.of("advanced", false, "reason",
                    "Gate not satisfied — approved=" + approved + " / total(excl.rejected)=" + total);
        }

        // Gate passes — complete step and advance
        completeStep(si, StepStatus.APPROVED, "Re-evaluated and auto-advanced by admin");
        expirePendingTasks(si, null);
        recordHistory(instance, si, null, "STEP_APPROVED",
                StepStatus.IN_PROGRESS.name(), StepStatus.APPROVED.name(), performedBy,
                "Re-evaluated by admin — step gate satisfied");
        eventPublisher.publishEvent(new WorkflowEvent.StepCompleted(
                instance.getId(), si.getId(), si.getSnapName(), "APPROVED", performedBy));

        Optional<WorkflowStep> nextStep = stepRepository
                .findFirstByWorkflowIdAndStepOrderGreaterThanOrderByStepOrderAsc(
                        instance.getWorkflowId(), si.getSnapStepOrder());

        if (nextStep.isPresent()) {
            StepInstance newSI = createStepInstance(instance, nextStep.get());
            instance.setCurrentStepId(newSI.getId());
            instanceRepository.save(instance);
            assignTasksForStep(newSI, nextStep.get(), instance);
            recordHistory(instance, newSI, null, "STEP_STARTED", null, newSI.getStatus().name(),
                    performedBy, "Moved to: " + nextStep.get().getName());
            eventPublisher.publishEvent(new WorkflowEvent.StepAdvanced(
                    instance.getId(), newSI.getId(), nextStep.get().getName(),
                    nextStep.get().getStepOrder(), newSI.getStatus().name(), performedBy));
            log.info("[WF-ADMIN] Step advanced | from='{}' | to='{}'", si.getSnapName(), nextStep.get().getName());
            return Map.of("advanced", true, "nextStep", nextStep.get().getName(),
                    "nextStepInstanceId", newSI.getId());
        } else {
            instance.setStatus(WorkflowStatus.COMPLETED);
            instanceRepository.save(instance);
            return Map.of("advanced", true, "nextStep", "WORKFLOW_COMPLETED");
        }
    }


    /**
     * Reset a task back to IN_PROGRESS so the assignee can re-work it.
     *
     * Workflow-layer half of the admin "reopen task" feature. Resets task status
     * and re-arms compound section gates. Assessment-domain state (reviewer
     * submitted sections, evaluations) is handled by AssessmentController separately.
     *
     *   1. Reset task status to IN_PROGRESS, clear acted_at
     *   2. Reset all TaskSectionCompletion rows (completed=false, re-arms gates)
     *   3. If step was APPROVED, revert to IN_PROGRESS so engine knows it's not done
     *   4. Write audit history
     */
    @Transactional
    public Map<String, Object> resetTask(Long instanceId, Long taskId, Long performedBy, boolean rollbackDownstream) {
        WorkflowInstance instance = instanceRepository.findById(instanceId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowInstance", instanceId));

        TaskInstance task = taskInstanceRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("TaskInstance", taskId));

        StepInstance si = stepInstanceRepository.findById(task.getStepInstanceId())
                .orElseThrow(() -> new ResourceNotFoundException("StepInstance", task.getStepInstanceId()));

        if (!si.getWorkflowInstanceId().equals(instanceId)) {
            throw new BusinessException("MISMATCH", "Task does not belong to this workflow instance");
        }

        String previousStatus = task.getStatus().name();

        // 1. Reset task
        task.setStatus(TaskStatus.IN_PROGRESS);
        task.setActedAt(null);
        task.setRemarks("Reset by admin (was " + previousStatus + ") — re-work in progress");
        taskInstanceRepository.save(task);
        log.info("[WF-ADMIN] RESET-TASK | taskId={} | {} -> IN_PROGRESS", taskId, previousStatus);

        // 2. Re-arm all section completion gates
        List<TaskSectionCompletion> sections =
                taskSectionCompletionRepository
                        .findByTaskInstanceIdOrderBySnapSectionOrderAsc(taskId);
        for (TaskSectionCompletion sc : sections) {
            sc.setCompleted(false);
            sc.setCompletedAt(null);
            sc.setCompletedBy(null);
            sc.setRemarks("Reset by admin");
        }
        taskSectionCompletionRepository.saveAll(sections);
        log.info("[WF-ADMIN] RESET-TASK | {} section gate(s) re-armed for taskId={}", sections.size(), taskId);

        // Also clear task_section_items for the reset task itself — items from the
        // previous run may have status=DONE. The SectionItemsNeededEvent re-fire
        // below will re-register them fresh so they start at NOT_DONE.
        List<com.kashi.grc.workflow.domain.TaskSectionItem> existingItems =
                taskSectionItemRepository.findByStepInstanceId(si.getId());
        if (!existingItems.isEmpty()) {
            taskSectionItemRepository.deleteAll(existingItems);
            log.info("[WF-ADMIN] RESET-TASK | cleared {} task_section_item(s) for re-registration | taskId={}",
                    existingItems.size(), taskId);
        }

        // 3. Revert step to IN_PROGRESS if it was already APPROVED.
        // Capture whether the step was reverted — used in step 4 to decide
        // whether downstream steps need to be rolled back.
        boolean stepReverted = si.getStatus() == StepStatus.APPROVED;
        if (stepReverted) {
            si.setStatus(StepStatus.IN_PROGRESS);
            si.setCompletedAt(null);
            stepInstanceRepository.save(si);
            log.info("[WF-ADMIN] RESET-TASK | stepInstance={} APPROVED -> IN_PROGRESS", si.getId());
        }

        // 4. Roll back all subsequent steps and expire their tasks —
        //    BUT ONLY when the step itself was reverted (was APPROVED → IN_PROGRESS).
        //
        // If the step is already IN_PROGRESS with multiple tasks and only ONE task is
        // being reset (e.g. reopening Rohan's task while Karan's is still PENDING),
        // the step stays IN_PROGRESS, downstream steps are unaffected, and other
        // responders keep their work. Rollback only makes sense when the entire step
        // was completed and is being pulled back — that's when downstream is invalid.
        //
        // Example A — rollback NEEDED:
        //   Step 5 was APPROVED (CISO confirmed all sections). Step 6 has active tasks.
        //   Reset step 5 → downstream must be rolled back so CISO can reassign fresh.
        //
        // Example B — rollback NOT needed:
        //   Step 6 is IN_PROGRESS. Rohan's task is reset. Karan's task is still PENDING.
        //   Step 6 stays IN_PROGRESS. Steps 7+ are untouched. Only Rohan re-does his work.
        if (rollbackDownstream) {
            // Determine the current step's order. snapStepOrder may be null on older
            // step instances created before the field was added — fall back to looking
            // it up from the blueprint step via stepId.
            Integer currentOrder = si.getSnapStepOrder();
            if (currentOrder == null && si.getStepId() != null) {
                currentOrder = stepRepository.findById(si.getStepId())
                        .map(WorkflowStep::getStepOrder).orElse(null);
            }
            log.info("[WF-ADMIN] RESET-TASK rollback | currentStepInstanceId={} snapStepOrder={} resolved={}",
                    si.getId(), si.getSnapStepOrder(), currentOrder);

            final Integer resolvedOrder = currentOrder;
            List<StepInstance> allSteps = stepInstanceRepository.findByWorkflowInstanceId(instanceId);
            log.info("[WF-ADMIN] RESET-TASK rollback | total step instances in workflow={} orders={}",
                    allSteps.size(),
                    allSteps.stream().map(s -> s.getId() + ":" + s.getSnapStepOrder()).toList());

            List<StepInstance> subsequentSteps = allSteps.stream()
                    .filter(s -> !s.getId().equals(si.getId()))
                    .filter(s -> {
                        // If we have order info, filter by order
                        if (resolvedOrder != null && s.getSnapStepOrder() != null) {
                            return s.getSnapStepOrder() > resolvedOrder;
                        }
                        // If snapStepOrder is missing on downstream steps, include ALL
                        // other non-terminal step instances as a safe fallback
                        return s.getStatus() == StepStatus.IN_PROGRESS
                                || s.getStatus() == StepStatus.AWAITING_ASSIGNMENT;
                    })
                    .toList();

            if (!subsequentSteps.isEmpty()) {
                List<Long> subsequentStepIds = subsequentSteps.stream()
                        .map(StepInstance::getId).toList();

                // Expire all non-terminal tasks on those steps
                List<TaskInstance> downstreamTasks =
                        taskInstanceRepository.findByStepInstanceIdIn(subsequentStepIds);
                int expiredCount = 0;
                for (TaskInstance dt : downstreamTasks) {
                    if (dt.getStatus() != TaskStatus.EXPIRED
                            && dt.getStatus() != TaskStatus.APPROVED) {
                        dt.setStatus(TaskStatus.EXPIRED);
                        dt.setRemarks("Expired — upstream step was reset for rework");
                        taskInstanceRepository.save(dt);
                        expiredCount++;
                    }
                }

                // Delete child task_section_completions FIRST to satisfy the FK
                // constraint (fk_tsc_step_i: task_section_completions.step_instance_id
                // → step_instances.id). Deleting step instances without clearing
                // their children throws a DataIntegrityViolationException.
                for (StepInstance ds : subsequentSteps) {
                    // Delete workflow_task_actions FIRST (FK: workflow_task_actions.task_instance_id
                    // → task_instances.id). Task action audit rows must be cleared before the
                    // task_instances themselves can be removed via the step_instances cascade.
                    List<com.kashi.grc.workflow.domain.WorkflowTaskAction> wta =
                            actionRepository.findByStepInstanceIdOrderByPerformedAtAsc(ds.getId());
                    if (!wta.isEmpty()) {
                        actionRepository.deleteAll(wta);
                        log.info("[WF-ADMIN] RESET-TASK rollback | deleted {} workflow_task_action(s) for stepInstanceId={}",
                                wta.size(), ds.getId());
                    }
                    // Delete task_section_items (FK: task_section_items.step_instance_id → step_instances.id)
                    List<com.kashi.grc.workflow.domain.TaskSectionItem> tsi =
                            taskSectionItemRepository.findByStepInstanceId(ds.getId());
                    if (!tsi.isEmpty()) {
                        taskSectionItemRepository.deleteAll(tsi);
                        log.info("[WF-ADMIN] RESET-TASK rollback | deleted {} task_section_item(s) for stepInstanceId={}",
                                tsi.size(), ds.getId());
                    }
                    // Delete task_section_completions (FK: task_section_completions.step_instance_id → step_instances.id)
                    List<TaskSectionCompletion> tsc =
                            taskSectionCompletionRepository.findByStepInstanceId(ds.getId());
                    if (!tsc.isEmpty()) {
                        taskSectionCompletionRepository.deleteAll(tsc);
                        log.info("[WF-ADMIN] RESET-TASK rollback | deleted {} task_section_completion(s) for stepInstanceId={}",
                                tsc.size(), ds.getId());
                    }
                }
                // Now safe to delete the step instances
                stepInstanceRepository.deleteAll(subsequentSteps);

                log.info("[WF-ADMIN] RESET-TASK | rolled back {} downstream step(s), expired {} task(s) | instanceId={} | requestedRollback=true",
                        subsequentSteps.size(), expiredCount, instanceId);
            }
        }

        // Re-fire SectionItemsNeededEvent AFTER rollback so items are not deleted
        // by the downstream rollback above. Items registered before rollback were
        // immediately deleted when downstream step_instances were removed.
        Long wfInstanceId = si.getWorkflowInstanceId();
        Long wfTenantId = instanceRepository.findById(wfInstanceId)
                .map(wi -> wi.getTenantId()).orElse(null);
        if (wfTenantId != null) {
            for (TaskSectionCompletion sc : sections) {
                if (sc.isSnapTracksItems() && sc.getSnapItemRefType() != null) {
                    eventPublisher.publishEvent(new com.kashi.grc.workflow.event.SectionItemsNeededEvent(
                            task.getId(),
                            si.getId(),
                            wfInstanceId,
                            wfTenantId,
                            sc.getSnapSectionKey(),
                            sc.getSnapItemRefType(),
                            sc.getSnapSectionScreenKey(),
                            sc.getSnapItemScreenKey(),
                            sc.getSnapSectionUiJson(),
                            task.getAssignedUserId()
                    ));
                    log.info("[WF-ADMIN] RESET-TASK | re-fired SectionItemsNeededEvent (post-rollback) | " +
                                    "sectionKey={} | itemRefType={} | taskId={}",
                            sc.getSnapSectionKey(), sc.getSnapItemRefType(), task.getId());
                }
            }
        }

        // 5. Audit trail
        recordHistory(instance, si, task, "TASK_RESET",
                previousStatus, TaskStatus.IN_PROGRESS.name(), performedBy,
                "Task reset by admin — assignee can re-work");

        return Map.of(
                "reset",          true,
                "taskId",         taskId,
                "previousStatus", previousStatus,
                "sectionsReset",  sections.size()
        );
    }

    // ── Eligible users for a step — used by the assign UI ─────────────────────

    public StepInstance getStepInstance(Long stepInstanceId) {
        return stepInstanceRepository.findById(stepInstanceId).orElse(null);
    }

    /**
     * Returns the next WorkflowStep after the given step instance,
     * based on snapStepOrder + 1 within the same workflow.
     * Returns empty if the step instance has no snapStepOrder or no next step exists.
     */
    public java.util.Optional<WorkflowStep> getNextStep(StepInstance si) {
        if (si.getSnapStepOrder() == null) return java.util.Optional.empty();
        return instanceRepository.findById(si.getWorkflowInstanceId())
                .flatMap(wi -> stepRepository
                        .findByWorkflowIdAndStepOrder(wi.getWorkflowId(), si.getSnapStepOrder() + 1));
    }

    /**
     * Walks forward from the given step instance to find the next step that has
     * actor roles configured. Walks up to 3 steps forward.
     * Used by eligible-users endpoint for ASSIGN-to-next-step pattern.
     */
    public List<WorkflowStepRole> getEligibleActorRoles(StepInstance si) {
        var nextStepOpt = getNextStep(si);
        if (nextStepOpt.isEmpty()) return getStepActorRoles(si.getStepId());

        List<WorkflowStepRole> roles = getStepActorRoles(nextStepOpt.get().getId());
        if (!roles.isEmpty()) return roles;

        if (si.getSnapStepOrder() == null) return List.of();
        // Walk forward up to 3 more steps
        return instanceRepository.findById(si.getWorkflowInstanceId())
                .map(wi -> {
                    int order = si.getSnapStepOrder() + 1;
                    for (int i = 0; i < 3; i++) {
                        order++;
                        var further = stepRepository.findByWorkflowIdAndStepOrder(wi.getWorkflowId(), order);
                        if (further.isEmpty()) break;
                        List<WorkflowStepRole> r = getStepActorRoles(further.get().getId());
                        if (!r.isEmpty()) return r;
                    }
                    return List.<WorkflowStepRole>of();
                })
                .orElse(List.of());
    }

    public List<WorkflowStepRole> getStepActorRoles(Long stepId) {
        return stepRoleRepository.findByStepId(stepId);
    }

    /**
     * Returns users who have any of the given roles, scoped to the tenant.
     * Deduplicates by userId so users with multiple matching roles appear once.
     */
    public List<Map<String, Object>> getUsersByRoles(List<Long> roleIds, Long tenantId) {
        // Find distinct userIds who have any of these roles in this tenant
        // Also track which roleId each user was found under (for roleName/side in response)
        java.util.Map<Long, Long> userToRole = new java.util.LinkedHashMap<>();
        for (Long roleId : roleIds) {
            for (Long userId : dbRepository.findUserIdsByRoleAndTenant(roleId, tenantId)) {
                userToRole.putIfAbsent(userId, roleId); // first matching role wins
            }
        }
        if (userToRole.isEmpty()) return List.of();

        // Build a roleId → Role map for name/side lookup
        java.util.Map<Long, com.kashi.grc.usermanagement.domain.Role> roleMap =
                roleRepository.findAllById(roleIds).stream()
                        .collect(java.util.stream.Collectors.toMap(
                                com.kashi.grc.usermanagement.domain.Role::getId,
                                r -> r));

        // Load user details for those ids
        return userRepository.findAllById(userToRole.keySet()).stream()
                .filter(u -> !u.isDeleted())
                .map(u -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id",        u.getId());
                    m.put("firstName", u.getFirstName());
                    m.put("lastName",  u.getLastName());
                    m.put("email",     u.getEmail());
                    m.put("fullName",  u.getFirstName() + " " + u.getLastName());
                    // Include role name and side so frontend can filter/display correctly
                    com.kashi.grc.usermanagement.domain.Role role = roleMap.get(userToRole.get(u.getId()));
                    if (role != null) {
                        m.put("roleName", role.getName());
                        m.put("side",     role.getSide() != null ? role.getSide().name() : null);
                    }
                    return m;
                })
                .toList();
    }

}