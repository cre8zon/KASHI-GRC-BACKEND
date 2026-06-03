package com.kashi.grc.common.config.security;

import com.kashi.grc.common.config.multitenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider tokenProvider;
    private final CustomUserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request);

        if (StringUtils.hasText(token) && tokenProvider.validateToken(token)) {
            try {
                Long userId   = tokenProvider.getUserId(token);
                Long tenantId = tokenProvider.getTenantId(token);

                // Populate TenantContext in case the interceptor hasn't run yet
                if (tenantId != null) {
                    TenantContext.setCurrentTenant(tenantId);
                }

                UserDetails userDetails = userDetailsService.loadUserByUsername(String.valueOf(userId));

                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);

                log.debug("Authenticated user id={} tenant={}", userId, tenantId);
            } catch (Exception e) {
                log.warn("Could not authenticate from JWT: {}", e.getMessage());
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        // Primary: Authorization header (all normal API calls)
        String bearer = request.getHeader("Authorization");
        if (org.springframework.util.StringUtils.hasText(bearer) && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        // Fallback: ?token= query param — used by iframe-based preview endpoints
        // (/v1/documents/{id}/stream and /v1/documents/{id}/preview-content) where
        // the browser cannot set custom headers. Only accepted for these specific paths.
        String path = request.getRequestURI();
        if (path.contains("/v1/documents/") && (path.endsWith("/stream") || path.endsWith("/preview-content"))) {
            String qpToken = request.getParameter("token");
            if (org.springframework.util.StringUtils.hasText(qpToken)) {
                return qpToken;
            }
        }
        return null;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "OPTIONS".equalsIgnoreCase(request.getMethod());
    }
}