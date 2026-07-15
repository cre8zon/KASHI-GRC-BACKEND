package com.kashi.grc.notification.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Fanout history — one row per NOTIFICATION_EMAIL_REQUESTED event processed
 * by NotificationEmailConsumer.
 *
 * TWO-LAYER IDEMPOTENCY (why this exists when email_log already does):
 *   email_log guards each INDIVIDUAL SMTP send (one row per recipient email).
 *   This table guards the FANOUT itself: Kafka is at-least-once, so on
 *   redelivery of the same notification event the consumer would otherwise
 *   re-publish N fresh email events with NEW eventIds — email_log could not
 *   catch those as duplicates. The unique event_id here short-circuits the
 *   whole fanout instead.
 *
 * Statuses:
 *   DISPATCHED — fanout completed, emails handed to the email topic
 *   SKIPPED    — nothing to do (no recipients / suppressed by rule / no emails)
 *   FAILED     — exception during fanout (record then rethrow → retries → DLT)
 *
 * Table is created automatically by ddl-auto=update.
 */
@Entity
@Table(name = "notification_dispatch_log",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_notif_dispatch_event_id", columnNames = "event_id"),
        indexes = @Index(name = "idx_notif_dispatch_tenant", columnList = "tenant_id"))
@Getter @Setter @Builder
@NoArgsConstructor @AllArgsConstructor
public class NotificationDispatchLog {

    public enum Status { DISPATCHED, SKIPPED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Envelope eventId — idempotency key for the fanout. */
    @Column(name = "event_id", nullable = false, length = 36)
    private String eventId;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "event_key", nullable = false, length = 100)
    private String eventKey;

    @Column(name = "entity_type", length = 100)
    private String entityType;

    @Column(name = "entity_id")
    private Long entityId;

    /** Recipients in the incoming event (before filtering). */
    @Column(name = "recipient_count")
    private Integer recipientCount;

    /** Individual email events actually published to kashigrc.email.requested. */
    @Column(name = "emails_dispatched")
    private Integer emailsDispatched;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private Status status;

    /** SKIPPED reason or FAILED error message (truncated). */
    @Column(name = "detail", length = 500)
    private String detail;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;
}
