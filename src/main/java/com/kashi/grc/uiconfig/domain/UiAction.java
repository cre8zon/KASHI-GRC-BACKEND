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