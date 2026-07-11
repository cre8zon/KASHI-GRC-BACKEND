package com.kashi.grc.common.kafka.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashi.grc.common.kafka.KafkaEventEnvelope;
import com.kashi.grc.common.kafka.KafkaTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 *    is published to "<topic>.DLT" and the partition moves on. A poison
 *    message can never block a partition.
 *  - Every message deserialises to KafkaEventEnvelope — one target type for
 *    all topics, no type-mapping headers needed.
 */
@Slf4j
@EnableKafka
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "kashi.kafka.enabled", havingValue = "true")
public class KafkaConfig {

    private final ObjectMapper objectMapper;

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${kashi.kafka.consumer.group-id:kashigrc-core}")
    private String groupId;

    @Value("${kashi.kafka.consumer.concurrency:3}")
    private int concurrency;

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

        DefaultKafkaProducerFactory<String, KafkaEventEnvelope> factory =
                new DefaultKafkaProducerFactory<>(props);
        factory.setKeySerializer(new StringSerializer());
        factory.setValueSerializer(new JsonSerializer<>(objectMapper));
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

        return new DefaultKafkaConsumerFactory<>(
                props,
                new ErrorHandlingDeserializer<>(new StringDeserializer()),
                new ErrorHandlingDeserializer<>(json));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, KafkaEventEnvelope>
    kafkaListenerContainerFactory(KafkaTemplate<String, KafkaEventEnvelope> template) {

        // Failed records → "<originalTopic>.DLT", same partition
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template);

        // 3 retries: 1s → 2s → 4s, then dead-letter
        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxElapsedTime(10_000L);
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
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
        return TopicBuilder.name(KafkaTopics.TEST + ".DLT")
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
        return TopicBuilder.name(KafkaTopics.EMAIL_REQUESTED + ".DLT")
                .partitions(3)
                .replicas(1)
                .build();
    }
}