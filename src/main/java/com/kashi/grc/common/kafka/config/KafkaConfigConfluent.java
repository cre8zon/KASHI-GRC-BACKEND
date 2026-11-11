
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
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@EnableKafka
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "kashi.kafka.enabled", havingValue = "true")
public class KafkaConfigConfluent {

    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    private final Map<String, KafkaClientMetrics> boundClientMetrics =
            new ConcurrentHashMap<>();

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.properties.security.protocol:SASL_SSL}")
    private String securityProtocol;

    @Value("${spring.kafka.properties.sasl.mechanism:PLAIN}")
    private String saslMechanism;

    @Value("${spring.kafka.properties.sasl.jaas.config}")
    private String saslJaasConfig;

    @Value("${kashi.kafka.consumer.group-id:kashigrc-core}")
    private String groupId;

    @Value("${kashi.kafka.consumer.concurrency:3}")
    private int concurrency;

    // Common Confluent Cloud configuration
    private Map<String, Object> commonKafkaProperties() {

        Map<String, Object> props = new HashMap<>();

        props.put("bootstrap.servers", bootstrapServers);
        props.put("security.protocol", securityProtocol);
        props.put("sasl.mechanism", saslMechanism);
        props.put("sasl.jaas.config", saslJaasConfig);
        props.put("ssl.endpoint.identification.algorithm", "https");
        props.put("client.dns.lookup", "use_all_dns_ips");

        return props;
    }

    // ─────────────────────────────────────────────
    // Producer
    // ─────────────────────────────────────────────

    @Bean
    public ProducerFactory<String, KafkaEventEnvelope> producerFactory() {

        Map<String, Object> props = commonKafkaProperties();

        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 15000);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30000);
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 3000);

        DefaultKafkaProducerFactory<String, KafkaEventEnvelope> factory =
                new DefaultKafkaProducerFactory<>(props);

        factory.setKeySerializer(new StringSerializer());
        factory.setValueSerializer(new JsonSerializer<>(objectMapper));

        factory.addListener(
                new ProducerFactory.Listener<String, KafkaEventEnvelope>() {

                    @Override
                    public void producerAdded(
                            String id,
                            Producer<String, KafkaEventEnvelope> producer) {

                        bindClientMetrics(id, new KafkaClientMetrics(producer));
                    }

                    @Override
                    public void producerRemoved(
                            String id,
                            Producer<String, KafkaEventEnvelope> producer) {

                        unbindClientMetrics(id);
                    }
                });

        return factory;
    }

    @Bean
    public KafkaTemplate<String, KafkaEventEnvelope> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    // ─────────────────────────────────────────────
    // Consumer
    // ─────────────────────────────────────────────

    @Bean
    public ConsumerFactory<String, KafkaEventEnvelope> consumerFactory() {

        Map<String, Object> props = commonKafkaProperties();

        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100);

        JsonDeserializer<KafkaEventEnvelope> jsonDeserializer =
                new JsonDeserializer<>(
                        KafkaEventEnvelope.class,
                        objectMapper
                );

        jsonDeserializer.addTrustedPackages("com.kashi.grc.*");

        DefaultKafkaConsumerFactory<String, KafkaEventEnvelope> factory =
                new DefaultKafkaConsumerFactory<>(
                        props,
                        new ErrorHandlingDeserializer<>(new StringDeserializer()),
                        new ErrorHandlingDeserializer<>(jsonDeserializer)
                );

        factory.addListener(
                new ConsumerFactory.Listener<String, KafkaEventEnvelope>() {

                    @Override
                    public void consumerAdded(
                            String id,
                            Consumer<String, KafkaEventEnvelope> consumer) {

                        bindClientMetrics(id, new KafkaClientMetrics(consumer));
                    }

                    @Override
                    public void consumerRemoved(
                            String id,
                            Consumer<String, KafkaEventEnvelope> consumer) {

                        unbindClientMetrics(id);
                    }
                });

        return factory;
    }

    // ─────────────────────────────────────────────
    // Kafka Metrics
    // ─────────────────────────────────────────────

    private void bindClientMetrics(String id, KafkaClientMetrics metrics) {

        try {
            metrics.bindTo(meterRegistry);
            boundClientMetrics.put(id, metrics);

        } catch (Exception e) {
            log.warn(
                    "[KAFKA-METRICS] Failed to bind metrics for id={} - {}",
                    id,
                    e.toString()
            );
        }
    }

    private void unbindClientMetrics(String id) {

        KafkaClientMetrics metrics = boundClientMetrics.remove(id);

        if (metrics != null) {
            try {
                metrics.close();

            } catch (Exception e) {
                log.warn(
                        "[KAFKA-METRICS] Failed to unbind metrics for id={} - {}",
                        id,
                        e.toString()
                );
            }
        }
    }

    // ─────────────────────────────────────────────
    // Listener Factory + DLT + Retry
    // ─────────────────────────────────────────────

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, KafkaEventEnvelope>
    kafkaListenerContainerFactory(
            KafkaTemplate<String, KafkaEventEnvelope> template) {

        DeadLetterPublishingRecoverer recoverer =
                new DeadLetterPublishingRecoverer(template);

        org.springframework.kafka.listener.ConsumerRecordRecoverer countingRecoverer =
                (record, exception) -> {

                    try {
                        Counter.builder("kashigrc.kafka.dlt.published")
                                .tag("topic", record.topic())
                                .register(meterRegistry)
                                .increment();

                    } catch (Exception e) {
                        log.warn(
                                "[KAFKA-METRICS] Failed to record DLT metric for topic={} - {}",
                                record.topic(),
                                e.toString()
                        );
                    }

                    recoverer.accept(record, exception);
                };

        ExponentialBackOff backOff =
                new ExponentialBackOff(1000L, 2.0);

        backOff.setMaxElapsedTime(10000L);

        DefaultErrorHandler errorHandler =
                new DefaultErrorHandler(countingRecoverer, backOff);

        errorHandler.setLogLevel(
                org.springframework.kafka.KafkaException.Level.WARN
        );

        ConcurrentKafkaListenerContainerFactory<String, KafkaEventEnvelope>
                factory = new ConcurrentKafkaListenerContainerFactory<>();

        factory.setConsumerFactory(consumerFactory());
        factory.setCommonErrorHandler(errorHandler);
        factory.setConcurrency(concurrency);

        return factory;
    }

    // ─────────────────────────────────────────────
    // Kafka Admin
    // ─────────────────────────────────────────────

    @Bean
    public KafkaAdmin kafkaAdmin() {
        return new KafkaAdmin(commonKafkaProperties());
    }

    // ─────────────────────────────────────────────
    // Topic Declarations
    // Replication factor 3 for Confluent Cloud
    // ─────────────────────────────────────────────

    @Bean
    public NewTopic testTopic() {
        return TopicBuilder.name(KafkaTopics.TEST)
                .partitions(3)
                .replicas(3)
                .build();
    }

    @Bean
    public NewTopic testTopicDlt() {
        return TopicBuilder.name(KafkaTopics.TEST + "-dlt")
                .partitions(3)
                .replicas(3)
                .build();
    }

    @Bean
    public NewTopic emailTopic() {
        return TopicBuilder.name(KafkaTopics.EMAIL_REQUESTED)
                .partitions(3)
                .replicas(3)
                .build();
    }

    @Bean
    public NewTopic emailTopicDlt() {
        return TopicBuilder.name(KafkaTopics.EMAIL_REQUESTED + "-dlt")
                .partitions(3)
                .replicas(3)
                .build();
    }

    @Bean
    public NewTopic notificationEmailTopic() {
        return TopicBuilder.name(KafkaTopics.NOTIFICATION_EMAIL)
                .partitions(3)
                .replicas(3)
                .build();
    }

    @Bean
    public NewTopic notificationEmailTopicDlt() {
        return TopicBuilder.name(KafkaTopics.NOTIFICATION_EMAIL + "-dlt")
                .partitions(3)
                .replicas(3)
                .build();
    }

    @Bean
    public NewTopic assessmentExecuteTopic() {
        return TopicBuilder.name(KafkaTopics.ASSESSMENT_EXECUTE_REQUESTED)
                .partitions(3)
                .replicas(3)
                .build();
    }

    @Bean
    public NewTopic assessmentExecuteTopicDlt() {
        return TopicBuilder.name(KafkaTopics.ASSESSMENT_EXECUTE_REQUESTED + "-dlt")
                .partitions(3)
                .replicas(3)
                .build();
    }

    @Bean
    public NewTopic auditEngagementSnapshotTopic() {
        return TopicBuilder.name(KafkaTopics.AUDIT_ENGAGEMENT_SNAPSHOT_REQUESTED)
                .partitions(3)
                .replicas(3)
                .build();
    }

    @Bean
    public NewTopic auditEngagementSnapshotTopicDlt() {
        return TopicBuilder.name(KafkaTopics.AUDIT_ENGAGEMENT_SNAPSHOT_REQUESTED + "-dlt")
                .partitions(3)
                .replicas(3)
                .build();
    }

    @Bean
    public NewTopic auditPolicyBulkAdoptTopic() {
        return TopicBuilder.name(KafkaTopics.AUDIT_POLICY_BULK_ADOPT_REQUESTED)
                .partitions(3)
                .replicas(3)
                .build();
    }

    @Bean
    public NewTopic auditPolicyBulkAdoptTopicDlt() {
        return TopicBuilder.name(KafkaTopics.AUDIT_POLICY_BULK_ADOPT_REQUESTED + "-dlt")
                .partitions(3)
                .replicas(3)
                .build();
    }
}
