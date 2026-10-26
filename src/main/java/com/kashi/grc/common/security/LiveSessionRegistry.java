package com.kashi.grc.common.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Every open live connection (the STOMP socket behind chat, notifications and
 * workflow updates), and who it belongs to — so a suspended account is
 * disconnected NOW rather than on its next click.
 *
 * Before this, suspending someone refused their next HTTP request and their
 * next subscription, but a socket already open stayed open: a chat left on
 * screen kept receiving messages until they touched something.
 *
 *   • WebSocketConfig installs this as a handler decorator, so every
 *     connection is known by its session id from the moment it opens.
 *   • StompAuthChannelInterceptor binds the session to its user on CONNECT.
 *   • closeUserAfterCommit — the user was suspended or deactivated: their
 *     tabs are told why on /topic/user/{id} (the app signs out with the right
 *     message), then every connection of theirs is closed.
 *   • sweepAfterCommit — an organisation or a vendor changed status: every
 *     open connection is re-judged and the blocked ones are closed.
 *   • A sweep every two minutes does the same, for what has no event of its
 *     own: an organisation's read-only grace running out.
 *
 * In memory, per instance — like the simple broker itself. With several
 * backend instances each closes its own connections; the access check on the
 * next request and subscription remains the backstop everywhere.
 */
@Slf4j
@Component
public class LiveSessionRegistry implements WebSocketHandlerDecoratorFactory {

    /** Sent on /topic/user/{id} before the connection is closed. */
    public static final String EVENT = "SESSION_ENDED";

    private record Live(WebSocketSession session, Long userId, Long tenantId) {}

    private final Map<String, WebSocketSession> open = new ConcurrentHashMap<>();
    private final Map<String, Live> bound = new ConcurrentHashMap<>();

    private final ObjectProvider<SimpMessagingTemplate> messaging;
    private final ObjectProvider<AccountAccessService> accountAccess;

    public LiveSessionRegistry(ObjectProvider<SimpMessagingTemplate> messaging,
                               ObjectProvider<AccountAccessService> accountAccess) {
        this.messaging = messaging;
        this.accountAccess = accountAccess;
    }

    // ── Tracking ─────────────────────────────────────────────────────────────

    @Override
    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                open.put(session.getId(), session);
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                open.remove(session.getId());
                bound.remove(session.getId());
                super.afterConnectionClosed(session, status);
            }
        };
    }

    /** CONNECT accepted for this user (StompAuthChannelInterceptor). */
    public void bind(String sessionId, Long userId, Long tenantId) {
        if (sessionId == null || userId == null) return;
        WebSocketSession s = open.get(sessionId);
        if (s != null) bound.put(sessionId, new Live(s, userId, tenantId));
    }

    // ── Closing ──────────────────────────────────────────────────────────────

    /** After the current transaction commits: tell this user's tabs why, then disconnect them. */
    public void closeUserAfterCommit(Long userId, String code) {
        afterCommit(() -> closeUser(userId, code));
    }

    /** After the current transaction commits: re-judge every open connection. */
    public void sweepAfterCommit() {
        // Cached verdicts may have been refilled between the change and the commit.
        afterCommit(() -> { AccountAccessService.invalidateAll(); sweep(); });
    }

    public void closeUser(Long userId, String code) {
        if (userId == null) return;
        boolean any = bound.values().stream().anyMatch(l -> userId.equals(l.userId()));
        if (!any) return;
        tell(userId, code);
        // A moment for the message to leave before the connection goes.
        CompletableFuture.runAsync(() -> bound.values().stream()
                        .filter(l -> userId.equals(l.userId()))
                        .forEach(this::close),
                CompletableFuture.delayedExecutor(750, TimeUnit.MILLISECONDS));
    }

    @Scheduled(fixedDelay = 2 * 60 * 1000, initialDelay = 2 * 60 * 1000)
    public void sweep() {
        AccountAccessService access = accountAccess.getIfAvailable();
        if (access == null || bound.isEmpty()) return;
        Map<Long, String> blocked = new ConcurrentHashMap<>();
        for (Live l : bound.values()) {
            if (blocked.containsKey(l.userId())) continue;
            try {
                AccountAccessService.Verdict v = access.check(l.userId(), l.tenantId());
                if (v.blocked()) blocked.put(l.userId(), v.code() == null ? "AUTH_TOKEN_INVALID" : v.code());
            } catch (Exception e) {
                log.debug("[LIVE-SESSIONS] Check failed for userId={} — left open | {}", l.userId(), e.getMessage());
            }
        }
        blocked.forEach(this::closeUser);
        if (!blocked.isEmpty()) log.info("[LIVE-SESSIONS] Disconnected {} blocked user(s)", blocked.size());
    }

    private void tell(Long userId, String code) {
        try {
            SimpMessagingTemplate t = messaging.getIfAvailable();
            if (t != null) t.convertAndSend("/topic/user/" + userId, Map.of("type", EVENT, "code", code));
        } catch (Exception e) {
            log.debug("[LIVE-SESSIONS] Could not notify userId={} | {}", userId, e.getMessage());
        }
    }

    private void close(Live l) {
        try {
            if (l.session().isOpen()) l.session().close(CloseStatus.POLICY_VIOLATION.withReason("Account access ended"));
        } catch (Exception e) {
            log.debug("[LIVE-SESSIONS] Close failed for session={} | {}", l.session().getId(), e.getMessage());
        } finally {
            bound.remove(l.session().getId());
            open.remove(l.session().getId());
        }
    }

    private static void afterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { r.run(); }
            });
        } else {
            r.run();
        }
    }
}
