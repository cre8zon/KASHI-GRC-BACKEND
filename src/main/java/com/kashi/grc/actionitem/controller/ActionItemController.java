package com.kashi.grc.actionitem.controller;

import com.kashi.grc.actionitem.domain.ActionItem;
import com.kashi.grc.actionitem.dto.ActionItemRequest;
import com.kashi.grc.actionitem.dto.ActionItemResponse;
import com.kashi.grc.actionitem.dto.ActionItemStatusUpdate;
import com.kashi.grc.actionitem.service.ActionItemService;
import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.usermanagement.domain.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@Tag(name = "Action Items", description = "Cross-module obligation tracking")
@RequiredArgsConstructor
public class ActionItemController {

    private final ActionItemService actionItemService;
    private final UtilityService    utilityService;

    /**
     * GET /v1/action-items/my
     * My open action items — for Action Items page and badge count.
     */
    @GetMapping("/v1/action-items/my")
    @Operation(summary = "Get my open action items")
    public ResponseEntity<ApiResponse<List<ActionItemResponse>>> getMyItems() {
        User user = utilityService.getLoggedInDataContext();
        List<String> roles = resolveRoleNames(user);
        List<ActionItemResponse> items = actionItemService.getMyOpenItems(
            user.getId(), roles, user.getTenantId());
        return ResponseEntity.ok(ApiResponse.success(items));
    }

    /**
     * GET /v1/action-items/my/count
     * Badge count for sidebar — real-time via WS, this is the initial load.
     */
    @GetMapping("/v1/action-items/my/count")
    @Operation(summary = "Count of my open action items")
    public ResponseEntity<ApiResponse<Long>> getMyCount() {
        User user = utilityService.getLoggedInDataContext();
        long count = actionItemService.countOpenForUser(user.getId(), user.getTenantId());
        return ResponseEntity.ok(ApiResponse.success(count));
    }

    /**
     * GET /v1/action-items?entityType=ASSESSMENT&entityId=23
     * All action items for an entity — oversight view for CISO/VRM/coordinator.
     */
    @GetMapping("/v1/action-items")
    @Operation(summary = "Get action items for an entity")
    public ResponseEntity<ApiResponse<List<ActionItemResponse>>> getForEntity(
            @RequestParam ActionItem.EntityType entityType,
            @RequestParam Long entityId) {
        User user = utilityService.getLoggedInDataContext();
        List<ActionItemResponse> items = actionItemService.getForEntity(
            entityType, entityId, user.getId(), resolveRoleNames(user), user.getTenantId());
        return ResponseEntity.ok(ApiResponse.success(items));
    }

    /**
     * POST /v1/action-items
     * Manually create an action item (used by audit, risk, issue modules).
     * REVISION_REQUEST items are created automatically via CommentService.
     */
    @PostMapping("/v1/action-items")
    @Operation(summary = "Create an action item")
    public ResponseEntity<ApiResponse<ActionItemResponse>> create(
            @Valid @RequestBody ActionItemRequest req) {
        User user = utilityService.getLoggedInDataContext();
        ActionItemResponse response = actionItemService.create(
            req, user.getId(), user.getTenantId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    /**
     * PATCH /v1/action-items/:id/status
     * Update status: IN_PROGRESS, RESOLVED, DISMISSED, OPEN (reopen).
     * Permission enforced by service based on resolution_reserved_for / resolution_role.
     */
    @PatchMapping("/v1/action-items/{id}/status")
    @Operation(summary = "Update action item status")
    public ResponseEntity<ApiResponse<ActionItemResponse>> updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody ActionItemStatusUpdate update) {
        User user = utilityService.getLoggedInDataContext();
        ActionItemResponse response = actionItemService.updateStatus(
            id, update, user.getId(), resolveRoleNames(user), user.getTenantId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ── Helper ────────────────────────────────────────────────────────────

    private List<String> resolveRoleNames(User user) {
        if (user.getRoles() == null) return List.of();
        return user.getRoles().stream()
            .map(r -> r.getName() != null ? r.getName() : "")
            .filter(s -> !s.isEmpty())
            .toList();
    }
}
