package com.kashi.grc.common.config.security;

import java.security.Principal;

/**
 * Who a WebSocket (STOMP) session belongs to — set once, when the browser
 * connects with a valid access token (StompAuthChannelInterceptor). The name
 * is the user id, so Spring's user destinations would resolve by id too.
 */
public record StompPrincipal(Long userId, Long tenantId) implements Principal {
    @Override
    public String getName() {
        return String.valueOf(userId);
    }
}
