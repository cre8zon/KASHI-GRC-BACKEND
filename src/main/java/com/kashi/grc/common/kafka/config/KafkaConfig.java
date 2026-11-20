package com.kashi.grc.common.kafka.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashi.grc.common.kafka.KafkaEventEnvelope;
import com.kashi.grc.common.kafka.KafkaTopics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.kafka.KafkaClientMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kafka foundation for KashiGRC.
 *
 * Gated behind kashi.kafka.enabled so environments without a broker
 * (e.g. current DigitalOcean prod) start normally with Kafka switched off.
 *
 * Design decisions:
 *  - JSON serde via the Spring-managed ObjectMapper (JavaTimeModule already
 *    registered → Instant serialises correctly).
 *  - Producer: acks=all + idempotence → no silent loss, no broker-side dupes.
 *  - Consumer: ErrorHandlingDeserializer wrapper → a malformed message goes
 *    to the error handler instead of crash-looping the container.
 *  - DefaultErrorHandler: 3 retries with exponential backoff, then the record
 *    is published to "<topic>-dlt" and the partition moves on. A poison
 *    message can never block a partition — see the recoverer below for the
 *    one thing that has to be true for that to hold.
 *  - Every message deserialises to KafkaEventEnvelope — one target type for
 *    all topics, no type-mapping headers needed.
 *  - Observability: KafkaClientMetrics bound to every producer/consumer the
 *    factories create — this is the ONLY way to get real broker-reported
 *    consumer lag (kafka.consumer.records.lag / .lag.max), not just our own
 *    processing-time counters. See KafkaConsumerMetricsAspect for per-listener
 *    processed/failed counters and KafkaEventPublisher for producer-side
 *    send counters — together these three are what make it possible to
 *    actually SEE whether Kafka is healthy in production, not just assume it.
 */
@Slf4j
@EnableKafka
@Configuration
@RequiredArgsConstructor
// Local / self-hosted broker (no SASL). For Confluent Cloud set
// kashi.kafka.provider=confluent and KafkaConfigConfluent loads instead —
// only one of the two may load, as both define the same beans.
@ConditionalOnExpression("${kashi.kafka.enabled:false} and '${kashi.kafka.provider:local}' != 'confluent'")
public class KafkaConfig {

    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    // Tracks bound KafkaClientMetrics instances by client id so they can be
    // unbound (closed) when a producer/consumer is removed — without this,
    // a consumer restart would leak meters bound to a now-dead client.
    private final Map<String, KafkaClientMetrics> boundClientMetrics = new ConcurrentHashMap<>();

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${kashi.kafka.consumer.group-id:kashigrc-core}")
    private String groupId;

    @Value("${kashi.kafka.consumer.concurrency:3}")
    private int concurrency;

    /**
     * This class talks plain text. Pointed at Confluent Cloud (TLS + SASL) the
     * client reads the TLS handshake bytes as a message length, allocates a
     * buffer that size and dies with "OutOfMemoryError: Java heap space", which
     * stops every listener container. Refuse to start instead, with the fix.
     */
    @jakarta.annotation.PostConstruct
    void refuseConfluentWithoutSasl() {
        if (bootstrapServers != null && bootstrapServers.contains("confluent.cloud")) {
            throw new IllegalStateException(
                    "spring.kafka.bootstrap-servers points at Confluent Cloud (" + bootstrapServers
                            + ") but kashi.kafka.provider is not 'confluent', so the plain-text KafkaConfig loaded. "
                            + "Set kashi.kafka.provider=confluent and spring.kafka.properties.sasl.jaas.config "
                            + "so KafkaConfigConfluent loads instead.");
        }
    }

    // ── Producer ─────────────────────────────────────────────────────

