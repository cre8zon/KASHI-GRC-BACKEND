package com.kashi.grc.actionitem.controller;

import com.kashi.grc.actionitem.domain.ActionItem;
import com.kashi.grc.actionitem.dto.ActionItemRequest;
import com.kashi.grc.actionitem.dto.ActionItemResponse;
import com.kashi.grc.actionitem.dto.ActionItemStatusUpdate;
import com.kashi.grc.actionitem.service.ActionItemService;
import com.kashi.grc.uiconfig.service.UiConfigService;
import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.usermanagement.domain.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * ActionItemController — cross-module obligation tracking.
 *
 * ── MODULE-AGNOSTIC ITEM-LEVEL SUB-DELEGATION ──────────────────────────────────
 *
 * Action items are the mechanism for an ACTOR to delegate individual items
 * (questions, controls, findings, clauses) within their workflow section to
 * another user. They are NOT workflow gates — open items do not block step approval.
 *
 * The pattern works identically across all modules:
 *
 * TPRM (existing):
 *   ACTOR = VENDOR_RESPONDER (section task)
 *   Delegates question Q5 to contributor John:
 *   entityType=QUESTION_RESPONSE, entityId=questionInstanceId, vendorId=vendorId
 *   navContext = { route: "/vendor/assessments/23/fill", questionInstanceId: 456 }
 *
 * RISK (new modules follow same pattern):
 *   ACTOR = RISK_ANALYST (section task)
 *   Delegates control CC6.1 evidence to Sarah:
 *   entityType=CONTROL, entityId=controlInstanceId
 *   navContext = { route: "/module/risk/42", tab: "evidence", controlId: controlInstanceId }
 *
 * AUDIT:
 *   ACTOR = LEAD_AUDITOR (section task)
 *   Delegates finding evidence to auditee dept head:
 *   entityType=FINDING, entityId=findingId
 *   navContext = { route: "/module/audit/88", tab: "evidence", findingId: findingId }
 *
 * KEY RULES:
 *   1. vendorId: set ONLY for TPRM items. Null for org-internal modules.
 *   2. navContext.route: /module/:entityType/:id for new modules (Universal Module Page).
 *   3. sourceType=SYSTEM for direct actor creation. sourceType=COMMENT for auto-created items.
 *   4. Section completion checks question response STATUS — not action item STATUS.
 *
 * ── ENDPOINTS ─────────────────────────────────────────────────────────────────
 *
 * GET    /v1/action-items/my               — my open items across all modules
 * GET    /v1/action-items/my/count         — badge count
 * GET    /v1/action-items?entityType=&entityId= — all items for an entity (oversight)
 * GET    /v1/action-items/:id              — single item with full context [NEW]
 * POST   /v1/action-items                 — create (module-agnostic, use navContext)
 * PUT    /v1/action-items/:id             — update title/description/dueAt/priority/assignee [NEW]
 * PATCH  /v1/action-items/:id/status      — update status only
 * DELETE /v1/action-items/:id             — dismiss (soft delete) [NEW]
 */
@Slf4j
@RestController
@Tag(name = "Action Items", description = "Cross-module obligation tracking — module-agnostic item-level sub-delegation")
@RequiredArgsConstructor
public class ActionItemController {

    private final ActionItemService actionItemService;
    private final UtilityService    utilityService;
    private final UiConfigService   uiConfigService;

    // ══════════════════════════════════════════════════════════════
    // MY ITEMS (unchanged)
    // ══════════════════════════════════════════════════════════════

    @GetMapping("/v1/action-items/my")
    @Operation(summary = "Get my open action items — all entityTypes across all modules")
    public ResponseEntity<ApiResponse<List<ActionItemResponse>>> getMyItems() {
        User user = utilityService.getLoggedInDataContext();
        List<ActionItemResponse> items = actionItemService.getMyOpenItems(
                user.getId(), resolveRoleNames(user), user.getTenantId(), user.getVendorId());
        return ResponseEntity.ok(ApiResponse.success(items));
    }

    @GetMapping("/v1/action-items/my/count")
    @Operation(summary = "Count of my open action items — for sidebar badge")
    public ResponseEntity<ApiResponse<Long>> getMyCount() {
        User user = utilityService.getLoggedInDataContext();
        long count = actionItemService.countOpenForUser(user.getId(), user.getTenantId());
        return ResponseEntity.ok(ApiResponse.success(count));
    }

