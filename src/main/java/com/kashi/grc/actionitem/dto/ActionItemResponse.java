package com.kashi.grc.actionitem.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kashi.grc.actionitem.domain.ActionItem;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * ActionItemResponse — returned by all action item endpoints.
 *
 * NEW FIELDS:
 *   itemScreenKey    — screen config key for assignee's work UI
 *   itemUiJson       — inline UI override for work screen
 *   parentEntityType — parent record type (e.g. RISK when item is a CONTROL)
 *   parentEntityId   — parent record ID
 *
 * All new fields are @JsonInclude(NON_NULL) — null when not set.
 * All existing callers unaffected.
 */
@Data @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ActionItemResponse {

    // ── Identity ──────────────────────────────────────────────────────────────
    private Long   id;
    private Long   blueprintId;

    // ── Assignment ────────────────────────────────────────────────────────────
    private Long   assignedTo;
    private String assignedToName;
    private String assignedGroupRole;
    private Long   createdBy;
    private String createdByName;
    private Long   vendorId;

    // ── Source ────────────────────────────────────────────────────────────────
    private ActionItem.SourceType sourceType;
    private Long                  sourceId;

    // ── Entity — what this is about ───────────────────────────────────────────
    private ActionItem.EntityType entityType;
    private Long                  entityId;

    // ── NEW: Parent context ────────────────────────────────────────────────────
    /**
     * Parent record context — the module record containing the item being delegated.
     * entityType=CONTROL, entityId=42, parentEntityType=RISK, parentEntityId=10
     * Used for breadcrumb navigation and parent page query invalidation.
     */
    private ActionItem.EntityType parentEntityType;
    private Long                  parentEntityId;

    // ── Content ───────────────────────────────────────────────────────────────
    private String title;
    private String description;

    // ── State ─────────────────────────────────────────────────────────────────
    private ActionItem.Status   status;
    private ActionItem.Priority priority;
    private LocalDateTime       dueAt;

    // ── Resolution ────────────────────────────────────────────────────────────
    private Long          resolutionReservedFor;
    private String        resolutionReservedForName;
    private String        resolutionRole;
    private LocalDateTime resolvedAt;
    private Long          resolvedBy;
    private String        resolvedByName;
    private String        resolutionNote;

    // ── Navigation ────────────────────────────────────────────────────────────
    private String navContext;  // JSON passthrough — module sets this at creation

    // ── NEW: Item UI rendering ─────────────────────────────────────────────────
    /**
     * Screen config key for the assignee's work UI.
     * Frontend: GET /v1/ui-config/screen/:itemScreenKey → fields, actions, ItemPanel config.
     * Null = generic action item view.
     */
    private String itemScreenKey;

    /**
     * Inline UI override for the work screen.
     * Merged with itemScreenKey config by frontend.
     * { "editableFields": [...], "showComments": true, "itemPanelMode": "responder" }
     */
    private String itemUiJson;

    // ── Timestamps ────────────────────────────────────────────────────────────
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ── Remediation / clarification ───────────────────────────────────────────
    private String  remediationType;
    private String  severity;
    private String  expectedEvidence;
    private Boolean acceptedRisk;
    private Long    acceptedRiskBy;
    private String  acceptedRiskByName;
    private String  acceptedRiskNote;
    private LocalDateTime acceptedRiskAt;

    // ── Computed ──────────────────────────────────────────────────────────────
    private boolean canResolve;   // true when calling user can mark RESOLVED
    private boolean isOverdue;    // true when dueAt is past and not resolved/dismissed
}