package com.kashi.grc.common.kafka;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * Transparently instruments every {@code @KafkaListener} method with
 * processing counters and a latency timer — no changes needed to any
 * existing consumer (EmailEventConsumer, NotificationEmailConsumer,
 * ExecuteAssessmentConsumer, AuditEngagementSnapshotConsumer) or any future
 * one, since this wraps by annotation, not by class.
 *
 * WHY AOP INSTEAD OF INSTRUMENTING EACH CONSUMER DIRECTLY: every consumer in
 * this app already follows the same try/catch/finally shape (set
 * TenantContext, do work, record outcome, rethrow on failure, clear
 * TenantContext) — hand-adding Counter/Timer calls to each one would mean
 * four near-identical edits today and one more every time a new consumer is
 * added. An aspect on the @KafkaListener annotation gets every consumer,
 * including future ones, for free, and can never be forgotten on a new one.
 *
 * METRICS EMITTED (tag: topic, resolved from the listener's @KafkaListener
 * topics attribute):
 *   kashigrc.kafka.consumer.processed   — Counter, tag result=success|failure
 *   kashigrc.kafka.consumer.latency     — Timer, tag result=success|failure
 *
 * CRITICAL: this advice must never swallow an exception. Every consumer's
 * retry/DLT behavior depends on rethrowing to the container's error handler
 * — this aspect only observes, it never changes what the listener method
 * does or whether the exception propagates.
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "kashi.kafka.enabled", havingValue = "true")
public class KafkaConsumerMetricsAspect {

    private final MeterRegistry meterRegistry;

    @Around("@annotation(org.springframework.kafka.annotation.KafkaListener)")
    public Object aroundKafkaListener(ProceedingJoinPoint pjp) throws Throwable {
        String topic = resolveTopic(pjp);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            Object result = pjp.proceed();
            recordOutcome(topic, "success", sample);
            return result;
        } catch (Throwable t) {
            recordOutcome(topic, "failure", sample);
            throw t; // MUST re-throw — this aspect only observes
        }
    }

    private void recordOutcome(String topic, String result, Timer.Sample sample) {
        try {
            Counter.builder("kashigrc.kafka.consumer.processed")
                    .tag("topic", topic).tag("result", result)
                    .register(meterRegistry).increment();
            sample.stop(Timer.builder("kashigrc.kafka.consumer.latency")
                    .tag("topic", topic).tag("result", result)
                    .register(meterRegistry));
        } catch (Exception e) {
            // A metrics-recording failure must never affect message
            // processing — same "observability can't break the thing it's
            // observing" rule as the Redis metrics layer.
            log.warn("[KAFKA-METRICS] Failed to record consumer metric for topic={} — {}", topic, e.toString());
        }
    }

    /** Resolves the topic name from the invoked method's @KafkaListener annotation. */
    private String resolveTopic(ProceedingJoinPoint pjp) {
        try {
            Method method = ((MethodSignature) pjp.getSignature()).getMethod();
            KafkaListener annotation = method.getAnnotation(KafkaListener.class);
            if (annotation != null && annotation.topics().length > 0) {
                return annotation.topics()[0];
            }
        } catch (Exception ignored) {
            // Fall through to "unknown" — a metrics-resolution failure must
            // never affect message processing.
        }
        return "unknown";
    }
}