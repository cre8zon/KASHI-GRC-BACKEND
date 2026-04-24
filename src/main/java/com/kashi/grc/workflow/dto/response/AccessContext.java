package com.kashi.grc.workflow.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kashi.grc.workflow.enums.TaskRole;
import lombok.Builder;
import lombok.Data;

/**
 * Describes exactly what the current user can do on a workflow page.
 *
 * Returned by GET /v1/workflow-instances/tasks/access-context
 * Called by every workflow page on mount — replaces scattered task-gate
 * useEffect logic with a single consistent hook.
 *
 * ── MODES ────────────────────────────────────────────────────────────────────
 *
 *   EDIT      — user has an active PENDING task on this step.
 *               canEdit=true, canAct=true. Full interactive form + action buttons.
 *
 *   OBSERVER  — user holds an observer role on this step but has no task.
 *               canEdit=false, canAct=false. Read-only form, observer banner shown.
 *
 *   COMPLETED — step is APPROVED/REJECTED, or workflow is terminal.
 *               canEdit=false, canAct=false. Read-only form, completion info shown.
 *
 *   DENIED    — user has no relationship to this step.
 *               canView=false. Frontend redirects to inbox.
 *
 * ── USAGE ────────────────────────────────────────────────────────────────────
 *
 *   Every workflow page:
 *     const { data: access } = useAccessContext(taskId, stepInstanceId)
 *     if (!access?.canView) return <Navigate to="/workflow/inbox" />
 *     <MyForm mode={access.mode} canAct={access.canAct} />
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AccessContext {

    /**
     * EDIT | OBSERVER | COMPLETED | DENIED
     * Frontend uses this to select the render mode for the page.
     */
    private String  mode;

    /** True if user may load and view the page at all. False → redirect to inbox. */
    private boolean canView;

    /** True if form fields are editable. False → all inputs disabled. */
    private boolean canEdit;

    /** True if action buttons (Approve / Reject / Delegate) are shown. */
    private boolean canAct;

    /** ACTOR | ASSIGNER | null (for observer/completed) */
    private TaskRole taskRole;

    /** Current step status: IN_PROGRESS | APPROVED | REJECTED | AWAITING_ASSIGNMENT */
    private String  stepStatus;

    /** Current workflow status: IN_PROGRESS | COMPLETED | CANCELLED | REJECTED */
    private String  workflowStatus;

    /**
     * Human-readable explanation of the access decision.
     * Shown in the UI for OBSERVER and COMPLETED modes.
     * e.g. "Step completed on 11 Apr 2026" or "Observer access via VENDOR_VRM role"
     */
    private String  reason;

    // ── Factory methods ───────────────────────────────────────────────────────

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