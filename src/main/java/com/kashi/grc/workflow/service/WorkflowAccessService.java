package com.kashi.grc.workflow.service;

import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.usermanagement.domain.User;
import com.kashi.grc.workflow.domain.*;
import com.kashi.grc.workflow.dto.response.AccessContext;
import com.kashi.grc.workflow.enums.*;
import com.kashi.grc.workflow.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * WorkflowAccessService — resolves what a user can do on a workflow page.
 *
 * ── WHY THIS EXISTS ───────────────────────────────────────────────────────────
 * Previously every workflow page had its own access guard logic in a useEffect:
 *   - Check if the user has a task for this step
 *   - If not, redirect to inbox
 * This was copy-pasted across pages, only enforced the "has task" check,
 * and had no concept of observer access or completed/read-only mode.
 *
 * This service centralises all access decisions in one place:
 *   1. Active task owner    → EDIT mode (full form + actions)
 *   2. Observer role holder → OBSERVER mode (read-only, no actions)
 *   3. Step/workflow done   → COMPLETED mode (read-only, historical view)
 *   4. No relationship      → DENIED (frontend redirects to inbox)
 *
 * ── HOW TO USE ────────────────────────────────────────────────────────────────
 * Frontend calls GET /v1/workflow-instances/tasks/access-context on page mount.
 * The returned AccessContext drives the entire page render:
 *   - canView=false       → redirect
 *   - mode=OBSERVER       → disable all inputs, hide action buttons, show banner
 *   - mode=COMPLETED      → show historical view with completion metadata
 *   - mode=EDIT           → full interactive form
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowAccessService {

    private final StepInstanceRepository              stepInstanceRepository;
    private final WorkflowInstanceRepository          instanceRepository;
    private final TaskInstanceRepository              taskInstanceRepository;
    private final WorkflowStepObserverRoleRepository  observerRoleRepository;
    private final WorkflowStepRepository              stepRepository;

    /**
     * Resolves the access context for a user on a specific step instance.
     *
     * @param user            The authenticated user (loaded by UtilityService)
     * @param stepInstanceId  The step instance to check access for
     * @param taskId          The task the user claims to own (null = observer/history check)
     */
    public AccessContext resolve(User user, Long stepInstanceId, Long taskId) {

        StepInstance si = stepInstanceRepository.findById(stepInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("StepInstance", stepInstanceId));

        WorkflowInstance wi = instanceRepository.findById(si.getWorkflowInstanceId())
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowInstance",
                        si.getWorkflowInstanceId()));

        String stepStatus     = si.getStatus().name();
        String workflowStatus = wi.getStatus().name();

        log.debug("[ACCESS] Resolving | userId={} | stepInstanceId={} | taskId={} | stepStatus={} | wfStatus={}",
                user.getId(), stepInstanceId, taskId, stepStatus, workflowStatus);

        // ── 1. Workflow terminal → always completed/read-only ─────────────────
        if (wi.getStatus() == WorkflowStatus.COMPLETED
                || wi.getStatus() == WorkflowStatus.CANCELLED
                || wi.getStatus() == WorkflowStatus.REJECTED) {
            log.debug("[ACCESS] COMPLETED — workflow is {}", wi.getStatus());
            return AccessContext.completed(
                    "Workflow is " + wi.getStatus().name().toLowerCase(),
                    stepStatus, workflowStatus);
        }

        // ── 2. Step terminal → completed/read-only ────────────────────────────
        if (si.getStatus() == StepStatus.APPROVED || si.getStatus() == StepStatus.REJECTED
                || si.getStatus() == StepStatus.REASSIGNED) {
            String completedAt = si.getCompletedAt() != null
                    ? si.getCompletedAt().toLocalDate().toString() : "unknown date";
            log.debug("[ACCESS] COMPLETED — step is {}", si.getStatus());
            return AccessContext.completed(
                    "Step " + si.getStatus().name().toLowerCase() + " on " + completedAt,
                    stepStatus, workflowStatus);
        }

        // ── 3. Active PENDING task owned by this user → EDIT ─────────────────
        if (taskId != null) {
            TaskInstance task = taskInstanceRepository.findById(taskId).orElse(null);
            if (task != null
                    && task.getAssignedUserId().equals(user.getId())
                    && task.getStepInstanceId().equals(stepInstanceId)
                    && (task.getStatus() == TaskStatus.PENDING
                    || task.getStatus() == TaskStatus.IN_PROGRESS)) {
                log.debug("[ACCESS] EDIT — user owns active task | taskId={} | role={} | status={}",
                        taskId, task.getTaskRole(), task.getStatus());
                return AccessContext.edit(task.getTaskRole(), stepStatus, workflowStatus);
            }

            // DELEGATED task — user delegated but retains accountability.
            // They can view in read-only but cannot act again.
            if (task != null
                    && task.getAssignedUserId().equals(user.getId())
                    && task.getStepInstanceId().equals(stepInstanceId)
                    && task.getStatus() == TaskStatus.DELEGATED) {
                log.debug("[ACCESS] OBSERVER — user delegated task | taskId={}", taskId);
                return AccessContext.observer(
                        "You delegated this task — monitoring only",
                        stepStatus, workflowStatus);
            }
        }

        // ── 4. Observer role on this step → OBSERVER ──────────────────────────
        // Load the user's role IDs and compare against step's observer role IDs.
        // Uses the blueprint step (si.getStepId()) — observer roles are global config,
        // not snapshotted, intentionally live (same as actorRoles in assignTasksForStep).
        if (si.getStepId() != null) {
            Set<Long> observerRoleIds = observerRoleRepository.findByStepId(si.getStepId())
                    .stream()
                    .map(WorkflowStepObserverRole::getRoleId)
                    .collect(Collectors.toSet());

            if (!observerRoleIds.isEmpty()) {
                Set<Long> userRoleIds = user.getRoles().stream()
                        .map(r -> r.getId())
                        .collect(Collectors.toSet());

                boolean isObserver = userRoleIds.stream().anyMatch(observerRoleIds::contains);
                if (isObserver) {
                    log.debug("[ACCESS] OBSERVER — user holds an observer role on this step");
                    return AccessContext.observer(
                            "You have read-only observer access to this step",
                            stepStatus, workflowStatus);
                }
            }
        }

        // ── 5. No relationship → DENIED ───────────────────────────────────────
        log.debug("[ACCESS] DENIED — no task, no observer role | userId={} | stepInstanceId={}",
                user.getId(), stepInstanceId);
        return AccessContext.denied();
    }
}