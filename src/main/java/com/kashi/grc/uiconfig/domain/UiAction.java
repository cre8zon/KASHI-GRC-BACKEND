package com.kashi.grc.uiconfig.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * Action buttons that appear on screens/detail pages.
 * The backend decides which actions are available based on
 * user permissions, role side, and entity status.
 * Add "Request More Info" to vendor detail = insert one row.
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

    /** Show a confirmation dialog before executing? */
    @Column(name = "requires_confirmation")
    @Builder.Default
    private boolean requiresConfirmation = false;

    @Column(name = "confirmation_message", columnDefinition = "TEXT")
    private String confirmationMessage;

    /** Does this action require a remarks/comment input? */
    @Column(name = "requires_remarks")
    @Builder.Default
    private boolean requiresRemarks = false;

    @Column(name = "sort_order")
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "is_active")
    @Builder.Default
    private boolean isActive = true;

    @Column(name = "tenant_id")
    private Long tenantId;
}
