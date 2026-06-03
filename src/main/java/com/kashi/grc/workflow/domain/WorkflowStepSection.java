package com.kashi.grc.workflow.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * WorkflowStepSection — BLUEPRINT ONLY. Written by Platform Admin.
 *
 * Read exactly ONCE inside snapshotSectionsForTask(). Never again during runtime.
 * Running instances are 100% isolated via TaskSectionCompletion.snap_* columns.
 *
 * ── NEW UI FIELDS ──────────────────────────────────────────────────────────────
 *
 * sectionScreenKey — screen config for section container (header, assignment, submit)
 * itemScreenKey    — screen config for each item card inside this section
 * itemRefType      — what itemRefId points to: CONTROL, QUESTION_RESPONSE, FINDING, etc.
 * sectionUiJson    — inline override for section container (extends sectionScreenKey)
 * itemUiJson       — inline override for item cards (extends itemScreenKey)
 *
 * These follow the exact same pattern as WorkflowStep.stepUiOverrideJson:
 *   Blueprint field → snapshotted to TaskSectionCompletion.snap_* → read at runtime from snap
 *   Never re-read from blueprint after snapshot.
 *
 * MIGRATION:
 *   ALTER TABLE workflow_step_sections
 *     ADD COLUMN section_screen_key VARCHAR(100) NULL,
 *     ADD COLUMN item_screen_key    VARCHAR(100) NULL,
 *     ADD COLUMN item_ref_type      VARCHAR(100) NULL,
 *     ADD COLUMN section_ui_json    JSON NULL,
 *     ADD COLUMN item_ui_json       JSON NULL;
 */
@Entity
@Table(name = "workflow_step_sections",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_wss_step_key",
                columnNames = {"step_id", "section_key"}))
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class WorkflowStepSection extends BaseEntity {

    @Column(name = "step_id", nullable = false)
    private Long stepId;

    @Column(name = "section_key", nullable = false, length = 100)
    private String sectionKey;

    @Column(name = "section_order", nullable = false)
    @Builder.Default
    private Integer sectionOrder = 0;

    @Column(name = "label", nullable = false, length = 255)
    private String label;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "required", nullable = false)
    @Builder.Default
    private boolean required = true;

    @Column(name = "completion_event", nullable = false, length = 100)
    private String completionEvent;

    @Column(name = "requires_assignment", nullable = false)
    @Builder.Default
    private boolean requiresAssignment = false;

    @Column(name = "tracks_items", nullable = false)
    @Builder.Default
    private boolean tracksItems = false;

    // ── NEW: UI rendering ─────────────────────────────────────────────────────

    /**
     * Screen config key for the section container.
     * GET /v1/ui-config/screen/:sectionScreenKey returns how to render:
     *   assignment panel, progress bar, submit button, header.
     * Null → CompoundTaskProgress fallback (preserves TPRM behavior).
     */
    @Column(name = "section_screen_key", length = 100)
    private String sectionScreenKey;

    /**
     * Screen config key for each item card inside this section.
     * GET /v1/ui-config/screen/:itemScreenKey returns:
     *   fields to display, actions available, whether ItemPanel is shown.
     * Null when tracksItems = false.
     */
    @Column(name = "item_screen_key", length = 100)
    private String itemScreenKey;

    /**
     * Entity type each TaskSectionItem.itemRefId resolves to.
     * Used by frontend for: ItemPanel entityType, action item entityType, navContext.
     * Must match ActionItem.EntityType or a recognized string for new modules.
     * Examples: CONTROL, QUESTION_RESPONSE, FINDING, RISK_GAP, POLICY_CLAUSE
     */
    @Column(name = "item_ref_type", length = 100)
    private String itemRefType;

    /**
     * Inline UI override for the section container.
     * Applied on top of sectionScreenKey defaults. Snapshotted to snap_section_ui_json.
     *
     * {
     *   "showAssignmentPanel": true,
     *   "showProgressBar":     true,
     *   "submitLabel":         "Submit section",
     *   "submitConfirm":       "This cannot be undone. Submit?",
     *   "allowReopen":         false,
     *   "showBatchAssign":     true
     * }
     */
    @Column(name = "section_ui_json", columnDefinition = "JSON")
    private String sectionUiJson;

    /**
     * Inline UI override for each item card inside this section.
     * Applied on top of itemScreenKey defaults. Snapshotted to snap_item_ui_json.
     *
     * {
     *   "editableFields":   ["evidenceText", "complianceStatus"],
     *   "readOnlyFields":   ["controlCode"],
     *   "canDelegate":      true,
     *   "showItemPanel":    true,
     *   "itemPanelMode":    "responder",
     *   "showEvidence":     true,
     *   "showComments":     true,
     *   "showActionItems":  true,
     *   "availableActions": ["save_draft", "mark_complete", "assign_item"]
     * }
     */
    @Column(name = "item_ui_json", columnDefinition = "JSON")
    private String itemUiJson;
}