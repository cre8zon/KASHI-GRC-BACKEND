package com.kashi.grc.uiconfig.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * In-app notification templates (parallel to emailtemplate).
 * title_template and body_template support {{placeholder}} syntax.
 * actionUrl supports {{entityId}}, {{taskId}}, etc.
 * Change notification text = update one row.
 */
@Entity
@Table(name = "notification_templates")
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class NotificationTemplate extends BaseEntity {

    /** Event key that triggers this notification: 'TASK_ASSIGNED', 'VENDOR_APPROVED' */
    @Column(name = "event_key", unique = true, nullable = false, length = 100)
    private String eventKey;

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
