package com.kashi.grc.assessment.controller;

import com.kashi.grc.assessment.service.AssessmentIssueEscalationService;
import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.util.UtilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Escalate a vendor-assessment remediation to an Issue.
 *
 * One endpoint, deliberately in its own class rather than appended to
 * ReviewController's thousand-plus lines.
 *
 * ── THE PERMISSION, NOT A ROLE NAME ───────────────────────────────────────
 * The audit equivalent has no gate at all — AuditFindingController's
 * escalateToIssue carries no @PreAuthorize, and the frontend's EscalateButton
 * defaults `canEscalate = true`, so today anyone who can see a finding can turn
 * it into an issue. That is the pattern you asked me to stop copying.
 *
 * `assessment.remediation.escalate` is the gate here. It is a permission code,
 * so a tenant that renames ORG_CISO, or adds a "Vendor Risk Lead" role next
 * year, grants the code and the button appears — no deployment, no code change.
 * The same code is what seed 65 puts on the ui_actions row, so the button and
 * the endpoint are gated by one string in one place.
 *
 * hasAuthority rather than hasRole: Spring's hasRole prepends ROLE_, and this
 * platform's authorities are the raw permission codes.
 */
@RestController
@RequestMapping("/v1/assessments")
@Tag(name = "Assessment Issue Escalation",
        description = "Turn a vendor remediation into a tracked Issue")
@RequiredArgsConstructor
public class AssessmentIssueController {

    private final AssessmentIssueEscalationService escalationService;
    private final UtilityService utilityService;

    /**
     * POST /v1/assessments/{assessmentId}/action-items/{actionItemId}/escalate-to-issue
     *
     * Body is optional. The only key read is workflowId, and passing it
     * overrides the name-ordered resolution in the service — useful while you
     * are still deciding which ISSUE blueprint the vendor flow should run, and
     * the honest way to pin it per tenant until that is settled.
     *
     * 201, not 200. A new resource exists at the end of this call, and the
     * audit endpoint already answers 201 for the same reason; answering 200
     * here would make two escalation endpoints disagree about the same act.
     */
    @PostMapping("/{assessmentId}/action-items/{actionItemId}/escalate-to-issue")
    @PreAuthorize("hasAuthority('assessment.remediation.escalate')")
    @Operation(summary = "Escalate a remediation request to an Issue and start its workflow")
    public ResponseEntity<ApiResponse<Map<String, Object>>> escalateToIssue(
            @PathVariable Long assessmentId,
            @PathVariable Long actionItemId,
            @RequestBody(required = false) Map<String, Object> body) {

        Long userId   = utilityService.getLoggedInDataContext().getId();
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();

        Long workflowId = null;
        if (body != null && body.get("workflowId") != null) {
            workflowId = Long.valueOf(String.valueOf(body.get("workflowId")));
        }

        Map<String, Object> result =
                escalationService.escalate(assessmentId, actionItemId, workflowId, userId, tenantId);

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(result));
    }
}