    // ══════════════════════════════════════════════════════════════
    // FOR ENTITY — oversight view (unchanged)
    // ══════════════════════════════════════════════════════════════

    @GetMapping("/v1/action-items")
    @Operation(summary = "Get all action items for an entity — oversight view for CISO/coordinator")
    public ResponseEntity<ApiResponse<List<ActionItemResponse>>> getForEntity(
            @RequestParam ActionItem.EntityType entityType,
            @RequestParam Long entityId) {
        User user = utilityService.getLoggedInDataContext();
        List<ActionItemResponse> items = actionItemService.getForEntity(
                entityType, entityId, user.getId(), resolveRoleNames(user), user.getTenantId());
        return ResponseEntity.ok(ApiResponse.success(items));
    }

    /**
     * Bulk oversight fetch: every action item for a set of entity ids.
     *
     *   GET /v1/action-items/by-entities?entityType=QUESTION_RESPONSE&entityIds=1,2,3
     *
     * Replaces one request per question on the assessment fill/review pages.
     * Each returned item carries its entityId, so the client groups locally.
     */
    @GetMapping("/v1/action-items/by-entities")
    @Operation(summary = "Get action items for many entities at once — avoids per-row requests")
    public ResponseEntity<ApiResponse<List<ActionItemResponse>>> getForEntities(
            @RequestParam ActionItem.EntityType entityType,
            @RequestParam List<Long> entityIds) {
        User user = utilityService.getLoggedInDataContext();
        List<ActionItemResponse> items = actionItemService.getForEntities(
                entityType, entityIds, user.getId(), resolveRoleNames(user), user.getTenantId());
        return ResponseEntity.ok(ApiResponse.success(items));
    }

    // ══════════════════════════════════════════════════════════════
    // GET BY ID — new
    // ══════════════════════════════════════════════════════════════

