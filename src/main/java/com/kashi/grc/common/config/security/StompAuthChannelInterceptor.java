package com.kashi.grc.common.config.security;

import com.kashi.grc.chat.service.ChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * Who is on the other end of a WebSocket, and which personal topics they may
 * listen to.
 *
 * CONNECT: the browser sends "Authorization: Bearer <access token>" (every
 * socket hook in the frontend already does). A valid token makes the session
 * that user's (StompPrincipal); without one the session stays anonymous —
 * still allowed, so nothing that worked before breaks.
 *
 * SUBSCRIBE to a personal topic needs the matching signed-in user:
 *   /topic/user/{userId}   only that user   (workflow tasks, action items)
 *   /topic/chat/{hmac}     only the user it was made for (ChatService.topic)
 * Any other subscription is unchanged. A refused subscription is dropped
 * quietly (no ERROR frame), so a browser holding an old token is not thrown
 * into a reconnect loop — it simply gets nothing on that topic.
 *
 * Signed-in chat sessions are also what "online" is built from
 * (ChatPresenceService).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final String USER_TOPIC = "/topic/user/";
    private static final String CHAT_TOPIC = "/topic/chat/";

    private final JwtTokenProvider tokens;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor acc = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (acc == null || acc.getCommand() == null) return message;

        if (StompCommand.CONNECT.equals(acc.getCommand())) {
            String header = acc.getFirstNativeHeader("Authorization");
            String token = header != null && header.startsWith("Bearer ") ? header.substring(7).trim() : null;
            if (token != null && !token.isEmpty() && !"null".equals(token) && tokens.validateToken(token)) {
                try {
                    acc.setUser(new StompPrincipal(tokens.getUserId(token), tokens.getTenantId(token)));
                } catch (RuntimeException e) {
                    log.debug("[WS] Could not read the token on connect: {}", e.getMessage());
                }
            }
            return message;
        }

        if (StompCommand.SUBSCRIBE.equals(acc.getCommand())) {
            String dest = acc.getDestination();
            if (dest == null) return message;
            StompPrincipal me = acc.getUser() instanceof StompPrincipal p ? p : null;
            if (dest.startsWith(USER_TOPIC)) {
                if (me == null || !dest.equals(USER_TOPIC + me.userId())) return refuse(dest, me);
            } else if (dest.startsWith(CHAT_TOPIC)) {
                if (me == null || !dest.equals(ChatService.topic(me.userId()))) return refuse(dest, me);
            }
        }
        return message;
    }

    private static Message<?> refuse(String dest, StompPrincipal me) {
        log.debug("[WS] Subscription refused | dest={} | user={}", dest, me == null ? "anonymous" : me.userId());
        return null;
    }
}
