package com.kashi.grc.common.kafka.test;

import com.kashi.grc.common.config.multitenancy.TenantContext;
import com.kashi.grc.common.kafka.KafkaEventEnvelope;
import com.kashi.grc.common.kafka.KafkaTopics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Round-trip verification listener. DELETE once real consumers exist.
 *
 * This class is the TEMPLATE for every future consumer in KashiGRC.
 * The try/finally tenant-context block is MANDATORY:
 *
 *  - Listener threads have no HTTP request → TenantInterceptor never ran →
 *    TenantContext is empty. Any repository call that relies on it would
 *    read/write the wrong tenant's data (or NPE).
 *  - Listener threads are POOLED and REUSED. Without clear() in finally,
 *    tenant 4's ID leaks into the next message's processing — a cross-tenant
 *    data breach in a GRC product. Non-negotiable.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "kashi.kafka.enabled", havingValue = "true")
public class KafkaTestListener {

    @KafkaListener(topics = KafkaTopics.TEST, containerFactory = "kafkaListenerContainerFactory")
    public void onTestEvent(KafkaEventEnvelope envelope,
                            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                            @Header(KafkaHeaders.OFFSET) long offset) {
        try {
            // 1. Re-establish tenant context from the envelope — ALWAYS first
            if (envelope.getTenantId() != null) {
                TenantContext.setCurrentTenant(envelope.getTenantId());
            }

            // 2. Actual work (here: just prove the round trip)
            log.info("KAFKA ROUND-TRIP OK — partition={} offset={} eventId={} eventType={} tenant={} payload={}",
                    partition, offset,
                    envelope.getEventId(), envelope.getEventType(),
                    envelope.getTenantId(), envelope.getPayload());

            // Uncomment to test the retry → DLT path:
            // if ("FAIL".equals(envelope.getEventType()))
            //     throw new IllegalStateException("Simulated consumer failure");

        } finally {
            // 3. ALWAYS clear — pooled threads are reused across tenants
            TenantContext.clear();
        }
    }
}
