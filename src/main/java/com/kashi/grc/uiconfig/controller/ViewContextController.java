package com.kashi.grc.uiconfig.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.usermanagement.domain.User;
import com.kashi.grc.workflow.dto.response.AccessContext;
import com.kashi.grc.workflow.service.WorkflowAccessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * ViewContextController — resolves the three-layer access context.
 *
 * GET /v1/ui-config/view-context
 *
 * Layer 1: Base role permissions      (PermissionGrant table)
 * Layer 2: Step UI override           (StepInstance.snapUiOverrideJson)
 * Layer 3: User permission overrides  (UserPermissionOverride table)
 * Layer 4: SoD evaluation             (SodRule table, per workflowInstanceId)
 *
 * ── CALL PATTERNS ─────────────────────────────────────────────────────────────
 *
 * List page:
 *   ?entityType=RISK
 *   → role permissions only, no step context
 *
 * Detail page (no active task):
 *   ?entityType=RISK&entityId=42
 *   → role permissions + SoD if active workflow instance exists
 *
 * Task page (actor/assigner with active step):
 *   ?entityType=RISK&entityId=42&stepInstanceId=99&taskId=7
 *   → full resolution: role + step UI override + user overrides + SoD
 *   taskId identifies the specific task so the backend can confirm ownership
 *   and return canEdit=true for PENDING actor tasks (FILL/REVIEW steps).
 *   Without taskId, the step resolves as observer → read-only at this step.
 */
@Slf4j
@RestController
@RequestMapping("/v1/ui-config")
@Tag(name = "UI Config — View Context", description = "Resolves full access context for current user + entity + step")
@RequiredArgsConstructor
public class ViewContextController {

    private final WorkflowAccessService workflowAccessService;
    private final UtilityService        utilityService;

    @GetMapping("/view-context")
    @Operation(
            summary = "Resolve full access context",
            description = "Merges role permissions + step UI override + user overrides + SoD. " +
                    "Frontend calls once per page load, caches 30s via React Query."
    )
    public ResponseEntity<ApiResponse<AccessContext>> getViewContext(
            @RequestParam String entityType,
            @RequestParam(required = false) Long entityId,
            @RequestParam(required = false) Long stepInstanceId,
            @RequestParam(required = false) Long taskId) {

        User currentUser = utilityService.getLoggedInUserWithRolesAndPermissions();

        log.debug("[VIEW-CONTEXT] user={} entityType={} entityId={} stepInstanceId={} taskId={}",
                currentUser.getId(), entityType, entityId, stepInstanceId, taskId);

        AccessContext context = stepInstanceId != null
                ? workflowAccessService.resolve(currentUser, stepInstanceId, taskId)
                : workflowAccessService.resolveForModule(currentUser, entityType, entityId);

        return ResponseEntity.ok(ApiResponse.success(context));
    }
}