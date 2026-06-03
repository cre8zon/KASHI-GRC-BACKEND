package com.kashi.grc.workflow.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * One compound-task section within a WorkflowStepRequest.
 *
 * At runtime the engine reads workflow_step_sections ONCE per task
 * inside snapshotSectionsForTask() and copies every field into
 * task_section_completions.snap_* columns. After that the blueprint row
 * is never read again — running instances are 100% isolated.
 *
 * NEW FIELDS (UI rendering):
 *   sectionScreenKey — screen config for section container UI
 *   itemScreenKey    — screen config for each item card
 *   itemRefType      — what itemRefId points to (CONTROL, QUESTION_RESPONSE, etc.)
 *   sectionUiJson    — inline override for section container
 *   itemUiJson       — inline override for item cards
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class StepSectionRequest {

    /** Present on update so upsertSteps can match the existing row. Null on create. */
    public Long id;

    /**
     * Machine-readable key, unique within the step.
     * e.g. "ANSWER", "UPLOAD", "REVIEW", "CONTROL_ASSESSMENT"
     * Must be SCREAMING_SNAKE_CASE.
     */
    @NotBlank
    public String sectionKey;

    /** Display order within the step (1-based). */
    public Integer sectionOrder;

    /** Human-readable label shown in CompoundTaskProgress bar. */
    @NotBlank
    public String label;

    /** Optional longer description shown in section panel header. */
    public String description;

    /**
     * If true (default), the task cannot be approved until this section completes.
     * If false, section is shown in progress but does not gate approval.
     */
    public boolean required = true;

    /**
     * The string published in TaskSectionEvent.completionEvent to mark this section done.
     * e.g. "ASSESSMENT_SUBMITTED", "CONTROL_EVALUATED", "DOCUMENT_UPLOADED"
     * Must be unique within the step.
     */
    @NotBlank
    public String completionEvent;

    /** Case 2: when true, section distributes work to other users via sub-tasks. */
    public boolean requiresAssignment = false;

    /** Case 3: when true, section tracks individual items (controls, findings, etc.). */
    public boolean tracksItems = false;

    // ── NEW: UI rendering fields ──────────────────────────────────────────────

    /**
     * Screen config key for section container UI.
     * Frontend fetches GET /v1/ui-config/screen/:sectionScreenKey.
     * Null = generic CompoundTaskProgress fallback (TPRM backward compat).
     * e.g. "risk_control_section", "audit_finding_section"
     */
    public String sectionScreenKey;

    /**
     * Screen config key for each item card within this section.
     * Frontend fetches GET /v1/ui-config/screen/:itemScreenKey.
     * Null when tracksItems = false.
     * e.g. "risk_control_item", "audit_finding_item"
     */
    public String itemScreenKey;

    /**
     * Entity type each TaskSectionItem.itemRefId resolves to.
     * Used for: ItemPanel entityType, action item entityType, navContext.
     * e.g. "CONTROL", "QUESTION_RESPONSE", "FINDING", "RISK_GAP", "POLICY_CLAUSE"
     * Null when tracksItems = false.
     */
    public String itemRefType;

    /**
     * Inline UI override for the section container.
     * Applied on top of sectionScreenKey defaults. Snapshotted to snap_section_ui_json.
     * JSON: { "showAssignmentPanel": true, "submitLabel": "Submit section", ... }
     */
    public String sectionUiJson;

    /**
     * Inline UI override for each item card within this section.
     * Applied on top of itemScreenKey defaults. Snapshotted to snap_item_ui_json.
     * JSON: { "editableFields": [...], "canDelegate": true, "itemPanelMode": "responder" }
     */
    public String itemUiJson;
}