    @Bean
    public ProducerFactory<String, KafkaEventEnvelope> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5);          // tiny batching win
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 15_000);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30_000);
        // How long send() may BLOCK the calling thread on metadata fetch /
        // full buffer. Default is 60s — a dead broker would hang request
        // threads for a minute each and exhaust the Tomcat pool. 3s = fail
        // fast; the failure is then swallowed+logged by KafkaEventPublisher.
        // (Separate knob from the delivery >= linger + request constraint.)
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 3_000);

        DefaultKafkaProducerFactory<String, KafkaEventEnvelope> factory =
                new DefaultKafkaProducerFactory<>(props);
        factory.setKeySerializer(new StringSerializer());
        factory.setValueSerializer(new JsonSerializer<>(objectMapper));

        // Real broker-reported producer metrics (request-latency-avg,
        // outgoing-byte-rate, etc.) — genuinely from the Kafka client's own
        // metric registry, not something we're computing ourselves.
        factory.addListener(new ProducerFactory.Listener<String, KafkaEventEnvelope>() {
            @Override
            public void producerAdded(String id, Producer<String, KafkaEventEnvelope> producer) {
                bindClientMetrics(id, new KafkaClientMetrics(producer));
            }

            @Override
            public void producerRemoved(String id, Producer<String, KafkaEventEnvelope> producer) {
                unbindClientMetrics(id);
            }
        });

        return factory;
    }

    @Bean
    public KafkaTemplate<String, KafkaEventEnvelope> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    // ── Consumer ─────────────────────────────────────────────────────

    @Bean
    public ConsumerFactory<String, KafkaEventEnvelope> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100);

        JsonDeserializer<KafkaEventEnvelope> json =
                new JsonDeserializer<>(KafkaEventEnvelope.class, objectMapper);
        json.addTrustedPackages("com.kashi.grc.*");

        DefaultKafkaConsumerFactory<String, KafkaEventEnvelope> factory = new DefaultKafkaConsumerFactory<>(
                props,
                new ErrorHandlingDeserializer<>(new StringDeserializer()),
                new ErrorHandlingDeserializer<>(json));

        // Real broker-reported consumer metrics — critically, records-lag /
        // records-lag-max, i.e. actual consumer lag straight from the Kafka
        // client's own metric registry. This is the one metric that answers
        // "is a consumer falling behind" that nothing else in this app
        // (our own processed/failed counters) can provide — those measure
        // how fast we process what we've polled, not how far behind the
        // latest offset we are.
        factory.addListener(new ConsumerFactory.Listener<String, KafkaEventEnvelope>() {
            @Override
            public void consumerAdded(String id, Consumer<String, KafkaEventEnvelope> consumer) {
                bindClientMetrics(id, new KafkaClientMetrics(consumer));
            }

            @Override
            public void consumerRemoved(String id, Consumer<String, KafkaEventEnvelope> consumer) {
                unbindClientMetrics(id);
            }
        });

        return factory;
    }

    private void bindClientMetrics(String id, KafkaClientMetrics metrics) {
        try {
            metrics.bindTo(meterRegistry);
            boundClientMetrics.put(id, metrics);
        } catch (Exception e) {
            log.warn("[KAFKA-METRICS] Failed to bind client metrics for id={} — {}", id, e.toString());
        }
    }

    private void unbindClientMetrics(String id) {
        KafkaClientMetrics metrics = boundClientMetrics.remove(id);
        if (metrics != null) {
            try {
                metrics.close();
            } catch (Exception e) {
                log.warn("[KAFKA-METRICS] Failed to unbind client metrics for id={} — {}", id, e.toString());
            }
        }
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, KafkaEventEnvelope>
    kafkaListenerContainerFactory(KafkaTemplate<String, KafkaEventEnvelope> template) {

        // Failed records -> "<originalTopic>-dlt" (Spring Kafka's actual default
        // suffix — NOT ".DLT" despite what older comments in this file assumed;
        // verified against real runtime behavior, not docs), at a partition the
        // PRODUCER picks.
        //
        // The -1 is the important part, and it is what makes the "a poison
        // message can never block a partition" promise above actually true.
        //
        // Spring Kafka's default resolver targets THE SAME PARTITION NUMBER in
        // the DLT, which silently requires every DLT to be at least as wide as
        // its source. Nothing enforces that, and partitions can be added to a
        // topic by anyone at any time and never removed, so the two drift.
        //
        // When they drifted on the Confluent side (source 6 partitions, DLT 3)
        // the result was not a lost message but a wedged partition: the publish
        // to the missing partition timed out, sendOrThrow threw, the record was
        // never recovered, the handler seeked back, and the same offset was
        // reprocessed every ~23 seconds indefinitely. Three partitions of
        // kashigrc.email.requested sat at offset 0 and never advanced.
        //
        // -1 hands the choice to the producer's partitioner, which places the
        // record by key among whatever partitions the DLT actually has. Keys are
        // preserved, so related messages still land together; they just no
        // longer depend on a partition index existing on both sides.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                template,
                (rec, ex) -> new TopicPartition(rec.topic() + "-dlt", -1));

        // Counts every time a record actually gets dead-lettered (retries
        // exhausted). This is the metric worth alerting on — a rising DLT
        // publish RATE means something is systematically broken, which is
        // more actionable than a live DLT topic depth (which would need
        // AdminClient offset polling to compute, and only tells you how big
        // the backlog is, not whether it's actively growing right now).
        org.springframework.kafka.listener.ConsumerRecordRecoverer countingRecoverer = (record, exception) -> {
            try {
                Counter.builder("kashigrc.kafka.dlt.published")
                        .tag("topic", record.topic())
                        .register(meterRegistry).increment();
            } catch (Exception e) {
                log.warn("[KAFKA-METRICS] Failed to record DLT metric for topic={} — {}",
                        record.topic(), e.toString());
            }
            recoverer.accept(record, exception);
        };

        // 3 retries: 1s → 2s → 4s, then dead-letter
        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxElapsedTime(10_000L);
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(countingRecoverer, backOff);
        errorHandler.setLogLevel(org.springframework.kafka.KafkaException.Level.WARN);

        ConcurrentKafkaListenerContainerFactory<String, KafkaEventEnvelope> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setCommonErrorHandler(errorHandler);
        factory.setConcurrency(concurrency); // matches 3 partitions per topic
        return factory;
    }

    // ── Topic declarations (created on startup via KafkaAdmin) ───────

    @Bean
    public KafkaAdmin kafkaAdmin() {
        return new KafkaAdmin(Map.of("bootstrap.servers", bootstrapServers));
    }

    @Bean
    public NewTopic testTopic() {
        return TopicBuilder.name(KafkaTopics.TEST)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic testTopicDlt() {
        return TopicBuilder.name(KafkaTopics.TEST + "-dlt")
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic emailTopic() {
        return TopicBuilder.name(KafkaTopics.EMAIL_REQUESTED)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic emailTopicDlt() {
        return TopicBuilder.name(KafkaTopics.EMAIL_REQUESTED + "-dlt")
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic notificationEmailTopic() {
        return TopicBuilder.name(KafkaTopics.NOTIFICATION_EMAIL)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic notificationEmailTopicDlt() {
        return TopicBuilder.name(KafkaTopics.NOTIFICATION_EMAIL + "-dlt")
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic assessmentExecuteTopic() {
        return TopicBuilder.name(KafkaTopics.ASSESSMENT_EXECUTE_REQUESTED)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic assessmentExecuteTopicDlt() {
        return TopicBuilder.name(KafkaTopics.ASSESSMENT_EXECUTE_REQUESTED + "-dlt")
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic auditEngagementSnapshotTopic() {
        return TopicBuilder.name(KafkaTopics.AUDIT_ENGAGEMENT_SNAPSHOT_REQUESTED)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic auditEngagementSnapshotTopicDlt() {
        return TopicBuilder.name(KafkaTopics.AUDIT_ENGAGEMENT_SNAPSHOT_REQUESTED + "-dlt")
                .partitions(3)
                .replicas(1)
                .build();
    }

    /**
     * Bulk policy adoption.
     *
     * Keyed by TENANT, so one tenant's adoption run stays on one partition and
     * two runs for the same tenant cannot interleave — which matters here,
     * because the "already adopted?" check and the copy that satisfies it are
     * not one atomic step. Concurrent runs for the same tenant could both see
     * "not adopted" and both copy.
     *
     * The engagement topic keys by engagementId for the opposite reason: each
     * engagement is independent and round-robin is fine.
     */
    @Bean
    public NewTopic auditPolicyBulkAdoptTopic() {
        return TopicBuilder.name(KafkaTopics.AUDIT_POLICY_BULK_ADOPT_REQUESTED)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic auditPolicyBulkAdoptTopicDlt() {
        return TopicBuilder.name(KafkaTopics.AUDIT_POLICY_BULK_ADOPT_REQUESTED + "-dlt")
                .partitions(3)
                .replicas(1)
                .build();
    }
}