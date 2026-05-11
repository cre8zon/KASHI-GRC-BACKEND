package com.kashi.grc.common.config.web;

import com.kashi.grc.common.util.UtilityService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Clears the per-request user cache held in UtilityService.REQUEST_USER_CACHE
 * after every HTTP request completes.
 *
 * WHY THIS EXISTS:
 *   UtilityService.getLoggedInDataContext() caches the loaded User entity in a
 *   ThreadLocal so that repeated calls within a single request (bootstrap, nav,
 *   branding, widgets, feature flags, etc.) only hit the DB once instead of 6–8×.
 *
 *   Tomcat reuses threads across requests. Without explicit cleanup the cached
 *   User from request N would leak into request N+1 on the same thread, serving
 *   a stale (or wrong-tenant) user object. This filter prevents that.
 *
 * ORDERING:
 *   HIGHEST_PRECEDENCE + 1 — runs before the JWT filter so the cache is always
 *   cleared even if the request fails authentication or throws before reaching
 *   the controller.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestUserCacheFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } finally {
            // Always clear — even if the request threw an exception.
            // This is the only safe place to do it because the thread
            // returns to Tomcat's pool after this finally block.
            UtilityService.clearRequestCache();
        }
    }
}
