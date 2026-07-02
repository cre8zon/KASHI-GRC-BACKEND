package com.kashi.grc.workflow.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.dto.PaginatedResponse;
import com.kashi.grc.common.repository.DbRepository;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.workflow.domain.WorkflowInstance;
import com.kashi.grc.workflow.dto.request.StartWorkflowRequest;
import com.kashi.grc.workflow.dto.request.TaskActionRequest;
import com.kashi.grc.workflow.dto.response.*;
import com.kashi.grc.workflow.enums.WorkflowStatus;
import com.kashi.grc.workflow.service.WorkflowAccessService;
import com.kashi.grc.workflow.service.WorkflowEngineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.criteria.Predicate;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * WorkflowInstanceController — running instances, task actions, and history.
 *
 * INSTANCE MANAGEMENT:
 *   POST   /v1/workflow-instances              — start a workflow for an entity
 *   GET    /v1/workflow-instances/{id}         — get full instance with steps + tasks
 *   GET    /v1/workflow-instances              — list (paginated, filterable)
 *   GET    /v1/workflow-instances/active       — get active instance for an entity
 *   PATCH  /v1/workflow-instances/{id}/cancel  — admin cancel
 *   PATCH  /v1/workflow-instances/{id}/hold    — put on hold
 *   PATCH  /v1/workflow-instances/{id}/resume  — resume from hold
 *
 * TASK ACTIONS (the core engine driver):
 *   POST   /v1/workflow-instances/tasks/action           — APPROVE/REJECT/SEND_BACK/REASSIGN/DELEGATE/ESCALATE/COMMENT/WITHDRAW
 *   GET    /v1/workflow-instances/tasks/user/{userId}    — user's pending tasks (inbox)
 *   GET    /v1/workflow-instances/tasks/user/{userId}/all — all tasks for user
 *   GET    /v1/workflow-instances/tasks/step/{stepInstanceId} — all tasks in a step
 *   POST   /v1/workflow-instances/tasks/assign           — manual assignment (legacy, role resolution)
 *
 * UNASSIGNED RECOVERY (admin):
 *   POST   /v1/workflow-instances/{instanceId}/steps/{stepInstanceId}/manual-assign?userId=X
 *     — Push an ACTOR task to a specific user on a step that is UNASSIGNED or IN_PROGRESS.
 *       Step transitions UNASSIGNED → IN_PROGRESS on the first successful assignment.
 *       Idempotent: duplicate for same user+step is silently skipped.
 *
 * OPS VISIBILITY (admin):
 *   GET    /v1/admin/workflow/stuck-steps
 *     — Returns all IN_PROGRESS steps with zero actor tasks. Each result includes
 *       the entity type/id, tenant, and the exact manual-assign URL to fix it.
 *
 * HISTORY (audit trail):
 *   GET    /v1/workflow-instances/{id}/history           — full chronological audit trail
 *   GET    /v1/workflow-instances/{id}/history/step/{stepId} — history for one step
 *   GET    /v1/workflow-instances/history/user/{userId}  — all actions by a user
 *
 * READS: org users see only their own tenant's instances.
 * WRITES: org users can start/action; Platform Admin can also cancel/hold/resume any instance.
 */
@Slf4j
@RestController
@RequestMapping("/v1/workflow-instances")
@Tag(name = "Workflow Instances", description = "Start and drive workflow executions for business entities")
@RequiredArgsConstructor
public class WorkflowInstanceController {

    private final WorkflowEngineService service;
    private final DbRepository          dbRepository;
    private final UtilityService        utilityService;
    private final WorkflowAccessService accessService;
    // Change 1: roleRepository for eligible-users side/role filtering
    private final com.kashi.grc.usermanagement.repository.RoleRepository roleRepository;
    private final com.kashi.grc.audit.repository.AuditEngagementRepository engagementRepository;

    // ══════════════════════════════════════════════════════════════
    // INSTANCE MANAGEMENT
    // ══════════════════════════════════════════════════════════════

