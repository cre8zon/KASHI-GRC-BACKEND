package com.kashi.grc.common.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Email history — one row per email event processed by EmailEventConsumer.
 *
 * Serves two purposes:
 *  1. HISTORY: queryable audit trail of every email the platform sent
 *     (who, when, which template, success/failure + error).
 *  2. IDEMPOTENCY: Kafka is at-least-once — the consumer may receive the
 *     same event twice (rebalance, crash after send but before offset
 *     commit). The unique eventId lets the consumer detect "already SENT"
 *     and skip, so a customer never gets a duplicate email.
 *
 * Table is created automatically by ddl-auto=update.
 */
@Entity
@Table(name = "email_log",
        uniqueConstraints = @UniqueConstraint(name = "uk_email_log_event_id", columnNames = "event_id"),
        indexes = {
                @Index(name = "idx_email_log_tenant",    columnList = "tenant_id"),
                @Index(name = "idx_email_log_recipient", columnList = "recipient"),
                @Index(name = "idx_email_log_source",    columnList = "source_event_id")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailLog {

    public enum Status { SENT, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Envelope eventId — idempotency key. */
    @Column(name = "event_id", nullable = false, length = 36)
    private String eventId;

    /**
     * Lineage: eventId of the ORIGINATING event when this email was born
     * from another Kafka event (e.g. the NOTIFICATION_EMAIL_REQUESTED
     * fanout). NULL for direct sends. Join notification_dispatch_log
     * .event_id = email_log.source_event_id for "every email this
     * notification produced" — the delivery-audit query.
     */
    @Column(name = "source_event_id", length = 36)
    private String sourceEventId;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "recipient", nullable = false, length = 320)
    private String recipient;

    /** Template used, null for raw (pre-rendered) emails. */
    @Column(name = "template_name", length = 100)
    private String templateName;

    @Column(name = "subject", length = 500)
    private String subject;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private Status status;

    /** Last error message on failure — truncated to fit. */
    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    /** Delivery attempts (1 on first success, increments across retries). */
    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}