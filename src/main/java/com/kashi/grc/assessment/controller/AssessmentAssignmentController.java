package com.kashi.grc.assessment.controller;

import com.kashi.grc.assessment.service.AssessmentAssignmentService;
import com.kashi.grc.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * One endpoint, and it exists only because of a missing id.
 *
 * ── WHAT WENT AWAY, AND WHY ───────────────────────────────────────────────
 * This class used to expose three endpoints:
 *
 *   GET /v1/assessments/{id}/assignable-users
 *   GET /v1/assessments/{id}/assignment-constraint
 *   GET /v1/workflows/tasks/{taskId}/assignable-users
 *
 * The first two are gone. They resolved the answer a different way from the
 * enforcement behind them — assignable_side and assignable_role_id only,
 * missing the mode where a step declares neither and eligible-users derives
 * who is assignable from the NEXT step's actor roles. On step 9 of the TPRM
 * workflow that is precisely the case, so those endpoints listed every user
 * while assertAssignable rejected the pick. Two answers to one question, and
 * the wrong one was the one people clicked.
 *
 * The platform already has the right endpoint:
 *
 *   GET /v1/workflow-instances/steps/{stepInstanceId}/eligible-users
 *
 * Anything holding a stepInstanceId calls that. components/vendor does, and
 * VendorAssessmentFillPage and VendorAssessmentAssignPage now do too.
 *
 * ── SO WHY IS THERE STILL ONE HERE ────────────────────────────────────────
 * AssessmentReviewPage's assignment panels hold a taskId and no step id. The
 * endpoint below is a lookup — task → step instance → the same resolution —
 * and returns the identical shape. It adds a hop, not a second answer.
 *
 * If that page ever carries a stepInstanceId, this file can go entirely.
 */
@RestController
@Tag(name = "Assessment Assignment", description = "Assignable users for the step behind a task")
@RequiredArgsConstructor
public class AssessmentAssignmentController {

    private final AssessmentAssignmentService assignmentService;

    /**
     * Assignable users for the step behind this task.
     *
     * Same response shape as the platform's eligible-users endpoint: a plain
     * list of user maps, not a paginated envelope. Callers can swap between the
     * two without reshaping, which is the point.
     *
     * No assessment-level access gate: the resolution refuses a task outside
     * the caller's tenant, and a user staging an assignment holds the task in
     * their own inbox. Gating on an assessment would also require one to exist,
     * which is the situation this endpoint is here to handle.
     */
    @GetMapping("/v1/workflows/tasks/{taskId}/assignable-users")
    @Operation(summary = "Users assignable to the step behind this task")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> assignableUsersForTask(
            @PathVariable Long taskId) {
        return ResponseEntity.ok(ApiResponse.success(
                assignmentService.eligibleUsersForTask(taskId)));
    }
}