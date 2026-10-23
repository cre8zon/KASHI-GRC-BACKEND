package com.kashi.grc.notification.service;

import com.kashi.grc.common.config.multitenancy.TenantContext;
import com.kashi.grc.common.kafka.KafkaEventPublisher;
import com.kashi.grc.common.kafka.KafkaTopics;
import com.kashi.grc.notification.domain.Notification;
import com.kashi.grc.notification.repository.NotificationRepository;
import com.kashi.grc.notification.spi.NotificationRouteContributor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
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

    /**
     * Where a notification opens, answered by the module that owns the entity.
     *
     * ObjectProvider rather than a constructor List for the reason spelled out
     * in NotificationRouteContributor: this service is injected into most
     * services in the product, so eager injection of anything that transitively
     * reaches one of them fails the context at startup. Resolved lazily, per
     * call, which costs a map lookup.
     */
    private final ObjectProvider<NotificationRouteContributor> routeContributors;

    /**
     * The STOMP push, so a notification arrives the moment it is written.
     *
     * ObjectProvider for the same reason as routeContributors above: this
     * service is injected almost everywhere, and eager injection of anything
     * that transitively reaches one of its dependents fails the context at
     * startup. It also means a deployment with the websocket broker disabled
     * simply has no bean here and falls back to polling, which is the
     * behaviour this service had before the push existed.
     */
    private final ObjectProvider<SimpMessagingTemplate> messagingProvider;

    public NotificationService(NotificationRepository notificationRepository,
                               ObjectProvider<KafkaEventPublisher> kafkaPublisherProvider,
                               ObjectProvider<NotificationRouteContributor> routeContributors,
                               ObjectProvider<SimpMessagingTemplate> messagingProvider) {
        this.notificationRepository = notificationRepository;
        this.kafkaPublisher         = kafkaPublisherProvider.getIfAvailable();
        this.routeContributors      = routeContributors;
        this.messagingProvider      = messagingProvider;
    }

    /**
     * Pushes one saved notification to its recipient's personal topic.
     *
     * ── WHY /topic/user/{userId} AND NOT A NEW TOPIC ──────────────────────
     *
     * It already exists and is already the right place. WebSocketConfig
     * documents it as "personal notifications for a user", WorkflowEventListener
     * and ActionItemService already publish to it, StompAuthChannelInterceptor
     * already authorises SUBSCRIBE on it against the signed-in user, and
     * useActionItems on the client is already subscribed. A notification is the
     * most literal possible case of "personal notification for a user", so
     * inventing a second channel would mean a second topic, a second
     * authorisation rule and a second socket for no gain.
     *
     * Deliberately NOT the chat topic, which was the other candidate: chat's
     * pushTopic is handed out only when the caller holds the chat permission
     * and is not a guest, so riding it would silently drop the push for every
     * vendor-side user — exactly the people most of these notifications are
     * for.
     *
     * ── WHAT IS SENT ──────────────────────────────────────────────────────
     *
     * The id and the route, not the whole row. The client refetches through
     * the API it already uses, which keeps one shape of the notification in
     * one place and means the socket payload can never disagree with the list.
     * The message rides along so the toast can appear without waiting for that
     * refetch to land.
     *
     * ── AFTER COMMIT ──────────────────────────────────────────────────────
     *
     * Same contract the email publish above spells out: callers are usually
     * inside a business transaction, and a push sent before commit can arrive
     * for a row that is about to be rolled back — the client would refetch and
     * find nothing. Outside a transaction (scheduler paths) it sends at once.
     *
     * Failure here is never allowed to cost the notification. The row is
     * already saved and the 30-second poll remains as the fallback, so a dead
     * broker degrades to the old behaviour rather than throwing on the caller's
     * business operation.
     */
    private void pushNotification(Notification n) {
        SimpMessagingTemplate messaging = messagingProvider.getIfAvailable();
        if (messaging == null || n.getUserId() == null) return;

        Map<String, Object> payload = new HashMap<>();
        payload.put("type",           "NOTIFICATION_CREATED");
        payload.put("notificationId", n.getId());
        payload.put("notificationType", n.getType());
        payload.put("message",        n.getMessage());
        payload.put("entityType",     n.getEntityType());
        payload.put("entityId",       n.getEntityId());
        payload.put("actionUrl",      n.getActionUrl());

        String destination = "/topic/user/" + n.getUserId();

        Runnable send = () -> {
            try {
                messaging.convertAndSend(destination, payload);
                log.debug("[NOTIFY-PUSH] {} → {}", n.getId(), destination);
            } catch (RuntimeException e) {
                log.debug("[NOTIFY-PUSH] Push failed, client will poll | {}", e.getMessage());
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { send.run(); }
            });
        } else {
            send.run();
        }
    }

    /**
     * The route for one notification, or null when nothing can answer.
     *
     * Null is a normal outcome, not a failure: a module with no contributor
     * keeps the client-side fallback it has always had. The point of writing it
     * here is that the client stops GUESSING for the modules that have one.
     *
     * Wrapped in a catch because this runs on the path that saves the
     * notification. A contributor that throws must cost its own route, never
     * the notification — losing the message to decorate it would be a poor
     * trade.
     */
    private String resolveActionUrl(String entityType, Long entityId, String type, Long userId) {
        if (entityType == null || entityId == null) return null;
        try {
            return routeContributors.stream()
                    .filter(c -> c.supports(entityType))
                    .map(c -> c.routeFor(entityType, entityId, type, userId))
                    .filter(java.util.Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            log.warn("[NOTIFY] Route resolution failed for {}:{} — notification still sent | {}",
                    entityType, entityId, e.getMessage());
            return null;
        }
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
                // Resolved per recipient, not per notification: the vendor user
                // notified about a question and the organisation user notified
                // about the same one want opposite screens, and sendToUsers
                // calls this once per user precisely so each gets their own.
                .actionUrl(resolveActionUrl(entityType, entityId, type, userId))
                .sentAt(LocalDateTime.now())
                .build();
        notificationRepository.save(n);
        // After the save, so the payload carries the generated id — the client
        // uses it to dedupe against what it has already shown.
        pushNotification(n);
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

    // ══════════════════════ READ ══════════════════════════════════════════════
    //
    // A notification is read when its subject has been dealt with, not only
    // when it is clicked on the Notifications page:
    //   • markReadForEntity — the owning module says so (chat: the conversation
    //     was read, or replied to);
    //   • markVisited       — the person opened the page a notification points
    //     to, by any route (a link, the sidebar, a bookmark);
    //   • markAllRead       — every unread one, not just the page on screen.
    // Each pushes NOTIFICATIONS_READ on the user's topic, so the bell, the page
    // and the sidebar badge update in every open tab at once.

    public long unreadCount(Long userId) {
        return notificationRepository.countByUserIdAndReadAtIsNull(userId);
    }

    @Transactional
    public int markAllRead(Long userId) {
        int n = notificationRepository.markAllRead(userId, LocalDateTime.now());
        if (n > 0) pushRead(userId);
        return n;
    }

    @Transactional
    public int markReadForEntity(Long userId, String entityType, Long entityId) {
        if (userId == null) return 0;
        int n = notificationRepository.markReadForEntity(userId, entityType, entityId, LocalDateTime.now());
        if (n > 0) pushRead(userId);
        return n;
    }

    /**
     * The user is looking at {@code path}?{@code query}: their unread
     * notifications about what is on that screen are read.
     *
     *   With an address (action_url): same path, and every query parameter of
     *   the address present with the same value — so a workspace's REQUESTS
     *   notification needs the Requests tab open, and a comment notification
     *   the Comments tab. ?join=1 is ignored (it only starts a call).
     *   Without one: the entity's own detail page, or the entity open in a
     *   side drawer (?drawerType=&drawerId=). List pages (the inbox, action
     *   items) never mark anything — seeing a list is not dealing with it.
     */
    @Transactional
    public int markVisited(Long userId, String path, String query) {
        if (userId == null || path == null || path.isBlank()) return 0;
        String p = normPath(path);
        Map<String, String> q = parseQuery(query);
        String drawerType = q.get("drawerType");
        Long drawerId = parseLong(q.get("drawerId"));
        List<Long> ids = new java.util.ArrayList<>();
        for (Notification n : notificationRepository.findByUserIdAndReadAtIsNull(userId)) {
            if (visited(n, p, q, drawerType, drawerId)) ids.add(n.getId());
        }
        if (ids.isEmpty()) return 0;
        int done = notificationRepository.markRead(userId, ids, LocalDateTime.now());
        if (done > 0) pushRead(userId);
        return done;
    }

    private static final java.util.Set<String> IGNORED_PARAMS = java.util.Set.of("join");

    /** Detail pages for entity types whose notifications carry no address (the client's fallback routes). */
    private static final Map<String, String> ENTITY_PAGES = Map.ofEntries(
            Map.entry("TASK", "/workflow/tasks/"),
            Map.entry("VENDOR", "/tprm/vendors/"),
            Map.entry("ASSESSMENT", "/module/vendor_assessment/"),
            Map.entry("AUDIT_ENGAGEMENT", "/module/audit_engagement/"),
            Map.entry("AUDIT_PROJECT", "/module/audit_project/"),
            Map.entry("AUDIT_CONTROL_INSTANCE", "/module/audit_control_instance/"),
            Map.entry("AUDIT_TEST_INSTANCE", "/module/audit_test_instance/"),
            Map.entry("AUDIT_POLICY_INSTANCE", "/module/audit_policy_instance/"),
            Map.entry("AUDIT_FINDING", "/module/audit_finding/"),
            Map.entry("ISSUE", "/module/issue/"),
            Map.entry("COLLAB_MEETING", "/collaboration/meetings/"),
            Map.entry("COLLAB_ROOM", "/collaboration/rooms/"),
            Map.entry("CHAT_CONVERSATION", "/chat/"));

    private static boolean visited(Notification n, String path, Map<String, String> query, String drawerType, Long drawerId) {
        String url = n.getActionUrl();
        if (url != null && !url.isBlank()) {
            int qi = url.indexOf('?');
            String aPath = normPath(qi < 0 ? url : url.substring(0, qi));
            if (aPath.equals(path)) {
                Map<String, String> aQuery = parseQuery(qi < 0 ? null : url.substring(qi + 1));
                for (Map.Entry<String, String> e : aQuery.entrySet()) {
                    if (IGNORED_PARAMS.contains(e.getKey())) continue;
                    if (!e.getValue().equals(query.get(e.getKey()))) return false;
                }
                return true;
            }
        }
        if (n.getEntityType() == null || n.getEntityId() == null) return false;
        if (drawerType != null && drawerId != null
                && drawerType.equalsIgnoreCase(n.getEntityType()) && drawerId.equals(n.getEntityId())) return true;
        if (url == null || url.isBlank()) {
            String page = ENTITY_PAGES.get(n.getEntityType());
            return page != null && path.equals(normPath(page + n.getEntityId()));
        }
        return false;
    }

    private static String normPath(String p) {
        String s = p == null ? "" : p.trim();
        int h = s.indexOf('#');
        if (h >= 0) s = s.substring(0, h);
        while (s.length() > 1 && s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s.toLowerCase();
    }

    private static Map<String, String> parseQuery(String q) {
        Map<String, String> out = new HashMap<>();
        if (q == null || q.isBlank()) return out;
        for (String part : (q.startsWith("?") ? q.substring(1) : q).split("&")) {
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            String k = java.net.URLDecoder.decode(eq < 0 ? part : part.substring(0, eq), java.nio.charset.StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : java.net.URLDecoder.decode(part.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
            out.put(k, v);
        }
        return out;
    }

    private static Long parseLong(String s) {
        try { return s == null ? null : Long.parseLong(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    /** Tell the user's open tabs that notifications were read (after commit). */
    private void pushRead(Long userId) {
        SimpMessagingTemplate messaging = messagingProvider.getIfAvailable();
        if (messaging == null || userId == null) return;
        Runnable send = () -> {
            try {
                messaging.convertAndSend("/topic/user/" + userId, Map.of("type", "NOTIFICATIONS_READ"));
            } catch (RuntimeException e) {
                log.debug("[NOTIFY-PUSH] Read push failed, clients will poll | {}", e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { send.run(); }
            });
        } else {
            send.run();
        }
    }
}