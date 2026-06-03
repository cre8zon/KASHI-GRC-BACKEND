package com.kashi.grc.workflow.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

/**
 * Blueprint section descriptor returned inside WorkflowStepResponse.sections[].
 * Populated from workflow_step_sections by buildWorkflowResponse().
 *
 * The admin UI (StepSectionEditor) reads these to pre-populate the section editor
 * when opening an existing blueprint step.
 *
 * NEW FIELDS (UI rendering):
 *   sectionScreenKey, itemScreenKey, itemRefType, sectionUiJson, itemUiJson
 *
 * All new fields @JsonInclude NON_NULL — backward safe for existing callers.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StepSectionResponse {

    // ── Existing fields ───────────────────────────────────────────────────────
    private Long    id;
    private String  sectionKey;
    private Integer sectionOrder;
    private String  label;
    private String  description;
    private boolean required;
    private String  completionEvent;
    private boolean requiresAssignment;
    private boolean tracksItems;

    // ── NEW: UI rendering fields ──────────────────────────────────────────────

    /**
     * Screen config key for section container.
     * GET /v1/ui-config/screen/:sectionScreenKey → header, assignment, submit.
     * Null = generic CompoundTaskProgress (TPRM backward compat).
     */
    private String sectionScreenKey;

    /**
     * Screen config key for each item card.
     * GET /v1/ui-config/screen/:itemScreenKey → fields, actions, ItemPanel.
     * Null when tracksItems = false.
     */
    private String itemScreenKey;

    /**
     * Entity type each item.itemRefId resolves to.
     * CONTROL | QUESTION_RESPONSE | FINDING | RISK_GAP | POLICY_CLAUSE
     */
    private String itemRefType;

    /**
     * Inline section container UI override (JSON string).
     * { "showAssignmentPanel": true, "submitLabel": "Submit section", "allowReopen": false }
     */
    private String sectionUiJson;

    /**
     * Inline item card UI override (JSON string).
     * { "editableFields": [...], "canDelegate": true, "itemPanelMode": "responder" }
     */
    private String itemUiJson;
}