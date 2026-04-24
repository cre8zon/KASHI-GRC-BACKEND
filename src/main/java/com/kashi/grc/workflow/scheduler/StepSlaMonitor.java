package com.kashi.grc.workflow.scheduler;

import com.kashi.grc.notification.service.NotificationService;
import com.kashi.grc.workflow.domain.StepInstance;
import com.kashi.grc.workflow.domain.TaskInstance;
import com.kashi.grc.workflow.domain.WorkflowInstance;
import com.kashi.grc.workflow.domain.WorkflowInstanceHistory;
import com.kashi.grc.workflow.domain.WorkflowStep;
import com.kashi.grc.workflow.domain.WorkflowStepRole;
import com.kashi.grc.workflow.enums.StepStatus;
import com.kashi.grc.workflow.enums.TaskRole;
import com.kashi.grc.workflow.enums.TaskStatus;
import com.kashi.grc.workflow.repository.*;
import com.kashi.grc.common.repository.DbRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * StepSlaMonitor — scheduled job for two concerns:
 *
 * ── CONCERN 1: UNASSIGNED STEP RE-ATTEMPT ────────────────────────────────────
 *
 *   When assignTasksForStep() finds that actorRoles exist in the blueprint but
 *   ZERO users hold those roles in the tenant, the step is set to UNASSIGNED.
 *   The initiator is notified, but the step cannot proceed automatically.
 *
 *   This job re-runs role resolution for every UNASSIGNED step every 15 minutes.
 *   If a user has since been added to the required role, tasks are created
 *   and the step transitions to IN_PROGRESS. No manual intervention needed.
 *
 *   This covers the common case: "we forgot to add the org CISO to the reviewer
 *   role — we just fixed that — the assessment should now proceed automatically."
 *
 * ── CONCERN 2: SLA BREACH ESCALATION ─────────────────────────────────────────
 *
 *   Every 15 minutes this job also sweeps IN_PROGRESS step instances whose
 *   sla_due_at has passed. For each breached step:
 *     1. Status is updated to SLA_BREACHED (still actionable — not terminal)
 *     2. The workflow initiator and all ASSIGNER-task holders receive a notification
 *     3. A STEP_SLA_BREACHED history event is recorded for the audit trail
 *
 *   If the step is optional (snap_is_optional = true) and has been IN_PROGRESS
 *   with no actor tasks for longer than the SLA, it is auto-advanced (SKIPPED).
 *   Mandatory steps with no actor tasks are escalated but never auto-advanced.
 *
 * ── SCALABILITY ───────────────────────────────────────────────────────────────
 *   This job is module-agnostic. It operates entirely on workflow engine tables
 *   (step_instances, task_instances, workflow_instances) and never imports any
 *   module-specific class. The same job covers vendor assessments, audits,
 *   policies, and any future module that uses the workflow engine.
 *
 * ── IDEMPOTENCY ───────────────────────────────────────────────────────────────
 *   Both sweeps are idempotent:
 *     - UNASSIGNED re-attempt: tasks already created → loop body is a no-op (uids still empty)
 *     - SLA breach: steps already SLA_BREACHED are excluded from the query
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StepSlaMonitor {

    private final StepInstanceRepository        stepInstanceRepository;
    private final TaskInstanceRepository        taskInstanceRepository;
    private final WorkflowInstanceRepository    instanceRepository;
    private final WorkflowInstanceHistoryRepository historyRepository;
    private final WorkflowStepRepository        stepRepository;
    private final WorkflowStepRoleRepository    stepRoleRepository;
    private final WorkflowStepAssignerRoleRepository stepAssignerRoleRepository;
    private final DbRepository                  dbRepository;
    private final NotificationService           notificationService;

    // ── CONCERN 1: Re-attempt UNASSIGNED steps ────────────────────────────────

    /**
     * Every 15 minutes: find all UNASSIGNED steps and re-attempt role resolution.
     * If users now hold the required roles, create tasks and transition to IN_PROGRESS.
     *
     * fixedDelay: waits 15 min between the end of one run and the start of the next.
     * Does not fire concurrent runs.
     */
    @Scheduled(fixedDelay = 15 * 60 * 1000)   // 15 minutes
    @Transactional
    public void retryUnassignedSteps() {
        List<StepInstance> unassigned = stepInstanceRepository.findByStatus(StepStatus.UNASSIGNED);
        if (unassigned.isEmpty()) return;

        log.info("[SLA-MONITOR] UNASSIGNED sweep | {} step(s) to retry", unassigned.size());
        int resolved = 0;

        for (StepInstance si : unassigned) {
            try {
                WorkflowInstance wi = instanceRepository.findById(si.getWorkflowInstanceId()).orElse(null);
                if (wi == null) continue;

                // Load the blueprint step for role join-table lookup
                WorkflowStep step = si.getStepId() != null
                        ? stepRepository.findById(si.getStepId()).orElse(null) : null;
                if (step == null) {
                    log.warn("[SLA-MONITOR] UNASSIGNED step {} has no blueprint step reference", si.getId());
                    continue;
                }

                List<WorkflowStepRole> actorRoles = stepRoleRepository.findByStepId(step.getId());
                if (actorRoles.isEmpty()) continue;

                int tasksCreated = 0;
                for (WorkflowStepRole ar : actorRoles) {
                    List<Long> uids = dbRepository.findUserIdsByRoleAndTenant(ar.getRoleId(), wi.getTenantId());
                    for (Long uid : uids) {
                        // Skip if this user already has a PENDING task on this step
                        boolean alreadyHasTask = taskInstanceRepository
                                .existsByStepInstanceIdAndAssignedUserIdAndStatus(
                                        si.getId(), uid, TaskStatus.PENDING);
                        if (alreadyHasTask) continue;

                        TaskInstance task = TaskInstance.builder()
                                .stepInstanceId(si.getId())
                                .assignedUserId(uid)
                                .status(TaskStatus.PENDING)
                                .isAutoAssigned(true)
                                .taskRole(TaskRole.ACTOR)
                                .assignerSide(null)
                                .actorRoleName(null)
                                .build();
                        taskInstanceRepository.save(task);

                        notificationService.send(uid, "TASK_ASSIGNMENT",
                                "New task assigned: " + si.getSnapName(), "TASK", task.getId());
                        tasksCreated++;
                    }
                }

                if (tasksCreated > 0) {
                    si.setStatus(StepStatus.IN_PROGRESS);
                    stepInstanceRepository.save(si);

                    recordHistory(wi, si, "STEP_UNASSIGNED_RESOLVED",
                            StepStatus.UNASSIGNED.name(), StepStatus.IN_PROGRESS.name(),
                            null, "Role members found on retry — " + tasksCreated + " task(s) created");

                    log.info("[SLA-MONITOR] UNASSIGNED resolved | stepInstanceId={} | step='{}' | tasksCreated={}",
                            si.getId(), si.getSnapName(), tasksCreated);
                    resolved++;
                } else {
                    log.debug("[SLA-MONITOR] UNASSIGNED step {} still has no role members | step='{}'",
                            si.getId(), si.getSnapName());
                }
            } catch (Exception e) {
                log.error("[SLA-MONITOR] Error retrying UNASSIGNED step {} | {}", si.getId(), e.getMessage(), e);
            }
        }

        log.info("[SLA-MONITOR] UNASSIGNED sweep complete | resolved={}/{}", resolved, unassigned.size());
    }

    // ── CONCERN 2: SLA breach escalation ─────────────────────────────────────

    /**
     * Every 15 minutes: find IN_PROGRESS steps past their SLA deadline.
     * Mark as SLA_BREACHED, notify initiator and assigners, record history.
     *
     * Optional steps with zero actor tasks and no pending work are auto-skipped.
     */
    @Scheduled(fixedDelay = 15 * 60 * 1000)   // 15 minutes
    @Transactional
    public void checkSlaBreaches() {
        List<StepInstance> breached = stepInstanceRepository.findAllSlaBreached(LocalDateTime.now());
        if (breached.isEmpty()) return;

        log.info("[SLA-MONITOR] SLA sweep | {} step(s) overdue", breached.size());
        int escalated = 0;
        int autoSkipped = 0;

        for (StepInstance si : breached) {
            try {
                WorkflowInstance wi = instanceRepository.findById(si.getWorkflowInstanceId()).orElse(null);
                if (wi == null) continue;

                long pendingActorTasks = taskInstanceRepository
                        .countByStepInstanceIdAndTaskRoleAndStatus(si.getId(), TaskRole.ACTOR, TaskStatus.PENDING);
                long approvedActorTasks = taskInstanceRepository
                        .countByStepInstanceIdAndTaskRoleAndStatus(si.getId(), TaskRole.ACTOR, TaskStatus.APPROVED);
                long totalActorTasks = taskInstanceRepository
                        .countByStepInstanceIdAndTaskRole(si.getId(), TaskRole.ACTOR);

                boolean isOptional = Boolean.TRUE.equals(si.getSnapIsOptional());

                // Auto-skip: optional step, no actor tasks at all, past SLA
                if (isOptional && totalActorTasks == 0 && pendingActorTasks == 0) {
                    si.setStatus(StepStatus.SKIPPED);
                    si.setCompletedAt(LocalDateTime.now());
                    si.setRemarks("Auto-skipped by SLA monitor — optional step, no tasks, SLA exceeded");
                    stepInstanceRepository.save(si);

                    recordHistory(wi, si, "STEP_AUTO_SKIPPED",
                            StepStatus.IN_PROGRESS.name(), StepStatus.SKIPPED.name(),
                            null, "Optional step auto-skipped: no tasks created and SLA exceeded");

                    log.info("[SLA-MONITOR] Optional step auto-skipped | stepInstanceId={} | step='{}'",
                            si.getId(), si.getSnapName());
                    autoSkipped++;
                    continue;
                }

                // Mark SLA_BREACHED — step remains actionable
                si.setStatus(StepStatus.SLA_BREACHED);
                stepInstanceRepository.save(si);

                String slaInfo = String.format(
                        "SLA exceeded | slaDueAt=%s | pendingTasks=%d | approvedTasks=%d",
                        si.getSlaDueAt(), pendingActorTasks, approvedActorTasks);

                recordHistory(wi, si, "STEP_SLA_BREACHED",
                        StepStatus.IN_PROGRESS.name(), StepStatus.SLA_BREACHED.name(),
                        null, slaInfo);

                // Notify initiator
                notificationService.send(
                        wi.getInitiatedBy(),
                        "STEP_SLA_BREACHED",
                        "Step '" + si.getSnapName() + "' has exceeded its SLA deadline. Escalation required.",
                        "STEP_INSTANCE",
                        si.getId()
                );

                // Notify all ASSIGNER task holders (coordinators need to see the breach too)
                List<TaskInstance> assignerTasks = taskInstanceRepository.findByStepInstanceId(si.getId())
                        .stream()
                        .filter(t -> t.getTaskRole() == TaskRole.ASSIGNER
                                && t.getStatus() == TaskStatus.PENDING)
                        .toList();
                for (TaskInstance assignerTask : assignerTasks) {
                    notificationService.send(
                            assignerTask.getAssignedUserId(),
                            "STEP_SLA_BREACHED",
                            "Step '" + si.getSnapName() + "' has exceeded its SLA — please escalate.",
                            "STEP_INSTANCE",
                            si.getId()
                    );
                }

                log.warn("[SLA-MONITOR] SLA breached | stepInstanceId={} | step='{}' | slaDueAt={} | notified=initiator+{}assigners",
                        si.getId(), si.getSnapName(), si.getSlaDueAt(), assignerTasks.size());
                escalated++;

            } catch (Exception e) {
                log.error("[SLA-MONITOR] Error processing SLA breach for step {} | {}",
                        si.getId(), e.getMessage(), e);
            }
        }

        log.info("[SLA-MONITOR] SLA sweep complete | escalated={} | autoSkipped={}", escalated, autoSkipped);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private void recordHistory(WorkflowInstance wi, StepInstance si,
                               String eventType, String fromStatus, String toStatus,
                               Long performedBy, String remarks) {
        historyRepository.save(WorkflowInstanceHistory.builder()
                .tenantId(wi.getTenantId())
                .workflowInstanceId(wi.getId())
                .stepInstanceId(si.getId())
                .taskInstanceId(null)
                .eventType(eventType)
                .fromStatus(fromStatus)
                .toStatus(toStatus)
                .performedBy(performedBy)   // null = system action
                .performedAt(LocalDateTime.now())
                .remarks(remarks)
                .stepId(si.getStepId())
                .stepName(si.getSnapName())
                .stepOrder(si.getSnapStepOrder())
                .build());
    }
}