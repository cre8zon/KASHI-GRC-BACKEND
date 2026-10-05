package com.kashi.grc.assessment.service;

import com.kashi.grc.assessment.domain.VendorAssessment;
import com.kashi.grc.assessment.domain.VendorAssessmentCycle;
import com.kashi.grc.assessment.repository.VendorAssessmentCycleRepository;
import com.kashi.grc.assessment.repository.VendorAssessmentRepository;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.usermanagement.domain.Role;
import com.kashi.grc.usermanagement.domain.RoleSide;
import com.kashi.grc.usermanagement.repository.RoleRepository;
import com.kashi.grc.usermanagement.repository.UserRepository;
import com.kashi.grc.workflow.domain.StepInstance;
import com.kashi.grc.workflow.domain.WorkflowInstance;
import com.kashi.grc.workflow.domain.WorkflowStepRole;
import com.kashi.grc.workflow.enums.StepAction;
import com.kashi.grc.workflow.enums.StepStatus;
import com.kashi.grc.workflow.repository.StepInstanceRepository;
import com.kashi.grc.workflow.repository.TaskInstanceRepository;
import com.kashi.grc.workflow.repository.WorkflowInstanceRepository;
import com.kashi.grc.workflow.service.WorkflowEngineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Who may be assigned to a step of an assessment — asked once, answered once.
 *
 * ── WHY THIS FILE SHRANK ──────────────────────────────────────────────────
 * It used to answer the question twice, and the two answers disagreed.
 *
 *   the picker      → constraintFor()  → assignableSide/RoleId → listUsers()
 *   the enforcement → eligibleUserIds() → the same plus mode 2
 *
 * Mode 2 is the case where a step declares neither column and eligible-users
 * derives the answer from the NEXT step's actor roles. Step 9, "Org Admin
 * Assigns Review to Org CISO", is exactly that. The picker returned
 * "unconstrained" there and listed EVERYBODY; the assertion then rejected the
 * pick. A dropdown that offers people the server refuses is worse than the
 * unfiltered dropdown it replaced, because the failure arrives after the user
 * has committed to a choice.
 *
 * So the picker methods are gone. There is now one resolution — eligible(),
 * mirroring WorkflowInstanceController.getEligibleUsers — and two ways in:
 *
 *   eligibleUsersForTask(taskId)   the list, for a caller holding only a task
 *   assertAssignable(...)          the rule, for the write path
 *
 * A caller that already knows its stepInstanceId does not come here at all —
 * it calls /v1/workflow-instances/steps/{id}/eligible-users directly, which is
 * the platform's own endpoint and the one components/vendor uses. This class
 * exists only because AssessmentReviewPage holds a taskId and no step id, and
 * because the write path needs the same answer server-side.
 *
 * ── WHAT IS NOT REIMPLEMENTED HERE ────────────────────────────────────────
 * Mode 0 (?side=X) belongs to the endpoint: it answers "who can do the AUDITOR
 * side of this workflow, wherever it currently sits", which is a question about
 * a screen with two pickers, not about enforcing one assignment. Callers that
 * need it use the platform endpoint.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssessmentAssignmentService {

    private final VendorAssessmentRepository      assessmentRepository;
    private final VendorAssessmentCycleRepository cycleRepository;
    private final WorkflowInstanceRepository      workflowInstanceRepository;
    private final StepInstanceRepository          stepInstanceRepository;
    private final TaskInstanceRepository          taskInstanceRepository;
    private final RoleRepository                  roleRepository;
    private final UserRepository                  userRepository;
    private final WorkflowEngineService           workflowEngineService;
    private final UtilityService                  utilityService;

    // ═════════════════════════════════════════════════════════════════════════
    // THE LIST
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Assignable users for the step behind one task.
     *
     * Returns the same shape as /v1/workflow-instances/steps/{id}/eligible-users
     * — a plain list of user maps keyed id / firstName / lastName / email /
     * fullName — so a frontend can swap between the two without reshaping.
     *
     * An EMPTY list and an UNCONSTRAINED step are different answers, and this
     * method flattens them to the same thing on purpose: when the workflow
     * declares nothing, every user of the tenant is assignable, and listing all
     * of them is the honest rendering of that. The assertion below agrees —
     * it allows anything in the same case.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> eligibleUsersForTask(Long taskId) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        StepInstance si = stepInstanceForTask(taskId, tenantId);

        List<Map<String, Object>> users = eligible(si, tenantId);
        if (users == null) {
            log.debug("[ASSIGNABLE] task={} step={} declares no constraint — listing all tenant users",
                    taskId, si.getId());
            return allTenantUsers(tenantId);
        }
        log.debug("[ASSIGNABLE] task={} step={} → {} eligible", taskId, si.getId(), users.size());
        return users;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // THE RULE
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Refuses an assignment the step does not permit.
     *
     * Resolves through the same eligible() the list uses, so what the dropdown
     * offers and what the server accepts cannot drift apart.
     *
     * Fails open where the workflow declares nothing — inventing a constraint
     * would break assignment on data that predates these columns, silently, at
     * the moment somebody tries to work. Tenant is the exception: always
     * checked, declared or not.
     */
    @Transactional(readOnly = true)
    public void assertAssignable(Long assigneeId, Long assessmentId, StepAction action) {
        if (assigneeId == null) {
            throw new BusinessException("INVALID_OPERATION", "An assignee is required.");
        }
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();

        // Tenant first and unconditionally, before any workflow lookup.
        userRepository.findByIdAndTenantId(assigneeId, tenantId)
                .orElseThrow(() -> new BusinessException("INVALID_ASSIGNEE",
                        "That user is not available for assignment.", HttpStatus.FORBIDDEN));

        StepInstance si = stepInstanceForAction(assessmentId, action);
        if (si == null) {
            log.debug("[ASSIGNABLE] No step instance for action={} on assessment={} — allowing",
                    action, assessmentId);
            return;
        }

        List<Map<String, Object>> users = eligible(si, tenantId);
        if (users == null) {
            log.debug("[ASSIGNABLE] Step {} declares no constraint — allowing", si.getId());
            return;
        }
        if (!idsOf(users).contains(assigneeId)) {
            log.warn("[ASSIGNABLE] Rejected | assignee={} assessment={} action={} step={} eligible={}",
                    assigneeId, assessmentId, action, si.getId(), users.size());
            throw new BusinessException("INVALID_ASSIGNEE",
                    "That user cannot be assigned to this step. The workflow limits it to "
                            + "a specific side or role.", HttpStatus.FORBIDDEN);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // THE ONE RESOLUTION
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Who the workflow permits on this step.
     *
     * Mirrors WorkflowInstanceController.getEligibleUsers modes 1 and 2, in the
     * same order and from the same sources.
     *
     * Returns NULL — not an empty list — when the step declares nothing
     * resolvable. "Anyone" and "no one" are opposite answers, and an empty list
     * would forbid every assignment on a step whose assignable_side simply has
     * not been seeded.
     */
    private List<Map<String, Object>> eligible(StepInstance si, Long tenantId) {
        // Mode 1 — the step names who may work WITHIN it.
        if (si.getSnapAssignableSide() != null) {
            try {
                RoleSide side = RoleSide.valueOf(si.getSnapAssignableSide().toUpperCase());
                List<Long> roleIds = (si.getSnapAssignableRoleId() != null)
                        ? List.of(si.getSnapAssignableRoleId())
                        : roleRepository.findAllForTenantBySide(tenantId, side)
                          .stream().map(Role::getId).toList();
                if (roleIds.isEmpty()) return List.of();
                // SCOPED. getUsersByRoles narrows by tenant and role and nothing
                // else, so this check accepted any vendor's holder of the role:
                // the picker was fixed, the validator behind it was not, and a
                // hand-made request could still land another vendor's user on
                // this assessment's question. Worse than the disclosure — it
                // hands them a task carrying someone else's assessment.
                return workflowEngineService.getUsersByRolesScoped(
                        roleIds, tenantId, si, si.getSnapAssignableSide());
            } catch (IllegalArgumentException e) {
                // A side string that is not a RoleSide. The endpoint logs and
                // falls through to mode 2; do the same, so bad seed data
                // degrades identically in both.
                log.warn("[ASSIGNABLE] Invalid snapAssignableSide '{}' on stepInstance={}",
                        si.getSnapAssignableSide(), si.getId());
            }
        }

        // Mode 2 — the NEXT step's actor roles: who will DO the next step.
        var next = workflowEngineService.getNextStep(si);
        if (next.isEmpty()) return null;

        List<Long> roleIds = workflowEngineService.getStepActorRoles(next.get().getId())
                .stream().map(WorkflowStepRole::getRoleId).distinct().toList();
        if (roleIds.isEmpty()) {
            // The next step is ASSIGNMENT_SCOPED or ENTITY_OWNER and has no
            // actor roles of its own, so mode 2 cannot answer. Not declared,
            // so not enforced — the seed for that step's assignable_side is
            // what turns this into a real constraint.
            return null;
        }
        // Same scope on the mode 2 path. Leaving one unscoped means the hole
        // survives on whichever step happens to fall through to it.
        return workflowEngineService.getUsersByRolesScoped(roleIds, tenantId, si, null);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // LOOKUPS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * The step instance behind a task, tenant-checked.
     *
     * Through the step's workflow instance, because TaskInstance carries no
     * tenant of its own. Not-found rather than forbidden on a mismatch: a 403
     * would confirm the task id is real in some other tenant.
     */
    private StepInstance stepInstanceForTask(Long taskId, Long tenantId) {
        var task = taskInstanceRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("TaskInstance", taskId));

        StepInstance si = stepInstanceRepository.findById(task.getStepInstanceId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "StepInstance", task.getStepInstanceId()));

        WorkflowInstance wi = workflowInstanceRepository.findById(si.getWorkflowInstanceId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "WorkflowInstance", si.getWorkflowInstanceId()));
        if (!tenantId.equals(wi.getTenantId())) {
            throw new ResourceNotFoundException("TaskInstance", taskId);
        }
        return si;
    }

    /** The step instance this action refers to on this assessment, or null. */
    private StepInstance stepInstanceForAction(Long assessmentId, StepAction action) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        VendorAssessment assessment = assessmentRepository
                .findByIdAndTenantId(assessmentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("VendorAssessment", assessmentId));

        VendorAssessmentCycle cycle = cycleRepository.findById(assessment.getCycleId()).orElse(null);
        if (cycle == null || cycle.getWorkflowInstanceId() == null) return null;

        List<StepInstance> all = stepInstanceRepository
                .findByWorkflowInstanceIdOrderByCreatedAtAsc(cycle.getWorkflowInstanceId()).stream()
                .filter(s -> s.getSnapStepAction() == action)
                .toList();
        if (all.isEmpty()) return null;

        // A running step is the right answer while it runs. Once finished — a
        // contributor reassigned after FILL closed — the most recent instance
        // of that action still describes what the step demanded, which beats
        // falling through to a blueprint that may since have been edited.
        return all.stream()
                .filter(s -> s.getStatus() == StepStatus.IN_PROGRESS
                        || s.getStatus() == StepStatus.AWAITING_ASSIGNMENT
                        || s.getStatus() == StepStatus.UNASSIGNED)
                .reduce((a, b) -> b)
                .orElseGet(() -> all.get(all.size() - 1));
    }

    /**
     * Every user of the tenant, in the eligible-users map shape.
     *
     * Used only where the workflow declares no constraint, so that the list and
     * the rule agree: both say "anyone". Built from the tenant's own roles
     * rather than a user query, so it cannot return a user with no role at all.
     */
    private List<Map<String, Object>> allTenantUsers(Long tenantId) {
        List<Long> roleIds = new java.util.ArrayList<>();
        for (RoleSide side : RoleSide.values()) {
            roleRepository.findAllForTenantBySide(tenantId, side)
                    .forEach(r -> roleIds.add(r.getId()));
        }
        if (roleIds.isEmpty()) return List.of();
        return workflowEngineService.getUsersByRoles(roleIds.stream().distinct().toList(), tenantId);
    }

    /** getUsersByRoles returns maps keyed "id" — see WorkflowEngineService. */
    private Set<Long> idsOf(List<Map<String, Object>> users) {
        Set<Long> out = new HashSet<>();
        for (Map<String, Object> u : users) {
            Object id = u.get("id");
            if (id instanceof Number n) out.add(n.longValue());
        }
        return out;
    }
}