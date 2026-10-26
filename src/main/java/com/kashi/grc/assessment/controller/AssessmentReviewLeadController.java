package com.kashi.grc.assessment.controller;

import com.kashi.grc.assessment.domain.VendorAssessment;
import com.kashi.grc.assessment.domain.VendorAssessmentCycle;
import com.kashi.grc.assessment.repository.VendorAssessmentCycleRepository;
import com.kashi.grc.assessment.repository.VendorAssessmentRepository;
import com.kashi.grc.assessment.service.AssessmentAssignmentService;
import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.workflow.domain.StepInstance;
import com.kashi.grc.workflow.domain.TaskInstance;
import com.kashi.grc.workflow.domain.TaskSectionCompletion;
import com.kashi.grc.workflow.enums.TaskStatus;
import com.kashi.grc.workflow.event.TaskSectionEvent;
import com.kashi.grc.workflow.repository.StepInstanceRepository;
import com.kashi.grc.workflow.repository.TaskInstanceRepository;
import com.kashi.grc.workflow.repository.TaskSectionCompletionRepository;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Objects;

/**
 * Step 9 of the TPRM workflow: the Org Admin names the Org CISO who leads the
 * review.
 *
 *   POST /v1/assessments/{assessmentId}/assign-review-lead?taskId=&userId=
 *
 * ── WHY THIS EXISTS ──────────────────────────────────────────────────────────
 * The Review tab has called this endpoint since step 9 was designed, and the
 * reader of its result has existed too — VendorWorkflowActorResolver reads
 * vendor_assessments.review_lead_user_id to give steps 10 and 13 to that one
 * CISO. Nothing wrote it. Every choice failed with "No static resource", the
 * column stayed NULL on every assessment, and steps 10 and 13 fell through to
 * the whole CISO pool — the exact thing the resolver exists to prevent.
 *
 * ── WHAT IT CHECKS ───────────────────────────────────────────────────────────
 *   • the assessment is in the caller's organisation (404 otherwise);
 *   • the task is the caller's own, still open, and on THIS assessment's
 *     workflow — so it cannot complete someone else's step or another
 *     assessment's;
 *   • the task actually has the review-lead choice open (the
 *     ASSIGN_REVIEW_LEAD section of its step);
 *   • the chosen person is one the workflow allows — through
 *     AssessmentAssignmentService.eligibleUsersForTask, the SAME resolution the
 *     picker lists, so what is offered and what is accepted cannot drift.
 *
 * ── WHAT IT DOES ─────────────────────────────────────────────────────────────
 * Saves the lead, then marks the section done by its own completion event (read
 * from the task's section row, never hard-coded). When that was the step's last
 * required section the task auto-approves and the workflow moves to step 10,
 * whose actor resolution now finds the lead.
 *
 * Not @Transactional on purpose: the section listener runs in its own new
 * transaction and advances the workflow there. The lead must already be
 * committed when step 10's actors are resolved, so it is saved (and committed)
 * first, and the event is published after.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AssessmentReviewLeadController {

    private static final String SECTION_KEY = "ASSIGN_REVIEW_LEAD";

    private final VendorAssessmentRepository       assessmentRepository;
    private final VendorAssessmentCycleRepository  cycleRepository;
    private final TaskInstanceRepository           taskInstanceRepository;
    private final StepInstanceRepository           stepInstanceRepository;
    private final TaskSectionCompletionRepository  completionRepository;
    private final AssessmentAssignmentService      assignmentService;
    private final UtilityService                   utilityService;
    private final ApplicationEventPublisher        eventPublisher;

    @PostMapping("/v1/assessments/{assessmentId}/assign-review-lead")
    @Operation(summary = "Step 9: choose the Org CISO who leads this assessment's review")
    public ResponseEntity<ApiResponse<Map<String, Object>>> assignReviewLead(
            @PathVariable Long assessmentId,
            @RequestParam Long taskId,
            @RequestParam Long userId) {

        var caller   = utilityService.getLoggedInDataContext();
        Long tenantId = caller.getTenantId();

        VendorAssessment assessment = assessmentRepository.findByIdAndTenantId(assessmentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("VendorAssessment", assessmentId));

        // The caller's own, open task …
        TaskInstance task = taskInstanceRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("TaskInstance", taskId));
        if (!Objects.equals(task.getAssignedUserId(), caller.getId())
                || !(task.getStatus() == TaskStatus.PENDING || task.getStatus() == TaskStatus.IN_PROGRESS)) {
            throw new BusinessException("TASK_NOT_YOURS",
                    "This step is not waiting on you. Open it from your own task in My Tasks.",
                    HttpStatus.FORBIDDEN);
        }
        // … on THIS assessment's workflow.
        StepInstance si = stepInstanceRepository.findById(task.getStepInstanceId())
                .orElseThrow(() -> new ResourceNotFoundException("StepInstance", task.getStepInstanceId()));
        Long assessmentWorkflow = assessment.getCycleId() == null ? null
                : cycleRepository.findById(assessment.getCycleId())
                  .map(VendorAssessmentCycle::getWorkflowInstanceId).orElse(null);
        if (assessmentWorkflow == null || !assessmentWorkflow.equals(si.getWorkflowInstanceId())) {
            throw new BusinessException("TASK_NOT_FOR_ASSESSMENT",
                    "That task belongs to a different assessment.", HttpStatus.UNPROCESSABLE_ENTITY);
        }

        // The choice is actually open on this task.
        TaskSectionCompletion section = completionRepository
                .findByTaskInstanceIdAndSnapSectionKey(taskId, SECTION_KEY)
                .orElseThrow(() -> new BusinessException("NO_REVIEW_LEAD_CHOICE",
                        "This step has no review lead to choose.", HttpStatus.UNPROCESSABLE_ENTITY));
        if (section.isCompleted()) {
            throw new BusinessException("REVIEW_LEAD_ALREADY_CHOSEN",
                    "The review lead has already been chosen for this assessment.", HttpStatus.CONFLICT);
        }

        // The person is one the workflow allows — the picker's own list.
        boolean eligible = assignmentService.eligibleUsersForTask(taskId).stream()
                .map(u -> u.get("id") != null ? u.get("id") : u.get("userId"))
                .filter(Objects::nonNull)
                .anyMatch(id -> String.valueOf(id).equals(String.valueOf(userId)));
        if (!eligible) {
            throw new BusinessException("INVALID_REVIEW_LEAD",
                    "That person can't lead this review. Choose someone from the list — "
                            + "it shows who holds the next step's role.", HttpStatus.FORBIDDEN);
        }

        // Committed before the workflow moves (see class note).
        assessment.setReviewLeadUserId(userId);
        assessmentRepository.save(assessment);

        eventPublisher.publishEvent(TaskSectionEvent.sectionDone(
                section.getSnapCompletionEvent(), taskId, caller.getId(), "VENDOR_ASSESSMENT", assessmentId));

        log.info("[REVIEW-LEAD] assessment={} lead={} task={} by={}", assessmentId, userId, taskId, caller.getId());
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "assessmentId",     assessmentId,
                "reviewLeadUserId", userId)));
    }
}