    @GetMapping("/v1/action-items/{id}")
    @Operation(summary = "Get a single action item by ID with full resolved context")
    public ResponseEntity<ApiResponse<ActionItemResponse>> getById(@PathVariable Long id) {
        User user = utilityService.getLoggedInDataContext();
        ActionItemResponse response = actionItemService.getById(
                id, user.getId(), resolveRoleNames(user), user.getTenantId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Resolves the Screen Designer config for an action item's work card.
     *
     * When an assignee opens an action item from their inbox, the frontend needs
     * to know which Screen Designer screen to render for the work card:
     *   - itemScreenKey on the ActionItem → GET /v1/ui-config/screen/{key}
     *   - Plus the entity data for the item (fetched separately via entityType + entityId)
     *
     * This endpoint combines both: returns the screen config AND the entity's navContext
     * so the frontend can render the correct focused work card in one call.
     *
     * Response shape:
     * {
     *   "itemScreenKey":   "control_evidence_item",
     *   "screenConfig":    { ...UiScreenConfig... },
     *   "navContext":      { "route": "/module/audit_control_instance/42", "tab": "evidence" },
     *   "entityType":      "AUDIT_CONTROL_INSTANCE",
     *   "entityId":        42,
     *   "actionItemId":    99,
     *   "title":           "Upload evidence for MFA-001"
     * }
     */
    @GetMapping("/v1/action-items/{id}/item-screen")
    @Operation(summary = "Resolve the work card screen config for an action item — used by task inbox")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getItemScreen(@PathVariable Long id) {

        var user     = utilityService.getLoggedInDataContext();
        var tenantId = user.getTenantId();

        // FIX: getById returns ActionItemResponse (not ActionItem domain object)
        ActionItemResponse item = actionItemService.getById(
                id, user.getId(), resolveRoleNames(user), tenantId);

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("actionItemId", item.getId());
        result.put("title",        item.getTitle());
        result.put("entityType",   item.getEntityType() != null ? item.getEntityType().name() : null);
        result.put("entityId",     item.getEntityId());
        result.put("status",       item.getStatus());
        result.put("priority",     item.getPriority());
        result.put("dueAt",        item.getDueAt());

        // navContext — deep-link JSON stored on the action item
        result.put("navContext", item.getNavContext());

        // itemScreenKey — which Screen Designer screen to render for this work card
        String screenKey = item.getItemScreenKey();
        result.put("itemScreenKey", screenKey);

        // Resolve screen config if itemScreenKey is set
        if (screenKey != null && !screenKey.isBlank()) {
            try {
                var screenConfig = uiConfigService.getScreenConfig(screenKey);
                result.put("screenConfig", screenConfig);
            } catch (Exception ex) {
                log.warn("[ACTION-ITEM] Screen config not found for key={} | {}", screenKey, ex.getMessage());
                result.put("screenConfig", null);
            }
        } else {
            result.put("screenConfig", null);
        }

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // ══════════════════════════════════════════════════════════════
    // CREATE (unchanged)
    // ══════════════════════════════════════════════════════════════

    /**
     * POST /v1/action-items
     *
     * Module-agnostic creation. Supply navContext JSON for deep-link routing.
     *
     * For item-level sub-delegation from a workflow task:
     *   sourceType: SYSTEM (direct actor creation) or COMMENT (auto from revision request)
     *   sourceId:   the taskInstanceId or commentId that triggered this
     *   entityType: QUESTION_RESPONSE | CONTROL | FINDING | RISK | AUDIT | POLICY | ISSUE
     *   entityId:   the specific item being delegated
     *   navContext: JSON string with route and any context the assignee needs
     */
    @PostMapping("/v1/action-items")
    @Operation(summary = "Create an action item — module-agnostic, use navContext for routing")
    public ResponseEntity<ApiResponse<ActionItemResponse>> create(
            @Valid @RequestBody ActionItemRequest req) {
        User user = utilityService.getLoggedInDataContext();
        ActionItemResponse response = actionItemService.create(req, user.getId(), user.getTenantId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    // ══════════════════════════════════════════════════════════════
    // UPDATE — new
    // ══════════════════════════════════════════════════════════════

    @PutMapping("/v1/action-items/{id}")
    @Operation(summary = "Update action item details — title, description, due date, priority, assignee")
    public ResponseEntity<ApiResponse<ActionItemResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody ActionItemUpdateRequest req) {
        User user = utilityService.getLoggedInDataContext();
        ActionItemResponse response = actionItemService.update(
                id, req.toServiceRequest(), user.getId(), resolveRoleNames(user), user.getTenantId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ══════════════════════════════════════════════════════════════
    // STATUS UPDATE (unchanged)
    // ══════════════════════════════════════════════════════════════

    @PatchMapping("/v1/action-items/{id}/status")
    @Operation(summary = "Update action item status — IN_PROGRESS, RESOLVED, DISMISSED, OPEN (reopen)")
    public ResponseEntity<ApiResponse<ActionItemResponse>> updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody ActionItemStatusUpdate update) {
        User user = utilityService.getLoggedInDataContext();
        ActionItemResponse response = actionItemService.updateStatus(
                id, update, user.getId(), resolveRoleNames(user), user.getTenantId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ══════════════════════════════════════════════════════════════
    // DELETE — new (soft delete via DISMISSED status)
    // ══════════════════════════════════════════════════════════════

    @DeleteMapping("/v1/action-items/{id}")
    @Operation(summary = "Dismiss (soft-delete) an action item — only creator or ORG_ADMIN")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        User user = utilityService.getLoggedInDataContext();
        actionItemService.dismiss(id, user.getId(), resolveRoleNames(user), user.getTenantId());
        return ResponseEntity.ok(ApiResponse.success());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<String> resolveRoleNames(User user) {
        if (user.getRoles() == null) return List.of();
        return user.getRoles().stream()
                .map(r -> r.getName() != null ? r.getName() : "")
                .filter(s -> !s.isEmpty())
                .toList();
    }

    // ── Inner update request ──────────────────────────────────────────────────

    @Data
    public static class ActionItemUpdateRequest {
        private String              title;
        private String              description;
        private String              dueAt;             // ISO datetime string
        private ActionItem.Priority priority;
        private Long                assignedTo;
        private String              assignedGroupRole;
        private String              navContext;        // updated if item's route changes

        /**
         * Convert to ActionItemService.UpdateRequest.
         * Service validates caller permissions before applying.
         */
        public ActionItemService.UpdateRequest toServiceRequest() {
            ActionItemService.UpdateRequest r = new ActionItemService.UpdateRequest();
            r.setTitle(title);
            r.setDescription(description);
            r.setDueAt(dueAt);
            r.setPriority(priority);
            r.setAssignedTo(assignedTo);
            r.setAssignedGroupRole(assignedGroupRole);
            r.setNavContext(navContext);
            return r;
        }
    }
}