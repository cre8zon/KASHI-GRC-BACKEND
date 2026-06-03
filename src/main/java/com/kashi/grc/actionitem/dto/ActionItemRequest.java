package com.kashi.grc.actionitem.dto;

import com.kashi.grc.actionitem.domain.ActionItem;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * ActionItemRequest — module-agnostic action item creation.
 *
 * NEW FIELDS:
 *   itemScreenKey    — screen config key for assignee's work UI
 *   itemUiJson       — inline UI override for work screen
 *   parentEntityType — parent record type (e.g. RISK when delegating a CONTROL)
 *   parentEntityId   — parent record ID
 *
 * USAGE by context:
 *
 *   TPRM (existing, unchanged):
 *     sourceType=COMMENT, entityType=QUESTION_RESPONSE, entityId=questionInstanceId,
 *     vendorId=vendorId, navContext={route, questionInstanceId, assessmentId}
 *
 *   New module — compound task item delegation:
 *     sourceType=WORKFLOW_STEP, entityType=CONTROL, entityId=controlId,
 *     parentEntityType=RISK, parentEntityId=riskId,
 *     itemScreenKey="risk_control_item", itemUiJson={...},
 *     navContext={route, sectionKey, taskInstanceId, itemRefType, itemRefId}
 *
 *   Record-level (from RecordDetailTemplate action items tab):
 *     sourceType=SYSTEM, entityType=RISK, entityId=riskId,
 *     navContext={route: "/module/risk/42", tab: "actions"}
 */
@Data
public class ActionItemRequest {

    // ── Blueprint (optional) ──────────────────────────────────────────────────
    private Long   blueprintId;
    private String blueprintCode;  // alternative to blueprintId — resolved by service

    // ── Assignment ────────────────────────────────────────────────────────────
    private Long   assignedTo;
    private String assignedGroupRole;

    // ── Source — what triggered this ─────────────────────────────────────────
    @NotNull private ActionItem.SourceType sourceType;
    @NotNull private Long                  sourceId;

    // ── Entity — what specific item this is about ─────────────────────────────
    @NotNull private ActionItem.EntityType entityType;
    @NotNull private Long                  entityId;

    // ── NEW: Parent context ────────────────────────────────────────────────────
    /**
     * Parent record type. The module record that contains the item being delegated.
     * entityType=CONTROL, entityId=42, parentEntityType=RISK, parentEntityId=10
     * → "Working on Control #42 within Risk #10"
     * Null for top-level action items.
     */
    private ActionItem.EntityType parentEntityType;
    private Long                  parentEntityId;

    // ── Content ───────────────────────────────────────────────────────────────
    @NotBlank private String title;
    private String description;

    // ── Resolution ────────────────────────────────────────────────────────────
    private Long   resolutionReservedFor;
    private String resolutionRole;

    // ── State ─────────────────────────────────────────────────────────────────
    private ActionItem.Priority priority;
    private String              dueAt;  // ISO datetime string — parsed by service

    // ── Navigation ────────────────────────────────────────────────────────────
    /**
     * JSON deep-link context for routing the assignee to the work item.
     *
     * TPRM: { "route": "/vendor/assessments/23/fill", "questionInstanceId": 456 }
     * New:  { "route": "/workflow/tasks/99", "sectionKey": "control_assessment",
     *         "itemId": 88, "itemRefType": "CONTROL", "itemRefId": 42 }
     */
    private String navContext;

    /**
     * Vendor scope for role-based assignment.
     * Set for TPRM items only. Null for org-internal modules.
     */
    private Long vendorId;

    // ── NEW: Item UI rendering ─────────────────────────────────────────────────
    /**
     * Screen config key for the assignee's work UI.
     * GET /v1/ui-config/screen/:itemScreenKey → fields, actions, ItemPanel config.
     * Null = generic action item view (title, description, resolve button).
     *
     * Pass the same key used in the section blueprint:
     *   itemScreenKey = section.itemScreenKey (from TaskSectionProgressResponse)
     */
    private String itemScreenKey;

    /**
     * Inline UI override for the work screen.
     * Applied on top of itemScreenKey defaults.
     * { "editableFields": ["evidenceText"], "showComments": true, "itemPanelMode": "responder" }
     */
    private String itemUiJson;
}