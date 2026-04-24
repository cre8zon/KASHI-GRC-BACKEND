package com.kashi.grc.issue.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.dto.PaginatedResponse;
import com.kashi.grc.common.util.UtilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * IssueController — Issue Management module stub.
 *
 * The navigation sidebar has an "Issues" entry that calls GET /v1/issues.
 * Without this controller Spring treats the path as a static resource and
 * throws NoResourceFoundException, polluting the logs every few seconds.
 *
 * This stub returns empty lists so the page loads cleanly. Full implementation
 * (issue creation, assignment, status tracking, SLA) to be built on top of this.
 */
@Slf4j
@RestController
@RequestMapping("/v1/issues")
@Tag(name = "Issue Management", description = "Issue tracking and remediation")
@RequiredArgsConstructor
public class IssueController {

    private final UtilityService utilityService;

    /**
     * GET /v1/issues
     * Returns a paginated list of issues for the current tenant.
     * Stub: returns empty list until the Issue domain is fully implemented.
     */
    @GetMapping
    @Operation(summary = "List issues for the current tenant")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listIssues(
            @RequestParam Map<String, String> allParams) {

        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        log.debug("[ISSUES] LIST | tenantId={}", tenantId);

        // Stub: return empty list with pagination metadata
        return ResponseEntity.ok(ApiResponse.success(List.of()));
    }

    /**
     * GET /v1/issues/{id}
     * Returns a single issue by ID.
     * Stub: returns not-found until fully implemented.
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get issue by ID")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getIssue(@PathVariable Long id) {
        log.debug("[ISSUES] GET | id={}", id);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "id",      id,
                "status",  "NOT_IMPLEMENTED",
                "message", "Issue management module coming soon"
        )));
    }

    /**
     * GET /v1/issues/my/count
     * Returns count of open issues assigned to the current user.
     * Used by sidebar badge. Returns 0 until module is implemented.
     */
    @GetMapping("/my/count")
    @Operation(summary = "Count of open issues assigned to me — for sidebar badge")
    public ResponseEntity<ApiResponse<Long>> getMyIssueCount() {
        return ResponseEntity.ok(ApiResponse.success(0L));
    }
}