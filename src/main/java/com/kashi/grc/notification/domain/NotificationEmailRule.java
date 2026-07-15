package com.kashi.grc.notification.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * Maps a notification eventKey to zero-or-more email templates.
 *
 * WHY A SEPARATE RULE TABLE (not a column on notification_templates):
 *   One event can fire MULTIPLE emails — e.g. TASK_ASSIGNMENT could send
 *   both "task-assigned" (to the assignee) and a manager digest template.
 *   Multiple active rows for the same event_key = multiple templates fired
 *   per event, each to ALL recipients of that event.
 *
 * RESOLUTION ORDER (NotificationEmailConsumer):
 *   1. Tenant-specific rules (tenant_id = event tenant) — if ANY exist,
 *      they fully replace the global set for that eventKey.
 *   2. Global rules (tenant_id IS NULL).
 *   3. NO rules at all → raw fallback email is still sent (subject derived
 *      from the eventKey, body = the in-app notification message). Every
 *      event emails by default; add rules later to curate.
 *
 * SUPPRESSION (the review lever):
 *   A single active row with suppressEmail=true mutes ALL email for that
 *   eventKey (including the raw fallback) — no code change, no redeploy.
 *   Use this after reviewing which of the ~25 event types are too noisy.
 *
 * templateName references email_template.name (soft reference by name —
 * same convention MailService already uses). NULL templateName + suppress
 * false is invalid and treated as "no rule" (fallback applies).
 *
 * Table is created automatically by ddl-auto=update.
 */
@Entity
@Table(name = "notification_email_rules",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_notif_email_rule",
                columnNames = {"event_key", "template_name", "tenant_id", "audience"}),
        indexes = @Index(name = "idx_notif_email_rule_key", columnList = "event_key"))
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class NotificationEmailRule extends BaseEntity {

    /**
     * Who receives this template when the event fires:
     *   RECIPIENT — the affected users carried in recipientUserIds
     *               (assignee, mentioned user, reviewer, ...)
     *   ACTOR     — the user who performed the action (envelope.actorUserId):
     *               commenter, assigner, initiator. Lets one event send
     *               "X commented on your item" to recipients AND
     *               "your comment was posted" to the commenter, from two rows.
     * Raw fallback (no rules) always targets RECIPIENT only — actors don't
     * need generic echoes of their own actions.
     */
    public enum Audience { RECIPIENT, ACTOR }

    /** Notification eventKey this rule applies to: 'TASK_ASSIGNMENT', 'NEW_COMMENT', ... */
    @Column(name = "event_key", nullable = false, length = 100)
    private String eventKey;

    /**
     * NULL = global platform rule.
     * Non-null = tenant override — if a tenant has ANY rules for an eventKey,
     * global rules for that eventKey are ignored for that tenant.
     */
    @Column(name = "tenant_id")
    private Long tenantId;

    /** email_template.name to render. NULL only meaningful with suppressEmail=true. */
    @Column(name = "template_name", length = 100)
    private String templateName;

    @Enumerated(EnumType.STRING)
    @Column(name = "audience", nullable = false, length = 12)
    @Builder.Default
    private Audience audience = Audience.RECIPIENT;

    /** true → NO email at all for this eventKey (kills the raw fallback too). */
    @Column(name = "suppress_email", nullable = false)
    @Builder.Default
    private boolean suppressEmail = false;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;
}
