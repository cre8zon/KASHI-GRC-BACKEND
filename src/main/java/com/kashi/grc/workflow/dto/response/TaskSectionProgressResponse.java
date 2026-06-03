package com.kashi.grc.workflow.dto.response;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

/**
 * TaskSectionProgressResponse — returned by GET /v1/compound-tasks/{taskInstanceId}/progress.
 *
 * All fields sourced from TaskSectionCompletion.snap_* — zero blueprint join.
 *
 * NEW fields: sectionScreenKey, itemScreenKey, itemRefType, sectionUiJson, itemUiJson, items.
 *
 * Frontend CompoundSectionRenderer.jsx uses these to render sections and item cards
 * without any additional blueprint API calls.
 *
 * BACKWARD COMPAT: all new fields are nullable. When null, frontend falls back to
 * existing CompoundTaskProgress component (TPRM behavior preserved).
 */
@Data
@Builder
public class TaskSectionProgressResponse {

    // ── Existing fields ───────────────────────────────────────────────────────

    private String  sectionKey;
    private Integer sectionOrder;
    private String  label;
    private String  description;
    private boolean required;
    private String  completionEvent;
    private boolean requiresAssignment;
    private boolean tracksItems;

    // Case 2: assignment progress
    private int assigneesTotal;
    private int assigneesCompleted;

    // Case 3: item progress
    private int itemsTotal;
    private int itemsCompleted;

    // Completion state
    private boolean       completed;
    private LocalDateTime completedAt;
    private Long          completedBy;
    private String        artifactType;
    private Long          artifactId;

    // ── NEW: UI rendering ─────────────────────────────────────────────────────

    /**
     * Screen config key for section container.
     * Frontend: GET /v1/ui-config/screen/:sectionScreenKey
     * Null → CompoundTaskProgress fallback (existing TPRM behavior).
     */
    private String sectionScreenKey;

    /**
     * Screen config key for each item card inside this section.
     * Frontend: GET /v1/ui-config/screen/:itemScreenKey
     * Null when tracksItems = false.
     */
    private String itemScreenKey;

    /**
     * What each item.itemRefId resolves to.
     * CONTROL | QUESTION_RESPONSE | FINDING | RISK_GAP | POLICY_CLAUSE
     * Used for: ItemPanel entityType, action item entityType, navContext.
     */
    private String itemRefType;

    /**
     * Inline section container UI override (JSON string).
     * Merged with sectionScreenKey config by frontend.
     * { "showAssignmentPanel": true, "submitLabel": "Submit section",
     *   "allowReopen": false, "showBatchAssign": true }
     */
    private String sectionUiJson;

    /**
     * Inline item card UI override (JSON string).
     * Merged with itemScreenKey config by frontend.
     * { "editableFields": ["evidenceText", "complianceStatus"],
     *   "readOnlyFields": ["controlCode"],
     *   "canDelegate": true, "showItemPanel": true,
     *   "itemPanelMode": "responder",
     *   "availableActions": ["save_draft", "mark_complete", "assign_item"] }
     */
    private String itemUiJson;

    /**
     * Tracked items (Case 3 — populated when tracksItems = true).
     * Each item carries runtime status + data needed to render its card.
     * Empty list when tracksItems = false.
     */
    private List<SectionItemResponse> items;

    // ── Nested: per-item ──────────────────────────────────────────────────────

    @Data
    @Builder
    public static class SectionItemResponse {
        /** TaskSectionItem.id — used for complete/assign API calls */
        private Long   id;

        /** Same as parent section.itemRefType */
        private String itemRefType;

        /** The entity ID — controlId, findingId, questionInstanceId, etc. */
        private Long   itemRefId;

        /** Human-readable label */
        private String itemLabel;

        /** PENDING | IN_PROGRESS | COMPLETED */
        private String status;

        /** User assigned to this item (null = unassigned) */
        private Long   assignedToUserId;
        private String assignedToUserName;

        /** Whether an open action item exists for this item (for badge display) */
        private boolean hasOpenActionItem;
    }
}