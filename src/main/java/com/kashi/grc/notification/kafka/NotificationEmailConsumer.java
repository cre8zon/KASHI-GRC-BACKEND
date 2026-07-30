package com.kashi.grc.notification.kafka;

import com.kashi.grc.common.config.multitenancy.TenantContext;
import com.kashi.grc.common.kafka.KafkaEventEnvelope;
import com.kashi.grc.common.kafka.KafkaTopics;
import com.kashi.grc.common.service.MailService;
import com.kashi.grc.notification.domain.NotificationDispatchLog;
import com.kashi.grc.notification.domain.NotificationEmailRule;
import com.kashi.grc.notification.domain.UserNotificationPreference;
import com.kashi.grc.notification.repository.NotificationDispatchLogRepository;
import com.kashi.grc.notification.repository.NotificationEmailRuleRepository;
import com.kashi.grc.notification.repository.UserNotificationPreferenceRepository;
import com.kashi.grc.usermanagement.domain.User;
import com.kashi.grc.usermanagement.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Consumer for kashigrc.notification.email — fans ONE notification event out
 * to N recipient emails.
 *
 * PIPELINE per event (all synchronous on the listener thread — pitfall #1):
 *   1. TenantContext from envelope (fallback: derived from first recipient's
 *      User row — scheduler-originated notifications carry no tenant).
 *   2. Fanout idempotency via notification_dispatch_log (event_id unique).
 *      Two-layer with email_log: this table stops duplicate FANOUT, email_log
 *      stops duplicate SMTP sends (see NotificationDispatchLog javadoc).
 *   3. Resolve recipients → User rows; drop inactive users, blank emails,
 *      and the actor (people don't need email about their own action).
 *   4. Resolve NotificationEmailRule set (tenant rules override global):
 *        suppress rule        → SKIPPED, no email
 *        N template rules     → N templates × M recipients emails
 *        no rules (default)   → 1 raw fallback email per recipient, built
 *                               from the in-app notification message
 *   5. Each email goes through MailService with EXPLICIT tenant (pitfall #5)
 *      → published to kashigrc.email.requested → EmailEventConsumer owns
 *      SMTP, per-recipient EmailLog idempotency, retries and DLT. This
 *      consumer never touches SMTP itself, so fanout stays fast and one
 *      broken mailbox cannot block the notification partition.
 *
 * FAILURE CLASSIFICATION:
 *   Non-retryable (log + record SKIPPED + return, offset commits):
 *     no recipients / no resolvable emails / suppressed by rule.
 *   Retryable (record FAILED + RETHROW → 3 backoff retries → .DLT):
 *     DB unavailable, unexpected exceptions. Publishing to the email topic
 *     is async fire-and-log (KafkaEventPublisher), matching MailService.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "kashi.kafka.enabled", havingValue = "true")
public class NotificationEmailConsumer {

    private static final EnumSet<NotificationDispatchLog.Status> TERMINAL =
            EnumSet.of(NotificationDispatchLog.Status.DISPATCHED,
                    NotificationDispatchLog.Status.SKIPPED);

    private final NotificationDispatchLogRepository dispatchLogRepository;
    private final NotificationEmailRuleRepository   ruleRepository;
    private final UserNotificationPreferenceRepository preferenceRepository;
    private final UserRepository                    userRepository;
    private final MailService                       mailService;

    /**
     * Absolute frontend base URL for email CTAs — emails can't use relative
     * links. Exposed to templates as {{appUrl}} and {{preferencesUrl}}.
     */
    @org.springframework.beans.factory.annotation.Value("${kashi.app.base-url:https://app.kashigrc.com}")
    private String appBaseUrl;

    @KafkaListener(
            topics = KafkaTopics.NOTIFICATION_EMAIL,
            groupId = "kashigrc-notification",
            containerFactory = "kafkaListenerContainerFactory")
    public void onNotificationEmailRequested(KafkaEventEnvelope envelope) {
        try {
            if (envelope.getTenantId() != null) {
                TenantContext.setCurrentTenant(envelope.getTenantId());
            }

            // Fanout idempotency — redelivery must not publish N new email events
            if (dispatchLogRepository.existsByEventIdAndStatusIn(envelope.getEventId(), TERMINAL)) {
                log.info("Notification event {} already processed — skipping duplicate delivery",
                        envelope.getEventId());
                return;
            }

            Map<String, Object> p = envelope.getPayload();
            String eventKey     = str(p.get("eventKey"));
            String message      = str(p.get("message"));
            String entityType   = str(p.get("entityType"));
            Long   entityId     = asLong(p.get("entityId"));
            List<Long> recipientIds = asLongList(p.get("recipientUserIds"));

            if (recipientIds.isEmpty()) {
                recordSkipped(envelope, eventKey, entityType, entityId, 0, "no recipients");
                return;
            }

            // Resolve users by PK (global — safe before tenant is known)
            List<User> users = userRepository.findAllById(recipientIds).stream()
                    .filter(User::isActive)
                    .filter(u -> u.getEmail() != null && !u.getEmail().isBlank())
                    .filter(u -> !Objects.equals(u.getId(), envelope.getActorUserId()))
                    .toList();

            // Scheduler-originated events carry no tenant — derive from recipient
            Long tenantId = envelope.getTenantId();
            if (tenantId == null && !users.isEmpty()) {
                tenantId = users.get(0).getTenantId();
                TenantContext.setCurrentTenant(tenantId);
                log.debug("Tenant derived from recipient user {} → tenant={}",
                        users.get(0).getId(), tenantId);
            }

            if (users.isEmpty()) {
                recordSkipped(envelope, eventKey, entityType, entityId,
                        recipientIds.size(), "no active recipients with email");
                return;
            }

            // ── Preference filter (per-user opt-out) ──────────────────────
            // One batch query covers recipients + actor. Resolution per user:
            // (userId, eventKey) row wins, else (userId, "ALL"), else enabled.
            List<Long> prefLookupIds = new ArrayList<>(users.stream().map(User::getId).toList());
            if (envelope.getActorUserId() != null) prefLookupIds.add(envelope.getActorUserId());
            Map<Long, Map<String, UserNotificationPreference>> prefs =
                    preferenceRepository.findByUserIdIn(prefLookupIds).stream()
                            .collect(java.util.stream.Collectors.groupingBy(
                                    UserNotificationPreference::getUserId,
                                    java.util.stream.Collectors.toMap(
                                            UserNotificationPreference::getEventKey,
                                            pref -> pref, (a, b) -> a)));
            users = users.stream()
                    .filter(u -> emailAllowed(prefs, u.getId(), eventKey))
                    .toList();

            if (users.isEmpty()
                    && !(envelope.getActorUserId() != null
                    && emailAllowed(prefs, envelope.getActorUserId(), eventKey))) {
                recordSkipped(envelope, eventKey, entityType, entityId,
                        recipientIds.size(), "all recipients opted out via preferences");
                return;
            }

            List<NotificationEmailRule> rules = resolveRules(eventKey, tenantId);

            if (rules.stream().anyMatch(NotificationEmailRule::isSuppressEmail)) {
                recordSkipped(envelope, eventKey, entityType, entityId,
                        recipientIds.size(), "suppressed by rule");
                return;
            }

            List<NotificationEmailRule> templateRules = rules.stream()
                    .filter(r -> r.getTemplateName() != null && !r.getTemplateName().isBlank())
                    .toList();

            Map<String, String> context = asStringMap(p.get("context"));

            int dispatched = 0;
            if (!templateRules.isEmpty()) {
                // ── RECIPIENT-audience templates → the affected users ─────
                // (actor already filtered out of `users` above — people don't
                // need the recipient-view email of their own action)
                List<NotificationEmailRule> recipientRules = templateRules.stream()
                        .filter(r -> r.getAudience() == NotificationEmailRule.Audience.RECIPIENT)
                        .toList();
                for (NotificationEmailRule rule : recipientRules) {
                    for (User u : users) {
                        mailService.send(rule.getTemplateName(), u.getEmail(),
                                buildVariables(u, context, eventKey, message, entityType, entityId),
                                tenantId, envelope.getEventId());
                        dispatched++;
                    }
                }

                // ── ACTOR-audience templates → the user who acted ─────────
                // ("your comment was posted", "you assigned X to Y", ...)
                List<NotificationEmailRule> actorRules = templateRules.stream()
                        .filter(r -> r.getAudience() == NotificationEmailRule.Audience.ACTOR)
                        .toList();
                if (!actorRules.isEmpty() && envelope.getActorUserId() != null
                        && emailAllowed(prefs, envelope.getActorUserId(), eventKey)) {
                    User actor = userRepository.findById(envelope.getActorUserId())
                            .filter(User::isActive)
                            .filter(u -> u.getEmail() != null && !u.getEmail().isBlank())
                            .orElse(null);
                    if (actor != null) {
                        for (NotificationEmailRule rule : actorRules) {
                            mailService.send(rule.getTemplateName(), actor.getEmail(),
                                    buildVariables(actor, context, eventKey, message, entityType, entityId),
                                    tenantId, envelope.getEventId());
                            dispatched++;
                        }
                    } else {
                        log.debug("ACTOR rules present but actor {} not emailable — skipping actor emails",
                                envelope.getActorUserId());
                    }
                }
            } else {
                // Default path: every event still emails — raw message fanout
                // to RECIPIENTS only. Curate later via notification_email_rules.
                String subject = "[KashiGRC] " + humanize(eventKey);
                for (User u : users) {
                    mailService.sendRaw(subject,
                            buildRawBody(u, message, eventKey), "text/html",
                            u.getEmail(), tenantId, envelope.getEventId());
                    dispatched++;
                }
            }

            dispatchLogRepository.save(NotificationDispatchLog.builder()
                    .eventId(envelope.getEventId())
                    .tenantId(tenantId)
                    .eventKey(eventKey)
                    .entityType(entityType)
                    .entityId(entityId)
                    .recipientCount(recipientIds.size())
                    .emailsDispatched(dispatched)
                    .status(NotificationDispatchLog.Status.DISPATCHED)
                    .processedAt(LocalDateTime.now())
                    .build());

            log.info("Notification fanout OK | eventId={} | eventKey={} | tenant={} | recipients={} | emails={}",
                    envelope.getEventId(), eventKey, tenantId, users.size(), dispatched);

        } catch (Exception e) {
            recordFailed(envelope, e);   // best-effort, never masks the real error
            throw e;                     // → DefaultErrorHandler retries → .DLT
        } finally {
            TenantContext.clear();       // listener threads are pooled — always clear
        }
    }

    // ── Rule resolution ───────────────────────────────────────────────────

    /**
     * Preference resolution: specific (user, eventKey) row wins over the
     * user's (user, "ALL") default; no row at all = enabled. Consciously
     * fail-open — a missing preference must never silence a notification.
     */
    private static boolean emailAllowed(Map<Long, Map<String, UserNotificationPreference>> prefs,
                                        Long userId, String eventKey) {
        Map<String, UserNotificationPreference> byKey = prefs.get(userId);
        if (byKey == null) return true;
        UserNotificationPreference p = eventKey != null ? byKey.get(eventKey) : null;
        if (p == null) p = byKey.get(UserNotificationPreference.ALL_EVENTS);
        return p == null || p.isEmailEnabled();
    }

    /** Tenant rules fully replace global rules for the same eventKey. */
    private List<NotificationEmailRule> resolveRules(String eventKey, Long tenantId) {
        if (eventKey == null) return List.of();
        if (tenantId != null) {
            List<NotificationEmailRule> tenantRules =
                    ruleRepository.findByEventKeyAndTenantIdAndIsActiveTrue(eventKey, tenantId);
            if (!tenantRules.isEmpty()) return tenantRules;
        }
        return ruleRepository.findByEventKeyAndTenantIdIsNullAndIsActiveTrue(eventKey);
    }

    // ── Email building ────────────────────────────────────────────────────

    /**
     * Variables offered to {{placeholder}} merge in configured templates.
     *
     * PRECEDENCE (last put wins):
     *   1. context map from the call site — domain data: commenterName,
     *      stepName, vendorName, taskUrl, ... Shared by every email of
     *      this event.
     *   2. per-user identity + base keys — firstName/fullName are ALWAYS
     *      the addressee's own (recipient in RECIPIENT emails, actor in
     *      ACTOR emails) and cannot be overridden by context; message /
     *      eventName / entity* are stable base keys every template can rely
     *      on even when the call site passed no context.
     */
    private Map<String, String> buildVariables(User u, Map<String, String> context,
                                               String eventKey, String message,
                                               String entityType, Long entityId) {
        Map<String, String> vars = new java.util.HashMap<>(context);
        vars.put("firstName",  u.getFirstName() != null ? u.getFirstName() : "");
        vars.put("fullName",   u.getFullName() != null ? u.getFullName().trim() : "");
        vars.put("message",    message != null ? message : "");
        vars.put("eventKey",   eventKey != null ? eventKey : "");
        vars.put("eventName",  humanize(eventKey));
        vars.put("entityType", entityType != null ? entityType : "");
        vars.put("entityId",   entityId != null ? String.valueOf(entityId) : "");
        vars.put("appUrl",         appBaseUrl);
        vars.put("preferencesUrl", appBaseUrl + "/settings/notifications");
        return vars;
    }

    private String buildRawBody(User u, String message, String eventKey) {
        String name = u.getFirstName() != null ? u.getFirstName() : "there";
        return "<html><body style=\"font-family:Calibri,Arial,sans-serif;color:#0E1F33\">"
                + "<p>Hi " + escape(name) + ",</p>"
                + "<p>" + escape(message != null ? message : humanize(eventKey)) + "</p>"
                + "<p>Please log in to KashiGRC to view the details and take action.</p>"
                + "<p style=\"color:#888;font-size:12px\">You received this because you are "
                + "a participant on this item. Event: " + escape(eventKey) + "</p>"
                + "</body></html>";
    }

    /** "TASK_ASSIGNMENT" → "Task assignment" — decent default subject. */
    private static String humanize(String eventKey) {
        if (eventKey == null || eventKey.isBlank()) return "Notification";
        String s = eventKey.replace('_', ' ').toLowerCase();
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ── History recording ─────────────────────────────────────────────────

    private void recordSkipped(KafkaEventEnvelope env, String eventKey, String entityType,
                               Long entityId, int recipientCount, String reason) {
        log.info("Notification fanout SKIPPED | eventId={} | eventKey={} | reason={}",
                env.getEventId(), eventKey, reason);
        dispatchLogRepository.save(NotificationDispatchLog.builder()
                .eventId(env.getEventId())
                .tenantId(env.getTenantId())
                .eventKey(eventKey != null ? eventKey : "UNKNOWN")
                .entityType(entityType)
                .entityId(entityId)
                .recipientCount(recipientCount)
                .emailsDispatched(0)
                .status(NotificationDispatchLog.Status.SKIPPED)
                .detail(reason)
                .processedAt(LocalDateTime.now())
                .build());
    }

    private void recordFailed(KafkaEventEnvelope env, Exception e) {
        try {
            String detail = e.getMessage() != null && e.getMessage().length() > 500
                    ? e.getMessage().substring(0, 500) : e.getMessage();
            String eventKey = env.getPayload() != null ? str(env.getPayload().get("eventKey")) : null;
            dispatchLogRepository.save(NotificationDispatchLog.builder()
                    .eventId(env.getEventId())
                    .tenantId(env.getTenantId())
                    .eventKey(eventKey != null ? eventKey : "UNKNOWN")
                    .recipientCount(0)
                    .emailsDispatched(0)
                    .status(NotificationDispatchLog.Status.FAILED)
                    .detail(detail)
                    .processedAt(LocalDateTime.now())
                    .build());
        } catch (Exception logEx) {
            log.error("Could not record FAILED dispatch log for eventId={}: {}",
                    env.getEventId(), logEx.getMessage());
        }
    }

    // ── Payload coercion (JSON numbers arrive as Integer/Long/String) ─────

    private static String str(Object o) { return o != null ? o.toString() : null; }

    private static Long asLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(o.toString()); } catch (NumberFormatException e) { return null; }
    }

    private static List<Long> asLongList(Object o) {
        List<Long> out = new ArrayList<>();
        if (o instanceof Iterable<?> it) {
            for (Object e : it) {
                Long v = asLong(e);
                if (v != null) out.add(v);
            }
        }
        return out.stream().distinct().toList();
    }

    /** JSON object → Map<String,String>; values stringified, nulls dropped. */
    private static Map<String, String> asStringMap(Object o) {
        Map<String, String> out = new java.util.HashMap<>();
        if (o instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    out.put(e.getKey().toString(), e.getValue().toString());
                }
            }
        }
        return out;
    }
}