package com.kashi.grc.common.kafka;

import com.kashi.grc.common.config.multitenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The ONLY class that should touch KafkaTemplate directly.
 *
 * Producing an event from any service is one line:
 *
 *   kafkaEventPublisher.publish(KafkaTopics.TEST, "PING",
 *           String.valueOf(userId), Map.of("hello", "world"));
 *
 * The envelope is stamped automatically:
 *   - eventId     → random UUID
 *   - tenantId    → TenantContext.getCurrentTenant() (the HTTP request thread
 *                   still has it — this is why producing must happen on the
 *                   request thread, BEFORE any async handoff)
 *   - occurredAt  → Instant.now()
 *
 * KEY CHOICE: the Kafka message key controls partition assignment.
 * Messages with the same key land on the same partition and are therefore
 * consumed IN ORDER. Use the entity that needs ordering as the key
 * (e.g. workflowInstanceId for workflow events, userId for notifications).
 * Pass null if ordering doesn't matter — Kafka will round-robin.
 *
 * Send is async and non-blocking; failures are logged with full context.
 * For events where loss is unacceptable (audit trail), we will add an
 * outbox pattern later — not needed for the foundation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "kashi.kafka.enabled", havingValue = "true")
public class KafkaEventPublisher {

    private final KafkaTemplate<String, KafkaEventEnvelope> kafkaTemplate;

    /** Publish with tenant taken from the current request's TenantContext. */
    public void publish(String topic, String eventType, String key, Map<String, Object> payload) {
        publish(topic, eventType, key, payload, TenantContext.getCurrentTenant(), null);
    }

    /** Publish with explicit tenant/actor — for schedulers, seeds, and consumers
     *  that re-publish (no request thread → no TenantContext). */
    public void publish(String topic, String eventType, String key,
                        Map<String, Object> payload, Long tenantId, Long actorUserId) {

        KafkaEventEnvelope envelope = KafkaEventEnvelope.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType(eventType)
                .tenantId(tenantId)
                .actorUserId(actorUserId)
                .occurredAt(Instant.now())
                .payload(payload)
                .build();

        // CONTRACT: publish() NEVER throws. send() is async for delivery but
        // can throw SYNCHRONOUSLY (metadata timeout when broker is down —
        // capped at max.block.ms — serialization failure, lazy producer
        // construction with bad config). Callers are business flows (comment
        // save, task assignment, afterCommit hooks): a broken broker must
        // degrade to "event lost + ERROR log", never to a failed user request.
        // Loss-unacceptable events get the outbox pattern later, not throws.
        try {
            kafkaTemplate.send(topic, key, envelope).whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Kafka publish FAILED topic={} eventType={} eventId={} tenant={}: {}",
                            topic, eventType, envelope.getEventId(), tenantId, ex.getMessage());
                } else if (log.isDebugEnabled()) {
                    log.debug("Kafka published topic={} partition={} offset={} eventType={} eventId={}",
                            topic,
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset(),
                            eventType, envelope.getEventId());
                }
            });
        } catch (Exception e) {
            log.error("Kafka publish FAILED (synchronous) topic={} eventType={} eventId={} tenant={}: {}",
                    topic, eventType, envelope.getEventId(), tenantId, e.getMessage());
        }
    }
}