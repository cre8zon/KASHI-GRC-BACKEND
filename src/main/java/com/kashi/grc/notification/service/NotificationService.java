package com.kashi.grc.notification.service;

import com.kashi.grc.common.config.multitenancy.TenantContext;
import com.kashi.grc.common.kafka.KafkaEventPublisher;
import com.kashi.grc.common.kafka.KafkaTopics;
import com.kashi.grc.notification.domain.Notification;
import com.kashi.grc.notification.repository.NotificationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-app notifications + the single Kafka PRODUCER for
 * kashigrc.notification.email.
 *
 * DESIGN — one choke point instead of 36 call-site edits:
 *   Every "affected user" moment in the platform (task assigned, comment,
 *   mention, SLA breach, audit/assessment events, ...) already calls this
 *   service. Intercepting HERE means every in-app notification automatically
 *   also becomes an email event — no producer code in any business module.
 *
 * WHAT CHANGED vs the original:
 *   - The synchronous DB write of the Notification row is UNCHANGED
 *     (in-app notifications stay instant and transactional).
 *   - Additionally, ONE NOTIFICATION_EMAIL_REQUESTED event is published per
 *     send()/sendToUsers() call. sendToUsers publishes a SINGLE event
 *     carrying all recipient IDs — the consumer fans out.
 *
 * TRANSACTION SAFETY (pitfall: message can outlive a rollback):
 *   Callers are usually inside a business @Transactional. The publish is
 *   registered as an afterCommit synchronization when a transaction is
 *   active — rollback → notification row gone AND no email event. Outside
 *   a transaction (some scheduler paths) it publishes immediately.
 *
 * TENANT:
 *   Producer stamps TenantContext when present (request threads). Scheduler
 *   threads have no TenantContext → tenantId travels as null and the
 *   consumer derives it from the recipient's User row. Notification rows
 *   themselves have no tenant column (queried by userId), so the DB write
 *   is unaffected either way.
 *
 * Kafka disabled (kashi.kafka.enabled=false) → publisher bean absent →
 * behaviour is byte-for-byte the original: DB write only, no email.
 */
@Slf4j
@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;
    /** Present only when kashi.kafka.enabled=true. */
    private final KafkaEventPublisher    kafkaPublisher;   // nullable

    public NotificationService(NotificationRepository notificationRepository,
                               ObjectProvider<KafkaEventPublisher> kafkaPublisherProvider) {
        this.notificationRepository = notificationRepository;
        this.kafkaPublisher         = kafkaPublisherProvider.getIfAvailable();
    }

    @Transactional
    public void send(Long userId, String type, String message, String entityType, Long entityId) {
        send(userId, type, message, entityType, entityId, null, Map.of());
    }

    /**
     * Rich variant — call sites SHOULD migrate to this progressively.
     *
     * @param actorUserId who performed the action (commenter, assigner, ...).
     *                    Enables ACTOR-audience email rules and lets the
     *                    consumer exclude the actor from recipient emails.
     * @param context     structured template data: {"commenterName": "Priya",
     *                    "stepName": "Vendor Fill", "taskUrl": "/tasks/42"}.
     *                    Becomes the {{placeholder}} variables in email
     *                    templates. Legacy callers get Map.of() — their
     *                    templates can still use the base variables
     *                    (firstName, message, eventName, entity*).
     */
    @Transactional
    public void send(Long userId, String type, String message, String entityType, Long entityId,
                     Long actorUserId, Map<String, String> context) {
        saveNotification(userId, type, message, entityType, entityId);
        publishEmailEvent(List.of(userId), type, message, entityType, entityId, actorUserId, context);
    }

    @Transactional
    public void sendToUsers(List<Long> userIds, String type, String message, String entityType, Long entityId) {
        sendToUsers(userIds, type, message, entityType, entityId, null, Map.of());
    }

    @Transactional
    public void sendToUsers(List<Long> userIds, String type, String message, String entityType, Long entityId,
                            Long actorUserId, Map<String, String> context) {
        List<Long> distinct = userIds.stream().distinct().toList();
        distinct.forEach(uid -> saveNotification(uid, type, message, entityType, entityId));
        // ONE event for all recipients — the consumer fans out to N emails
        publishEmailEvent(distinct, type, message, entityType, entityId, actorUserId, context);
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private void saveNotification(Long userId, String type, String message,
                                  String entityType, Long entityId) {
        Notification n = Notification.builder()
                .userId(userId)
                .type(type)
                .message(message)
                .entityType(entityType)
                .entityId(entityId)
                .sentAt(LocalDateTime.now())
                .build();
        notificationRepository.save(n);
        log.debug("Notification sent to user {} — [{}] {}", userId, type, message);
    }

    /**
     * Publish the email fanout event AFTER COMMIT when inside a transaction,
     * immediately otherwise. Key = entityType:entityId so all events about
     * the same entity land on one partition and are processed in order.
     */
    private void publishEmailEvent(List<Long> userIds, String eventKey, String message,
                                   String entityType, Long entityId,
                                   Long actorUserId, Map<String, String> context) {
        if (kafkaPublisher == null || userIds == null || userIds.isEmpty()) return;

        Map<String, Object> payload = new HashMap<>();
        payload.put("eventKey",         eventKey);
        payload.put("message",          message);
        payload.put("entityType",       entityType);
        payload.put("entityId",         entityId);
        payload.put("recipientUserIds", userIds);
        payload.put("context",          context != null ? context : Map.of());

        // TenantContext is null on scheduler threads — consumer derives tenant
        // from the recipient's User row in that case.
        Long tenantId = TenantContext.getCurrentTenant();
        String key = (entityType != null && entityId != null)
                ? entityType + ":" + entityId : eventKey;

        Runnable publish = () -> kafkaPublisher.publish(
                KafkaTopics.NOTIFICATION_EMAIL, "NOTIFICATION_EMAIL_REQUESTED",
                key, payload, tenantId, actorUserId);

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { publish.run(); }
            });
        } else {
            publish.run();
        }
    }
}