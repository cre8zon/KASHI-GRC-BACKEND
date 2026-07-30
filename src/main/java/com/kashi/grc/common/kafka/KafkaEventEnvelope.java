package com.kashi.grc.common.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * Universal envelope for every message KashiGRC publishes to Kafka.
 *
 * WHY AN ENVELOPE:
 *  - Kafka consumers run on listener threads with NO HTTP request context.
 *    The X-Tenant-ID header (and therefore TenantContext) does not exist there.
 *    tenantId travels inside the message so consumers can re-establish it.
 *  - eventId gives consumers an idempotency key (Kafka is at-least-once:
 *    a consumer that crashes after processing but before committing the
 *    offset WILL see the same message again).
 *  - One concrete class (payload as Map) means a single deserializer target
 *    for every topic — no per-topic type-mapping configuration.
 *
 * Typed access to the payload:
 *   EmailRequestedPayload p = envelope.payloadAs(EmailRequestedPayload.class, objectMapper);
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KafkaEventEnvelope {

    /** UUID — idempotency / de-duplication key. */
    private String eventId;

    /** Business event type, e.g. "EMAIL_REQUESTED", "TASK_ASSIGNED". */
    private String eventType;

    /** Tenant that owns this event. Consumers MUST set TenantContext from this. */
    private Long tenantId;

    /** User who triggered the event (nullable for SYSTEM-originated events). */
    private Long actorUserId;

    /** When the event occurred (producer-side clock, UTC). */
    private Instant occurredAt;

    /** Free-form event data. Keys/shape defined per eventType. */
    private Map<String, Object> payload;

    /** Convert the raw payload map into a typed DTO. */
    public <T> T payloadAs(Class<T> type, ObjectMapper mapper) {
        return mapper.convertValue(payload, type);
    }
}
