package com.kashi.grc.uiconfig.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * Action buttons that appear on screens/detail pages.
 * The backend decides which actions are available based on
 * user permissions, role side, and entity status.
 * Add "Request More Info" to vendor detail = insert one row.
 *
 * FIX (2026-05-15): requiresConfirmation, requiresRemarks, isActive changed from
 * primitive boolean → Boolean wrapper so Hibernate can load legacy rows that have
 * NULL in those columns without throwing PropertyAccessException.
 *
 * Run this once to backfill existing NULLs if desired:
 *   UPDATE ui_actions SET requires_confirmation = 0 WHERE requires_confirmation IS NULL;
 *   UPDATE ui_actions SET requires_remarks      = 0 WHERE requires_remarks      IS NULL;
 *   UPDATE ui_actions SET is_active             = 1 WHERE is_active             IS NULL;
 */
@Entity
@Table(name = "ui_actions")
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class UiAction extends BaseEntity {

    /** Screen this action appears on. e.g. 'vendor_detail', 'task_inbox' */
    @Column(name = "screen_key", nullable = false, length = 100)
    private String screenKey;

    /** Unique action identifier. e.g. 'approve', 'reject', 'export_pdf' */
    @Column(name = "action_key", nullable = false, length = 100)
    private String actionKey;

    @Column(name = "label", nullable = false, length = 255)
    private String label;

    /** Lucide icon name */
    @Column(name = "icon", length = 100)
    private String icon;

    /** 'primary', 'danger', 'secondary', 'ghost', 'warning' */
    @Column(name = "variant", length = 50)
    @Builder.Default
    private String variant = "primary";

    /** API endpoint template. Use {id} for entity id. e.g. '/v1/workflows/tasks/{id}/act' */
    @Column(name = "api_endpoint", length = 255)
    private String apiEndpoint;

    @Column(name = "http_method", length = 10)
    @Builder.Default
    private String httpMethod = "POST";

    /**
     * Static request body to merge with dynamic data.
     * JSON: {"action": "APPROVE"} — frontend adds taskId etc.
     */
    @Column(name = "payload_template_json", columnDefinition = "JSON")
    private String payloadTemplateJson;

    /** Permission code required to see this action. NULL = no check. */
    @Column(name = "required_permission", length = 255)
    private String requiredPermission;

    /** Role sides that can see this action. NULL = all. Comma-separated. */
    @Column(name = "allowed_sides", length = 255)
    private String allowedSides;

    /**
     * Workflow step actions this action belongs to. NULL = any step.
     * Comma-separated with OR semantics, exactly like allowed_sides:
     * 'ASSIGN' or 'ASSIGN,REVIEW'.
     *
     * ── WHY requires_section_gate WAS NOT ENOUGH ──────────────────────────
     * sql/86 added requires_section_gate to stop "Confirm assignments" showing
     * on steps where there is no gate to close. It asks the wrong question:
     * "does this step have sections?" — and BOTH the assign step and the fill
     * step have them. So the button went on appearing on "Responders Fill
     * Questionnaires", where the responder's completion gesture is submitting
     * their sections and nothing needs confirming. A control that can only
     * fail teaches people the whole row is decorative.
     *
     * This asks the question that was actually meant: which step is this. The
     * two are kept separate rather than merged, because they are independent —
     * an action can want a sectioned step without caring which one, and a
     * step-scoped action can exist on a step with no sections at all.
     *
     * NULL is "any", so every existing row behaves exactly as it does today
     * and this is additive by construction.
     */
    @Column(name = "allowed_step_actions", length = 255)
    private String allowedStepActions;

    /**
     * The blueprint section key this action closes — the gate it exists to fire.
     *
     * The third and most precise scope, after allowed_sides and
     * allowed_step_actions. Set it and the action appears ONLY on a step whose
     * task still owes that section, and disappears the moment the gate closes.
     *
     * ── WHY THE OTHER TWO WERE NOT ENOUGH ─────────────────────────────────
     *
     * Workflow 12 has three separate REVIEW steps — "Responders Review and
     * Publish Answers", "Vendor CISO Final Review and Submission" and
     * "Reviewers Consolidate Findings". allowed_step_actions = 'REVIEW' would
     * put all of their buttons on all three. nav_key cannot separate them
     * either: six nav keys cover thirteen steps.
     *
     * The section key can, because it IS the gate: PUBLISH_SECTION,
     * CONSOLIDATE_SCORES, ASSIGN_RISK_RATING. It is a blueprint key rather than
     * a row id, so it survives a cloned or renumbered workflow, and it is
     * already the thing the action's endpoint fires through
     * TaskSectionEvent.sectionDone.
     *
     * ── AND WHY NOT THE GENERIC COMPLETE ENDPOINT ─────────────────────────
     *
     * POST /v1/compound-tasks/{taskId}/sections/{key}/complete fires any gate
     * by name, which looks like it removes the need for these actions
     * altogether. It does not: the endpoints behind them do domain work the
     * generic one cannot. /risk-rating sets the rating and recomputes the
     * score, /consolidate-scores computes it, /document-findings writes the
     * findings text. Firing the gate without that would close a step with no
     * rating assigned.
     *
     * NULL means "not tied to a gate", which is every row that predates this
     * column — so this is additive and nothing else moves.
     *
     * Matched against AccessContext.openSectionKeys, which is why that field
     * exists. Exactly one key per action: an action that closed two gates would
     * have no honest answer for when to hide.
     */
    @Column(name = "completes_section_key", length = 100)
    private String completesSectionKey;

    /**
     * Entity must be in one of these statuses for action to appear.
     * JSON array: ["PENDING", "IN_PROGRESS"] — NULL = always show.
     */
    @Column(name = "allowed_statuses_json", columnDefinition = "JSON")
    private String allowedStatusesJson;

    /**
     * Show a confirmation dialog before executing?
     * FIX: Boolean (wrapper) instead of boolean (primitive) — allows NULL in existing DB rows.
     * Lombok generates getRequiresConfirmation() for this field.
     */
    @Column(name = "requires_confirmation")
    @Builder.Default
    private Boolean requiresConfirmation = false;

    /** When true, action is only available to users assigned to this specific entity instance.
     *  Frontend checks entity.isAssignedToCurrentUser (returned by GET endpoint).
     *  No hardcoding of action keys — set per-action in ui_actions table. */
    /**
     * This action fires a workflow SECTION-completion event, so it only makes
     * sense on a step that HAS section gates.
     *
     * "Confirm assignments" was showing on every step the user held a task on,
     * including "Responders Fill Questionnaires" — where there is no gate to
     * close, the step auto-completes on submit, and the button could only
     * confuse. Filtering by action key in the frontend would have worked and
     * would have put two action names in the page's source; this is the same
     * rule as data.
     *
     * The frontend hides such an action unless viewContext.hasSections is true.
     * Default false, so every existing action behaves exactly as before.
     */
    @Column(name = "requires_section_gate")
    @Builder.Default
    private Boolean requiresSectionGate = false;

    @Column(name = "requires_assignment")
    @Builder.Default
    private Boolean requiresAssignment = false;

    @Column(name = "confirmation_message", columnDefinition = "TEXT")
    private String confirmationMessage;

    /**
     * Does this action require a remarks/comment input?
     * FIX: Boolean (wrapper) instead of boolean (primitive) — allows NULL in existing DB rows.
     * Lombok generates getRequiresRemarks() for this field.
     */
    @Column(name = "requires_remarks")
    @Builder.Default
    private Boolean requiresRemarks = false;

    @Column(name = "sort_order")
    @Builder.Default
    private Integer sortOrder = 0;

    /**
     * FIX: Boolean (wrapper) instead of boolean (primitive) — allows NULL in existing DB rows.
     * Lombok generates isActive() for fields named isXxx with Boolean type (Lombok 1.18+).
     */
    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "tenant_id")
    private Long tenantId;
}