    @PostMapping
    @Operation(summary = "Start a workflow instance for a business entity")
    public ResponseEntity<ApiResponse<WorkflowInstanceResponse>> start(
            @Valid @RequestBody StartWorkflowRequest req) {

        Long tenantId     = utilityService.getLoggedInDataContext().getTenantId();
        Long initiatedBy  = utilityService.getLoggedInDataContext().getId();
        log.info("[WF-INSTANCE] START | workflowId={} | entityType='{}' | entityId={} | tenantId={}",
                req.getWorkflowId(), req.getEntityType(), req.getEntityId(), tenantId);

        WorkflowInstanceResponse response = service.startWorkflow(req, tenantId, initiatedBy);
        log.info("[WF-INSTANCE] Started | instanceId={} | currentStep='{}'",
                response.getId(), response.getCurrentStepName());

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    /**
     * GET /v1/workflow-instances/tasks/user/me/count
     * Returns pending task count for the current authenticated user.
     * Used by nav sidebar badge_count_endpoint on inbox items.
     * Must be declared BEFORE /{id} so Spring matches it as a literal path
     * rather than routing "tasks" as the {id} path variable.
     */
    @GetMapping("/tasks/user/me/count")
    @Operation(summary = "Count of pending tasks for the current user — used for nav badge")
    public ResponseEntity<ApiResponse<Long>> getMyTaskCount() {
        Long userId = utilityService.getLoggedInDataContext().getId();
        long count = service.getPendingTasksForUser(userId).size();
        return ResponseEntity.ok(ApiResponse.success(count));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get full instance detail — steps, tasks, and their statuses")
    public ResponseEntity<ApiResponse<WorkflowInstanceResponse>> getInstance(@PathVariable Long id) {
        log.debug("[WF-INSTANCE] GET | id={}", id);
        return ResponseEntity.ok(ApiResponse.success(service.getInstanceById(id)));
    }

    @GetMapping
    @Operation(summary = "List workflow instances — paginated, filterable by status/entity/priority")
    public ResponseEntity<ApiResponse<PaginatedResponse<WorkflowInstanceResponse>>> list(
            @RequestParam Map<String, String> allParams) {

        boolean isSystem = utilityService.isSystemUser();
        Long tenantId    = isSystem ? null : utilityService.getLoggedInDataContext().getTenantId();
        log.debug("[WF-INSTANCE] LIST | isSystem={} | tenantId={}", isSystem, tenantId);

        return ResponseEntity.ok(ApiResponse.success(dbRepository.findAll(
                WorkflowInstance.class,
                utilityService.getpageDetails(allParams),
                (cb, root) -> {
                    List<Predicate> preds = new ArrayList<>();
                    if (!isSystem) preds.add(cb.equal(root.get("tenantId"), tenantId));
                    if (allParams.containsKey("status"))
                        preds.add(cb.equal(root.get("status"),
                                WorkflowStatus.valueOf(allParams.get("status").toUpperCase())));
                    if (allParams.containsKey("entityType"))
                        preds.add(cb.equal(root.get("entityType"), allParams.get("entityType")));
                    if (allParams.containsKey("entityId"))
                        preds.add(cb.equal(root.get("entityId"), Long.parseLong(allParams.get("entityId"))));
                    if (allParams.containsKey("priority"))
                        preds.add(cb.equal(root.get("priority"), allParams.get("priority")));
                    return preds;
                },
                (cb, root) -> Map.of(
                        "status",     root.get("status"),
                        "entitytype", root.get("entityType"),
                        "priority",   root.get("priority")),
                service::buildInstanceResponse
        )));
    }

    @GetMapping("/active")
    @Operation(summary = "Get the active instance for a specific entity (IN_PROGRESS / PENDING / ON_HOLD)")
    public ResponseEntity<ApiResponse<WorkflowInstanceResponse>> getActive(
            @RequestParam String entityType,
            @RequestParam Long entityId) {

        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        log.debug("[WF-INSTANCE] GET ACTIVE | tenantId={} | entityType='{}' | entityId={}", tenantId, entityType, entityId);
        return ResponseEntity.ok(ApiResponse.success(
                service.getActiveInstanceForEntity(tenantId, entityType, entityId)));
    }

    @PatchMapping("/{id}/cancel")
    @Operation(summary = "Cancel an instance — terminal action, cannot be undone")
    public ResponseEntity<ApiResponse<Map<String, Object>>> cancel(
            @PathVariable Long id,
            @RequestParam(required = false) String remarks) {

        Long performedBy = utilityService.getLoggedInDataContext().getId();
        log.warn("[WF-INSTANCE] CANCEL | id={} | performedBy={}", id, performedBy);
        service.cancelInstance(id, performedBy, remarks);
        return ResponseEntity.ok(ApiResponse.success(Map.of("instanceId", id, "status", "CANCELLED")));
    }

    @PatchMapping("/{id}/hold")
    @Operation(summary = "Put an IN_PROGRESS instance on hold — pending tasks remain until resumed")
    public ResponseEntity<ApiResponse<Map<String, Object>>> hold(
            @PathVariable Long id,
            @RequestParam(required = false) String remarks) {

        Long performedBy = utilityService.getLoggedInDataContext().getId();
        log.info("[WF-INSTANCE] HOLD | id={} | performedBy={}", id, performedBy);
        service.holdInstance(id, performedBy, remarks);
        return ResponseEntity.ok(ApiResponse.success(Map.of("instanceId", id, "status", "ON_HOLD")));
    }

    @PatchMapping("/{id}/resume")
    @Operation(summary = "Resume an ON_HOLD instance back to IN_PROGRESS")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resume(@PathVariable Long id) {
        Long performedBy = utilityService.getLoggedInDataContext().getId();
        log.info("[WF-INSTANCE] RESUME | id={} | performedBy={}", id, performedBy);
        service.resumeInstance(id, performedBy);
        return ResponseEntity.ok(ApiResponse.success(Map.of("instanceId", id, "status", "IN_PROGRESS")));
    }

    // ══════════════════════════════════════════════════════════════
    // TASK ACTIONS
    // ══════════════════════════════════════════════════════════════

    @PostMapping({"/tasks/action", "/tasks/{taskId}/action"})
    @Operation(summary = "Perform an action on a task — APPROVE | REJECT | SEND_BACK | REASSIGN | DELEGATE | ESCALATE | COMMENT | WITHDRAW")
    public ResponseEntity<ApiResponse<WorkflowInstanceResponse>> action(
            @PathVariable(required = false) Long taskId,
            @Valid @RequestBody TaskActionRequest req) {

        if (taskId != null) req.setTaskInstanceId(taskId);

        Long performedBy = utilityService.getLoggedInDataContext().getId();
        log.info("[WF-TASK] ACTION {} | taskId={} | performedBy={}", req.getActionType(), req.getTaskInstanceId(), performedBy);

        WorkflowInstanceResponse response = service.performAction(req, performedBy);
        log.info("[WF-TASK] Action done | {} | taskId={} | instanceStatus={}", req.getActionType(), req.getTaskInstanceId(), response.getStatus());

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * POST /v1/workflow-instances/steps/{stepInstanceId}/override
     *
     * Step override action — advances a step without requiring a task.
     * Requires workflow:step:override permission (checked via @PreAuthorize or service layer).
     * Used by users with override authority (e.g. lead auditor advancing step 5 manually).
     */
    @PostMapping("/steps/{stepInstanceId}/override")
    @Operation(summary = "Override-advance a step without a task (requires workflow:step:override permission)")
    public ResponseEntity<ApiResponse<WorkflowInstanceResponse>> overrideStep(
            @PathVariable Long stepInstanceId,
            @RequestBody(required = false) java.util.Map<String, Object> body) {

        Long performedBy = utilityService.getLoggedInDataContext().getId();
        String action  = body != null && body.get("action")  != null ? body.get("action").toString()  : "APPROVE";
        String remarks = body != null && body.get("remarks") != null ? body.get("remarks").toString() : null;

        log.info("[WF-STEP] OVERRIDE {} | stepInstanceId={} | performedBy={}", action, stepInstanceId, performedBy);

        WorkflowInstanceResponse response = service.overrideAdvanceStep(stepInstanceId, action, remarks, performedBy);

        log.info("[WF-STEP] Override done | stepInstanceId={} | instanceStatus={}", stepInstanceId, response.getStatus());

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/tasks/user/{userId}")
    @Operation(summary = "Get pending tasks for a user — their workflow inbox")
    public ResponseEntity<ApiResponse<List<TaskInstanceResponse>>> getPendingTasks(@PathVariable Long userId) {
        log.debug("[WF-TASK] GET PENDING | userId={}", userId);
        return ResponseEntity.ok(ApiResponse.success(service.getPendingTasksForUser(userId)));
    }

    @GetMapping("/tasks/my-next")
    @Operation(summary = "After task completion — find current user's next pending task on the same entity or its parent project")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> getMyNextTask(
            @RequestParam String entityType,
            @RequestParam Long entityId) {

        Long userId = utilityService.getLoggedInDataContext().getId();
        log.debug("[WF-TASK] MY-NEXT | userId={} | entityType={} | entityId={}", userId, entityType, entityId);

        List<TaskInstanceResponse> pending = service.getPendingTasksForUser(userId);

        // 1. Direct match — task on this exact entity
        var next = pending.stream()
                .filter(t -> entityType.equals(t.getEntityType())
                        && entityId.equals(t.getEntityId()))
                .findFirst()
                .orElse(null);

        // 2. If this is an engagement page, also look for tasks on its parent project
        if (next == null && "AUDIT_ENGAGEMENT".equals(entityType)) {
            var eng = engagementRepository.findById(entityId).orElse(null);
            if (eng != null && eng.getProjectInstanceId() != null) {
                Long projectId = eng.getProjectInstanceId();
                next = pending.stream()
                        .filter(t -> "AUDIT_PROJECT".equals(t.getEntityType())
                                && projectId.equals(t.getEntityId()))
                        .findFirst()
                        .orElse(null);
            }
        }

        if (next == null) {
            return ResponseEntity.ok(ApiResponse.success(java.util.Map.of()));
        }

        return ResponseEntity.ok(ApiResponse.success(java.util.Map.of(
                "taskId",         next.getId(),
                "stepInstanceId", next.getStepInstanceId(),
                "stepName",       next.getStepName()   != null ? next.getStepName()   : "",
                "navKey",         next.getNavKey()     != null ? next.getNavKey()     : "",
                "artifactId",     next.getArtifactId() != null ? next.getArtifactId() : entityId
        )));
    }

    @GetMapping("/tasks/user/{userId}/all")
    @Operation(summary = "Get all tasks for a user — complete task history across all statuses")
    public ResponseEntity<ApiResponse<List<TaskInstanceResponse>>> getAllTasks(@PathVariable Long userId) {
        log.debug("[WF-TASK] GET ALL | userId={}", userId);
        return ResponseEntity.ok(ApiResponse.success(service.getAllTasksForUser(userId)));
    }

    @GetMapping("/tasks/step/{stepInstanceId}")
    @Operation(summary = "Get all task instances in a step — all assignees and their statuses")
    public ResponseEntity<ApiResponse<List<TaskInstanceResponse>>> getTasksForStep(
            @PathVariable Long stepInstanceId) {
        log.debug("[WF-TASK] GET FOR STEP | stepInstanceId={}", stepInstanceId);
        return ResponseEntity.ok(ApiResponse.success(service.getTasksForStepInstance(stepInstanceId)));
    }

    @PostMapping("/tasks/assign")
    @Operation(summary = "Manually assign a task to a user — legacy endpoint for role resolution")
    public ResponseEntity<ApiResponse<TaskInstanceResponse>> assignTask(
            @RequestParam Long stepInstanceId,
            @RequestParam Long userId) {

        Long assignedBy = utilityService.getLoggedInDataContext().getId();
        log.info("[WF-TASK] ASSIGN | stepInstanceId={} | userId={} | assignedBy={}", stepInstanceId, userId, assignedBy);
        return ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.success(service.assignTaskToUser(stepInstanceId, userId, assignedBy)));
    }

    // ══════════════════════════════════════════════════════════════
    // UNASSIGNED RECOVERY — admin endpoint
    // ══════════════════════════════════════════════════════════════

    /**
     * POST /v1/workflow-instances/{instanceId}/steps/{stepInstanceId}/manual-assign?userId=X
     *
     * Pushes an ACTOR task to a specific user on a step that is:
     *   - UNASSIGNED: actorRoles had no members at activation time → step was stuck.
     *                 Step transitions to IN_PROGRESS on the first successful assignment.
     *   - IN_PROGRESS: adds an additional actor to an already-active step (additive).
     *
     * IDEMPOTENT: if the user already has a PENDING task on this step, no duplicate is created.
     *
     * This endpoint is the direct human fix for the "org side user missing" scenario:
     *   1. Vendor submits assessment
     *   2. Engine tries to create org reviewer task → no users hold the reviewer role
     *   3. Step goes UNASSIGNED, initiator notified
     *   4. Admin calls this endpoint with the correct userId → step unlocked immediately
     *
     * StepSlaMonitor also retries UNASSIGNED steps automatically every 15 minutes,
     * so if an admin just adds the user to the role (instead of using this endpoint),
     * the step will self-heal within 15 minutes with no further action required.
     *
     * Authorization: Platform Admin or Org Admin role required.
     */
    @PostMapping("/{instanceId}/steps/{stepInstanceId}/manual-assign")
    @Operation(summary = "Manually push an ACTOR task to a user on an UNASSIGNED or IN_PROGRESS step — admin only")
    public ResponseEntity<ApiResponse<TaskInstanceResponse>> manualAssign(
            @PathVariable Long instanceId,
            @PathVariable Long stepInstanceId,
            @RequestParam Long userId) {

        Long assignedBy = utilityService.getLoggedInDataContext().getId();
        log.info("[WF-MANUAL-ASSIGN] instanceId={} | stepInstanceId={} | userId={} | assignedBy={}",
                instanceId, stepInstanceId, userId, assignedBy);

        TaskInstanceResponse task = service.manualAssignTask(stepInstanceId, userId, assignedBy);

        log.info("[WF-MANUAL-ASSIGN] Done | taskId={} | stepInstanceId={} | userId={}",
                task.getId(), stepInstanceId, userId);

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(task));
    }

    // ══════════════════════════════════════════════════════════════
    // OPS VISIBILITY — admin health endpoints
    // ══════════════════════════════════════════════════════════════

    /**
     * GET /v1/admin/workflow/stuck-steps
     *
     * Returns all IN_PROGRESS step instances platform-wide that have zero PENDING or
     * APPROVED actor tasks. These are "stuck" — something prevented task creation and
     * the step can never advance on its own.
     *
     * Each result includes:
     *   - stepInstanceId, stepName, stepStatus, startedAt, slaDueAt
     *   - workflowInstanceId, entityType, entityId, tenantId, initiatedBy
     *   - fixEndpoint — the exact manual-assign URL to unlock the step
     *
     * UNASSIGNED steps also appear here via the stepStatus field, giving admins
     * a single query to see everything that is blocked across all modules and tenants.
     *
     * Authorization: Platform Admin role required.
     */
    @GetMapping("/admin/stuck-steps")
    @Operation(summary = "Admin: list all stuck workflow steps — IN_PROGRESS with zero actor tasks")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getStuckSteps() {
        log.info("[WF-ADMIN] GET STUCK STEPS | requestedBy={}",
                utilityService.getLoggedInDataContext().getId());
        return ResponseEntity.ok(ApiResponse.success(service.getStuckSteps()));
    }

    /**
     * POST /v1/workflow-instances/{id}/steps/{stepInstanceId}/re-evaluate
     *
     * Re-runs isStepApprovalSatisfied() on a specific step and advances the workflow
     * if the gate now passes. Useful for steps that got stuck because REJECTED sub-tasks
     * were inflating the total ACTOR count and preventing advancement (now fixed in
     * isStepApprovalSatisfied to exclude REJECTED tasks).
     *
     * Called by org admin from the workflow timeline when a step shows all actor tasks
     * complete but the step hasn't advanced.
     */
    @PostMapping("/{id}/steps/{stepInstanceId}/re-evaluate")
    @Operation(summary = "Admin: re-evaluate step approval gate and advance workflow if satisfied")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reEvaluateStep(
            @PathVariable Long id,
            @PathVariable Long stepInstanceId) {
        Long userId = utilityService.getLoggedInDataContext().getId();
        log.info("[WF-ADMIN] RE-EVALUATE | instanceId={} | stepInstanceId={} | by={}", id, stepInstanceId, userId);
        Map<String, Object> result = service.reEvaluateStep(id, stepInstanceId, userId);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * POST /v1/workflow-instances/{id}/tasks/{taskId}/reset
     *
     * Admin: reset a task back to IN_PROGRESS so the assignee can re-work it.
     *
     * Resets:
     *   1. task_instances.status       → IN_PROGRESS
     *   2. task_instances.acted_at     → NULL
     *   3. task_section_completions    → completed=false, completedAt=NULL for all rows
     *   4. Writes a workflow history audit entry
     *
     * Does NOT touch assessment domain state (answers, evaluations, section submits).
     * Pair with POST /v1/assessments/{id}/reset-reviewer-sections if the reviewer's
     * section submissions also need to be cleared so they can re-submit.
     *
     * Idempotent — safe to call on an already-IN_PROGRESS task.
     */
    @PostMapping("/{id}/tasks/{taskId}/reset")
    @org.springframework.transaction.annotation.Transactional
    @Operation(summary = "Admin: reset a task to IN_PROGRESS and re-arm its section gates")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resetTask(
            @PathVariable Long id,
            @PathVariable Long taskId,
            @RequestBody(required = false) java.util.Map<String, Object> body) {
        Long userId = utilityService.getLoggedInDataContext().getId();
        boolean rollbackDownstream = body != null
                && Boolean.TRUE.equals(body.get("rollbackDownstream"));
        log.info("[WF-ADMIN] RESET-TASK | instanceId={} | taskId={} | by={} | rollbackDownstream={}",
                id, taskId, userId, rollbackDownstream);
        Map<String, Object> result = service.resetTask(id, taskId, userId, rollbackDownstream);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // ══════════════════════════════════════════════════════════════
    // HISTORY / AUDIT TRAIL
    // ══════════════════════════════════════════════════════════════

    @GetMapping("/{id}/history")
    @Operation(summary = "Full chronological audit trail for a workflow instance")
    public ResponseEntity<ApiResponse<List<WorkflowHistoryResponse>>> getHistory(@PathVariable Long id) {
        log.debug("[WF-HISTORY] GET FULL | instanceId={}", id);
        return ResponseEntity.ok(ApiResponse.success(service.getFullHistory(id)));
    }

    @GetMapping("/{id}/history/step/{stepId}")
    @Operation(summary = "History for a specific step — shows all iterations if step was revisited")
    public ResponseEntity<ApiResponse<List<WorkflowHistoryResponse>>> getHistoryByStep(
            @PathVariable Long id, @PathVariable Long stepId) {
        log.debug("[WF-HISTORY] GET BY STEP | instanceId={} | stepId={}", id, stepId);
        return ResponseEntity.ok(ApiResponse.success(service.getHistoryByStep(id, stepId)));
    }

    @GetMapping("/history/user/{userId}")
    @Operation(summary = "All workflow history events performed by a user — for compliance reports")
    public ResponseEntity<ApiResponse<List<WorkflowHistoryResponse>>> getHistoryByUser(
            @PathVariable Long userId) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        log.debug("[WF-HISTORY] GET BY USER | userId={} | tenantId={}", userId, tenantId);
        return ResponseEntity.ok(ApiResponse.success(service.getHistoryByUser(userId, tenantId)));
    }

    /**
     * GET /v1/workflow-instances/tasks/access-context
     *
     * Resolves what the current user can do on a workflow page.
     * Returns AccessContext with: mode, canView, canEdit, canAct, reason, stepStatus, workflowStatus.
     */
    @GetMapping("/tasks/access-context")
    @Operation(summary = "Resolve access mode for current user on a workflow step page")
    public ResponseEntity<ApiResponse<AccessContext>> getAccessContext(
            @RequestParam Long stepInstanceId,
            @RequestParam(required = false) Long taskId) {

        com.kashi.grc.usermanagement.domain.User user = utilityService.getLoggedInDataContext();
        log.debug("[WF-ACCESS] GET | userId={} | stepInstanceId={} | taskId={}",
                user.getId(), stepInstanceId, taskId);

        AccessContext ctx = accessService.resolve(user, stepInstanceId, taskId);
        return ResponseEntity.ok(ApiResponse.success(ctx));
    }

    /**
     * GET /v1/workflow-instances/{id}/progress
     *
     * Per-step tracking summary — every blueprint step with runtime state,
     * assigned users by name, task history, SLA status, and duration.
     */
    @GetMapping("/{id}/progress")
    @Operation(summary = "Per-step progress tracking — assignments, tasks, SLA, duration for each step")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getProgress(@PathVariable Long id) {
        log.debug("[WF-PROGRESS] GET | instanceId={}", id);
        return ResponseEntity.ok(ApiResponse.success(service.getInstanceProgress(id)));
    }

    /**
     * GET /v1/workflow-instances/steps/{stepInstanceId}/eligible-users
     *
     * Returns users eligible to be ASSIGNED at this step.
     *
     * Two modes driven by step config (workflow_steps.assignable_side / assignable_role_id):
     *
     * Mode 1 — snapAssignableSide is set (section/control assignment pattern):
     *   The actor assigns users to do work WITHIN this step.
     *   e.g. Step 3: Lead Auditor picks Auditor IIs for section assignment.
     *   If snapAssignableRoleId is also set, further narrows to that specific role.
     *
     * Mode 2 — snapAssignableSide is null (ASSIGN-to-next-step pattern):
     *   Returns the NEXT step's actor roles — who will DO the next step.
     *   e.g. Step 2: GRC Manager picks a Lead Auditor who will own Step 3.
     *   Original behavior — unchanged.
     */
    @GetMapping("/steps/{stepInstanceId}/eligible-users")
    @Operation(summary = "Users eligible to be assigned at this step")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getEligibleUsers(
            @PathVariable Long stepInstanceId) {

        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();

        // 1. Load current step instance
        com.kashi.grc.workflow.domain.StepInstance si = service.getStepInstance(stepInstanceId);
        if (si == null) return ResponseEntity.notFound().build();

        // ── Mode 1: assignableSide configured on the step ─────────────────────
        if (si.getSnapAssignableSide() != null) {
            try {
                com.kashi.grc.usermanagement.domain.RoleSide roleSide =
                        com.kashi.grc.usermanagement.domain.RoleSide.valueOf(
                                si.getSnapAssignableSide().toUpperCase());
                List<Long> roleIds;
                if (si.getSnapAssignableRoleId() != null) {
                    roleIds = List.of(si.getSnapAssignableRoleId());
                } else {
                    roleIds = roleRepository.findAllForTenantBySide(tenantId, roleSide)
                            .stream().map(r -> r.getId()).toList();
                }
                if (roleIds.isEmpty()) return ResponseEntity.ok(ApiResponse.success(List.of()));
                List<Map<String, Object>> users = service.getUsersByRoles(roleIds, tenantId);
                log.info("[WF-ELIGIBLE] stepInstanceId={} assignableSide={} assignableRoleId={} → {} users",
                        stepInstanceId, si.getSnapAssignableSide(), si.getSnapAssignableRoleId(), users.size());
                return ResponseEntity.ok(ApiResponse.success(users));
            } catch (IllegalArgumentException e) {
                log.warn("[WF-ELIGIBLE] Invalid snapAssignableSide: {}", si.getSnapAssignableSide());
            }
        }

        // ── Mode 2: next step's actor roles (original behavior) ───────────────
        // 2. Find the NEXT step's actor roles via service
        // (service has access to instanceRepository and workflowStepRepository)
        var nextStepOpt = service.getNextStep(si);
        if (nextStepOpt.isEmpty()) {
            // No next step or snapStepOrder missing — fall back to current step
            return eligibleFromStep(si.getStepId(), tenantId, stepInstanceId);
        }

        Long nextStepId = nextStepOpt.get().getId();

        // 3. Get actor roles of the NEXT step — these are the people being assigned
        List<com.kashi.grc.workflow.domain.WorkflowStepRole> nextActorRoles =
                service.getStepActorRoles(nextStepId);

        if (nextActorRoles.isEmpty()) {
            log.debug("[WF-ELIGIBLE] Next step {} has no actor roles (ENTITY_OWNER or ASSIGNMENT_SCOPED)", nextStepId);
            return ResponseEntity.ok(ApiResponse.success(List.of()));
        }

        List<Long> roleIds = nextActorRoles.stream()
                .map(com.kashi.grc.workflow.domain.WorkflowStepRole::getRoleId)
                .distinct().toList();

        List<Map<String, Object>> users = service.getUsersByRoles(roleIds, tenantId);

        log.debug("[WF-ELIGIBLE] stepInstanceId={} currentOrder={} → nextStepId={} roleIds={} → {} users",
                stepInstanceId, si.getSnapStepOrder(), nextStepId, roleIds, users.size());

        return ResponseEntity.ok(ApiResponse.success(users));
    }

    /** Helper — get eligible users from the current step's actor roles (fallback). */
    private ResponseEntity<ApiResponse<List<Map<String, Object>>>> eligibleFromStep(
            Long stepId, Long tenantId, Long stepInstanceId) {
        var roles = service.getStepActorRoles(stepId);
        if (roles.isEmpty()) return ResponseEntity.ok(ApiResponse.success(List.of()));
        var roleIds = roles.stream().map(r -> r.getRoleId()).distinct().toList();
        return ResponseEntity.ok(ApiResponse.success(service.getUsersByRoles(roleIds, tenantId)));
    }
}