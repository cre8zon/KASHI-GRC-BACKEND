package com.kashi.grc.common.kafka.test;

import com.kashi.grc.common.kafka.KafkaEventPublisher;
import com.kashi.grc.common.kafka.KafkaTopics;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

/**
 * Temporary round-trip test endpoint. DELETE once real topics are wired.
 *
 * Requires normal auth (JWT + X-Tenant-ID header), same as every other
 * endpoint — which is exactly what we want: it proves the tenant ID flows
 * from the HTTP header → TenantContext → envelope → consumer.
 *
 * Test with:
 *   POST /v1/kafka-test/publish?message=hello
 *   (Authorization: Bearer <jwt>, X-Tenant-ID: 4)
 */
@RestController
@RequestMapping("/v1/kafka-test")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "kashi.kafka.enabled", havingValue = "true")
public class KafkaTestController {

    private final KafkaEventPublisher publisher;

    @PostMapping("/publish")
    public Map<String, Object> publish(@RequestParam(defaultValue = "hello kashigrc") String message) {
        publisher.publish(
                KafkaTopics.TEST,
                "PING",
                null,                                   // no ordering needed → round-robin
                Map.of("message", message, "sentAt", Instant.now().toString()));

        return Map.of(
                "status", "published",
                "topic", KafkaTopics.TEST,
                "hint", "check application logs for 'KAFKA ROUND-TRIP OK'");
    }
}
