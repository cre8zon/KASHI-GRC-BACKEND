package com.kashi.grc.uiconfig.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * In-app notification templates (parallel to emailtemplate).
 * title_template and body_template support {{placeholder}} syntax.
 * actionUrl supports {{entityId}}, {{taskId}}, etc.
 * Change notification text = update one row.
 *
 * NEW: tenantId — allows per-tenant template overrides.
 *   NULL tenantId = global platform default.
 *   Non-null tenantId = tenant-specific override (takes precedence over global).
 *
 * CHANGED: eventKey uniqueness is now per-tenant (not globally unique),
 *   because two tenants can both have a TASK_ASSIGNED template.
 *   Replaced the @Column(unique=true) with a composite unique constraint.
 *
 * MIGRATION (run once):
 *   -- Remove old unique index on event_key (if it exists)
 *   ALTER TABLE notification_templates
 *     DROP INDEX IF EXISTS UK_event_key,
 *     ADD COLUMN tenant_id BIGINT NULL,
 *     ADD UNIQUE INDEX uk_notif_tmpl_eventkey_tenant (event_key, tenant_id);
 */
@Entity
@Table(
        name = "notification_templates",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_notif_tmpl_eventkey_tenant",
                columnNames = {"event_key", "tenant_id"}
        )
)
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class NotificationTemplate extends BaseEntity {

    /** Event key that triggers this notification: 'TASK_ASSIGNED', 'VENDOR_APPROVED' */
    @Column(name = "event_key", nullable = false, length = 100)
    private String eventKey;

    /**
     * NULL = global platform template.
     * Non-null = tenant-specific override — loaded by TemplateNotificationService
     * with tenant-first lookup (tenant-specific takes precedence).
     */
    @Column(name = "tenant_id")
    private Long tenantId;

    /** Title with placeholders: 'Task assigned: {{stepName}}' */
    @Column(name = "title_template", nullable = false, length = 255)
    private String titleTemplate;

    /** Body with placeholders: 'Vendor {{vendorName}} requires your review.' */
    @Column(name = "body_template", columnDefinition = "TEXT")
    private String bodyTemplate;

    /** Lucide icon name shown in notification bell */
    @Column(name = "icon", length = 100)
    @Builder.Default
    private String icon = "Bell";

    /** Semantic color for the notification: 'blue', 'amber', 'red', 'green' */
    @Column(name = "color_tag", length = 30)
    @Builder.Default
    private String colorTag = "blue";

    /** Deeplink route template: '/workflow/tasks/{{taskId}}' */
    @Column(name = "action_url", length = 500)
    private String actionUrl;

    @Column(name = "is_active")
    @Builder.Default
    private boolean isActive = true;
}