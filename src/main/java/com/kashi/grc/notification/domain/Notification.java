package com.kashi.grc.notification.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * In-app notification record.
 *
 * NEW fields (additive — legacy `message` column kept for backward compat):
 *   title     — short headline rendered in the notification bell dropdown
 *   body      — longer description (replaces message for template-driven notifications)
 *   icon      — Lucide icon name: "Bell", "AlertTriangle", "CheckCircle2", etc.
 *   colorTag  — semantic color: "blue", "amber", "red", "green"
 *   actionUrl — deep-link route: "/workflow/tasks/42", "/tprm/vendors/7"
 *
 * The legacy NotificationService.send() still populates only `message` — all new
 * fields remain null. TemplateNotificationService.send() populates all fields.
 * Both flows coexist without breaking existing callers.
 *
 * MIGRATION (run once):
 *   ALTER TABLE notifications
 *     ADD COLUMN title      VARCHAR(255) NULL,
 *     ADD COLUMN body       TEXT         NULL,
 *     ADD COLUMN icon       VARCHAR(100) NULL,
 *     ADD COLUMN color_tag  VARCHAR(30)  NULL,
 *     ADD COLUMN action_url VARCHAR(500) NULL;
 */
@Entity
@Table(name = "notifications")
@Getter @Setter @lombok.experimental.SuperBuilder @NoArgsConstructor @AllArgsConstructor
public class Notification extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "type", nullable = false)
    private String type; // ASSIGNMENT, SUBMISSION, REVIEW, ESCALATION

    /**
     * Legacy plain-text message — still populated by the original NotificationService.send().
     * New code should use title + body instead.
     */
    @Column(name = "message", nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(name = "entity_type")
    private String entityType; // TASK, ASSESSMENT, VENDOR

    @Column(name = "entity_id")
    private Long entityId;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    // ── NEW template-driven fields ────────────────────────────────────────────

    /** Short headline for the notification bell: "Task assigned: Vendor Fill" */
    @Column(name = "title", length = 255)
    private String title;

    /** Longer body text resolved from NotificationTemplate.bodyTemplate */
    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

    /** Lucide icon name. e.g. "Bell", "AlertTriangle", "CheckCircle2" */
    @Column(name = "icon", length = 100)
    private String icon;

    /** Semantic color for the notification dot/badge: "blue", "amber", "red", "green" */
    @Column(name = "color_tag", length = 30)
    private String colorTag;

    /** Deep-link route: "/workflow/tasks/42", "/tprm/vendors/7/assessments/3" */
    @Column(name = "action_url", length = 500)
    private String actionUrl;
}