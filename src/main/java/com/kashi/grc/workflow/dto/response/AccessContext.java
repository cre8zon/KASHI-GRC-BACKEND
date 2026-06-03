package com.kashi.grc.workflow.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kashi.grc.workflow.enums.TaskRole;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * AccessContext — extended to serve as the single resolved access object
 * the frontend reads on every workflow/module page.
 *
 * ── EXISTING FIELDS (unchanged — all existing callers unaffected) ─────────────
 *   mode, canView, canEdit, canAct, taskRole,
 *   stepStatus, workflowStatus, reason
 *
 * ── NEW FIELDS (all @JsonInclude NON_NULL — absent when not set) ─────────────
 *
 * Field-level:
 *   editableFields   — when non-empty, ONLY these fields are editable
 *   readOnlyFields   — always read-only regardless of canEdit
 *   hiddenFields     — completely hidden from this user at this step
 *
 * Tab/panel visibility:
 *   visibleTabs      — when non-empty, ONLY these tabs are shown
 *   hiddenTabs       — explicitly hidden tabs
 *
 * Action buttons:
 *   availableActions — workflow task actions: APPROVE, REJECT, SEND_BACK, etc.
 *
 * Permission set:
 *   permissions      — resolved permission codes for this user
 *                      (role grants + user overrides, merged at resolve time)
 *                      Used by frontend PermissionGate checks.
 *
 * SoD:
 *   sodViolations    — conflicts detected for this user on this instance.
 *                      HARD = action buttons blocked.
 *                      SOFT = warning shown, exception required.
 *
 * ── HOW THESE ARE POPULATED ──────────────────────────────────────────────────
 *
 * WorkflowAccessService.resolve() now:
 *   1. Reads WorkflowStep.stepUiOverrideJson (snapshotted → StepInstance.snapUiOverrideJson)
 *   2. Reads user's PermissionGrant rows for their roles
 *   3. Reads user's UserPermissionOverride rows (wins over role grants)
 *   4. Evaluates SodRule table for conflicts on this workflowInstanceId
 *   5. Merges all into this object before returning
 *
 * Resolution order (step override can only RESTRICT, never EXPAND role ceiling):
 *   Base role permissions
 *     ↓ intersect with step UI override restrictions
 *     ↓ then apply user overrides (can expand OR restrict within role ceiling)
 *     → AccessContext
 *
 * ── FRONTEND USAGE ────────────────────────────────────────────────────────────
 *
 *   const { data: access } = useAccessContext(taskId, stepInstanceId)
 *
 *   // Tab visibility
 *   if (!access.visibleTabs || access.visibleTabs.includes('evidence')) { ... }
 *
 *   // Field editability
 *   const editable = access.canEdit
 *     && (!access.editableFields?.length || access.editableFields.includes('mitigationPlan'))
 *     && !access.readOnlyFields?.includes('mitigationPlan')
 *
 *   // Action buttons
 *   {access.availableActions?.includes('APPROVE') && <ApproveButton />}
 *
 *   // Permission gate
 *   {access.permissions?.includes('risk.approve') && <ApproveButton />}
 *
 *   // SoD
 *   {access.sodViolations?.some(v => v.conflictType === 'HARD') && <SodBlockBanner />}
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AccessContext {

    // ── Existing fields — DO NOT RENAME (frontend uses these) ────────────────

    /** EDIT | OBSERVER | COMPLETED | DENIED */
    private String   mode;

    private boolean  canView;
    private boolean  canEdit;
    private boolean  canAct;

    /** ACTOR | ASSIGNER | null */
    private TaskRole taskRole;

    private String   stepStatus;
    private String   workflowStatus;
    private String   reason;

    // ── New: step label (shown in task inbox and breadcrumb) ─────────────────

    /** e.g. "Risk approval", "Vendor assessment fill" */
    private String stepLabel;

    /** e.g. FILL, ASSIGN, REVIEW, EVALUATE, APPROVE, ACKNOWLEDGE
     *  Frontend uses this to auto-select the most relevant tab when
     *  opening an entity from a workflow task (e.g. ASSIGN → sections tab). */
    private String stepAction;

    // ── New: field-level visibility ───────────────────────────────────────────

    /**
     * When non-empty: ONLY these field keys are editable.
     * Null/empty = all fields editable (subject to canEdit).
     */
    private List<String> editableFields;

    /**
     * Always read-only at this step, regardless of canEdit.
     * e.g. ["inherentRisk", "riskOwner"] — set by step UI override.
     */
    private List<String> readOnlyFields;

    /** Completely hidden from this user at this step. */
    private List<String> hiddenFields;

    // ── New: tab/panel visibility ─────────────────────────────────────────────

    /**
     * When non-empty: ONLY these tab keys are shown.
     * Null/empty = all tabs shown (filtered by blueprint capabilities).
     */
    private List<String> visibleTabs;

    /** Explicitly hidden tabs. Applied on top of visibleTabs. */
    private List<String> hiddenTabs;

    // ── New: action buttons ───────────────────────────────────────────────────

    /**
     * Workflow task actions available to this user at this step.
     * Subset of: APPROVE, REJECT, SEND_BACK, REASSIGN, DELEGATE, ESCALATE, COMMENT, WITHDRAW
     * Empty/null = no workflow actions (OBSERVER, COMPLETED, DENIED modes).
     */
    private List<String> availableActions;

    // ── New: resolved permission set ──────────────────────────────────────────

    /**
     * Fully resolved permission codes for this user.
     * Merges: role_permissions → permission_grants → user_permission_overrides.
     * Frontend uses this for PermissionGate checks instead of the JWT claim,
     * because the JWT is issued at login and doesn't reflect mid-session
     * role changes or user overrides.
     *
     * e.g. ["risk.view", "risk.edit", "risk.create", "vendor.view"]
     */
    private List<String> permissions;

    // ── New: SoD violations ───────────────────────────────────────────────────

    /**
     * SoD conflicts detected for this user on this workflow instance.
     * HARD conflicts block action buttons on the frontend.
     * SOFT conflicts show a warning; user must document an exception to proceed.
     */
    private List<SodViolation> sodViolations;

    // ── Nested types ──────────────────────────────────────────────────────────

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SodViolation {
        /** Human-readable rule name: "Risk creator cannot approve own risk" */
        String ruleName;
        /** The permission the user holds that conflicts: "risk.approve" */
        String conflictingPermission;
        /** HARD = block actions. SOFT = warn + require exception. */
        String conflictType;
        /** Message shown to the user in the banner */
        String message;
    }

    // ── Existing factory methods (unchanged) ──────────────────────────────────

    public static AccessContext edit(TaskRole taskRole, String stepStatus, String workflowStatus) {
        return AccessContext.builder()
                .mode("EDIT")
                .canView(true).canEdit(true).canAct(true)
                .taskRole(taskRole)
                .stepStatus(stepStatus)
                .workflowStatus(workflowStatus)
                .build();
    }

    public static AccessContext observer(String reason, String stepStatus, String workflowStatus) {
        return AccessContext.builder()
                .mode("OBSERVER")
                .canView(true).canEdit(false).canAct(false)
                .stepStatus(stepStatus)
                .workflowStatus(workflowStatus)
                .reason(reason)
                .build();
    }

    public static AccessContext completed(String reason, String stepStatus, String workflowStatus) {
        return AccessContext.builder()
                .mode("COMPLETED")
                .canView(true).canEdit(false).canAct(false)
                .stepStatus(stepStatus)
                .workflowStatus(workflowStatus)
                .reason(reason)
                .build();
    }

    public static AccessContext denied() {
        return AccessContext.builder()
                .mode("DENIED")
                .canView(false).canEdit(false).canAct(false)
                .build();
    }
}