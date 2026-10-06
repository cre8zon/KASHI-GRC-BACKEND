package com.kashi.grc.chat.service;

import com.kashi.grc.common.config.security.StompPrincipal;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Who is online in chat — live, from the WebSocket itself.
 *
 * A person is online while at least one of their browser tabs holds a signed-in
 * socket subscribed to their own chat topic (only chat users get one, so
 * invited auditors never appear). The frontend keeps that socket open on every
 * page, not just Chat. Several tabs count once; the last tab closing makes
 * them offline.
 *
 * Changes are pushed to everyone online in the same organisation as
 *   { type: "presence", userId, online, lastSeenAt? }
 * on their private chat topic — ids and a time only, like every chat push.
 * Going offline waits a few seconds first, so reloading a page does not
 * flicker "offline → online" for everyone else.
 *
 * Held in memory: one backend process (the same assumption as the in-memory
 * STOMP broker). After a restart everyone reconnects within seconds; "last
 * seen" from before the restart is forgotten.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatPresenceService {

    private static final long OFFLINE_GRACE_SECONDS = 8;
    private static final int MAX_LAST_SEEN = 50_000;

    private final SimpMessagingTemplate messaging;

    private record Key(Long tenantId, Long userId) {}

    /** Guarded by this: chat sessions, open-session counts, who others were told is online. */
    private final Map<String, Key> sessions = new HashMap<>();
    private final Map<Key, Integer> open = new HashMap<>();
    private final Set<Key> announced = new HashSet<>();
    private final Map<Key, LocalDateTime> lastSeen = new HashMap<>();

    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "chat-presence");
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    void stop() {
        timer.shutdownNow();
    }

    /** A signed-in tab subscribed to its own chat topic: that person is here. */
    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        if (!(event.getUser() instanceof StompPrincipal p) || p.userId() == null || p.tenantId() == null) return;
        StompHeaderAccessor acc = StompHeaderAccessor.wrap(event.getMessage());
        String dest = acc.getDestination();
        String sessionId = acc.getSessionId();
        if (dest == null || sessionId == null || !dest.equals(ChatService.topic(p.userId()))) return;
        Key k = new Key(p.tenantId(), p.userId());
        boolean tell;
        synchronized (this) {
            if (sessions.containsKey(sessionId)) return;          // re-subscribe on the same socket
            sessions.put(sessionId, k);
            open.merge(k, 1, Integer::sum);
            tell = announced.add(k);
        }
        if (tell) broadcast(k, true, null);
    }

    /** A tab closed or lost its connection. */
    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        Key k;
        synchronized (this) {
            k = sessions.remove(event.getSessionId());
            if (k == null) return;
            int left = open.merge(k, -1, Integer::sum);
            if (left > 0) return;
            open.remove(k);
            if (lastSeen.size() > MAX_LAST_SEEN) lastSeen.clear();
            lastSeen.put(k, LocalDateTime.now());
        }
        timer.schedule(() -> settleOffline(k), OFFLINE_GRACE_SECONDS, TimeUnit.SECONDS);
    }

    private void settleOffline(Key k) {
        LocalDateTime seen;
        synchronized (this) {
            if (open.containsKey(k) || !announced.remove(k)) return;   // came back in time
            seen = lastSeen.get(k);
        }
        broadcast(k, false, seen);
    }

    /** Everyone online in the organisation, and when the others were last seen. */
    public Map<String, Object> snapshot(Long tenantId) {
        List<Long> online = new ArrayList<>();
        Map<String, Object> seen = new LinkedHashMap<>();
        synchronized (this) {
            for (Key k : announced) if (k.tenantId().equals(tenantId)) online.add(k.userId());
            lastSeen.forEach((k, at) -> {
                if (k.tenantId().equals(tenantId) && !announced.contains(k)) seen.put(String.valueOf(k.userId()), at);
            });
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("online", online);
        out.put("lastSeen", seen);
        return out;
    }

    private void broadcast(Key subject, boolean online, LocalDateTime lastSeenAt) {
        List<Long> to = new ArrayList<>();
        synchronized (this) {
            for (Key k : announced) {
                if (k.tenantId().equals(subject.tenantId()) && !k.userId().equals(subject.userId())) to.add(k.userId());
            }
        }
        if (to.isEmpty()) return;
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("type", "presence");
        ev.put("userId", subject.userId());
        ev.put("online", online);
        if (lastSeenAt != null) ev.put("lastSeenAt", lastSeenAt.toString());
        for (Long uid : to) {
            try { messaging.convertAndSend(ChatService.topic(uid), ev); }
            catch (RuntimeException e) { log.debug("[CHAT] Presence push failed | {}", e.getMessage()); }
        }
    }
}
