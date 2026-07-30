package com.kashi.grc.notification.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Per-user notification preferences — the opt-out layer of the email pipeline.
 *
 * MODEL:
 *   One row per (userId, eventKey). eventKey uses the sentinel "ALL" for the
 *   user's global default instead of NULL — MySQL unique indexes allow
 *   multiple NULLs, which would permit duplicate global rows.
 *
 * RESOLUTION (NotificationEmailConsumer.emailAllowed):
 *   specific (userId, eventKey) row → wins
 *   else (userId, "ALL") row        → applies
 *   else                            → enabled (no row = default on)
 *
 * inAppEnabled is stored but NOT yet enforced — the in-app bell is the
 * system of record for "what happened" and stays always-on for now.
 * Enforcing it later is a one-line check in NotificationService.saveNotification;
 * the schema and UI contract are already future-proof.
 *
 * DIGEST mode (instant vs daily rollup) is deliberately absent from v1 —
 * it needs a scheduler + aggregation table and will arrive with the
 * digest consumer group on the same topic.
 *
 * Table is created automatically by ddl-auto=update.
 */
@Entity
@Table(name = "user_notification_preferences",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_notif_pref", columnNames = {"user_id", "event_key"}),
        indexes = @Index(name = "idx_user_notif_pref_user", columnList = "user_id"))
@Getter @Setter @Builder
@NoArgsConstructor @AllArgsConstructor
public class UserNotificationPreference {

    /** Sentinel eventKey meaning "the user's default for all events". */
    public static final String ALL_EVENTS = "ALL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Notification eventKey ('TASK_ASSIGNMENT', 'NEW_COMMENT', ...) or "ALL". */
    @Column(name = "event_key", nullable = false, length = 100)
    private String eventKey;

    @Column(name = "email_enabled", nullable = false)
    @Builder.Default
    private boolean emailEnabled = true;

    /** Stored for UI symmetry; not yet enforced (see class javadoc). */
    @Column(name = "in_app_enabled", nullable = false)
    @Builder.Default
    private boolean inAppEnabled = true;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
