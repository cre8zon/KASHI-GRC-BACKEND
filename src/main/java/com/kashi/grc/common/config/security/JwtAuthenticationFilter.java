package com.kashi.grc.common.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashi.grc.common.config.multitenancy.TenantContext;
import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.security.AccountAccessService;
import org.springframework.http.MediaType;
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
    private final AccountAccessService accountAccess;
    private final ObjectMapper objectMapper;

    /** Request attribute naming why a valid token was refused (see RestAuthenticationEntryPoint). */
    public static final String ACCOUNT_BLOCK_ATTR = "kashi.auth.accountBlock";

    /**
     * Response headers telling the client the organisation is read-only (the app
     * shows a banner). Exposed through CORS in CorsFilter.
     */
    public static final String ACCESS_HEADER       = "X-Account-Access";
    public static final String ACCESS_UNTIL_HEADER = "X-Account-Access-Until";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request);

        JwtTokenProvider.TokenStatus status = StringUtils.hasText(token)
                ? tokenProvider.inspectAccessToken(token) : null;
        // Remembered for RestAuthenticationEntryPoint, so a request that ends
        // up unauthenticated can be answered "session expired" rather than a
        // bare 403 the client cannot tell apart from a permission problem.
        if (status != null) request.setAttribute(JwtTokenProvider.TOKEN_STATUS_ATTR, status);

        AccountAccessService.Verdict refused = null;
        if (status == JwtTokenProvider.TokenStatus.VALID) {
            try {
                Long userId   = tokenProvider.getUserId(token);
                Long tenantId = tokenProvider.getTenantId(token);

                // Populate TenantContext in case the interceptor hasn't run yet
                if (tenantId != null) {
                    TenantContext.setCurrentTenant(tenantId);
                }

                UserDetails userDetails = userDetailsService.loadUserByUsername(String.valueOf(userId));

                // ── Is this account allowed in right now? ─────────────────
                // A valid token only proves who you are. A suspended user,
                // a suspended or closed organisation, or a suspended vendor
                // is refused here, on the next request — not when the token
                // happens to expire. Platform administrators are exempt: they
                // are the ones who suspend and reactivate.
                boolean platform = userDetails.getAuthorities().stream()
                        .anyMatch(a -> "SIDE_SYSTEM".equals(a.getAuthority()));
                AccountAccessService.Verdict v = platform
                        ? AccountAccessService.Verdict.FULL
                        : accountAccess.check(userId, tenantId);
                if (v.blocked()) {
                    // Leave the request unauthenticated: a protected endpoint
                    // answers 401 with this code (the client signs out and
                    // says why); a public one still works.
                    request.setAttribute(ACCOUNT_BLOCK_ATTR, v);
                    log.debug("Account blocked id={} code={}", userId, v.code());
                } else {
                    if (v.readOnly()) {
                        response.setHeader(ACCESS_HEADER, "READ_ONLY");
                        if (v.readOnlyUntil() != null) {
                            response.setHeader(ACCESS_UNTIL_HEADER, v.readOnlyUntil().toString());
                        }
                        if (isWrite(request)) refused = v;
                    }

                    UsernamePasswordAuthenticationToken auth =
                            new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(auth);

                    log.debug("Authenticated user id={} tenant={}", userId, tenantId);
                }
            } catch (Exception e) {
                log.warn("Could not authenticate from JWT: {}", e.getMessage());
                SecurityContextHolder.clearContext();
                refused = null;
            }
        }

        // A write while the organisation is read-only: answered here, outside
        // the try above, so the request is never passed on.
        if (refused != null) {
            SecurityContextHolder.clearContext();
            refuseWrite(response, refused);
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * A request that changes something. Signing out and refreshing stay open
     * (POST /v1/auth/**): a read-only user must still be able to leave.
     */
    private static boolean isWrite(HttpServletRequest request) {
        String m = request.getMethod();
        if ("GET".equalsIgnoreCase(m) || "HEAD".equalsIgnoreCase(m) || "OPTIONS".equalsIgnoreCase(m)) return false;
        return !request.getRequestURI().startsWith("/v1/auth/");
    }

    /** 403, not 401: the session is fine; this one action is not allowed. */
    private void refuseWrite(HttpServletResponse response, AccountAccessService.Verdict v) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(v.code(), v.message()));
    }

    private String extractToken(HttpServletRequest request) {
        // Primary: Authorization header (all normal API calls)
        String bearer = request.getHeader("Authorization");
        if (StringUtils.hasText(bearer) && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        // Fallback: ?token= query param — used by iframe-based preview endpoints
        // (/v1/documents/{id}/stream and /v1/documents/{id}/preview-content) where
        // the browser cannot set custom headers. Only accepted for these specific paths.
        String path = request.getRequestURI();
        if (path.contains("/v1/documents/") && (path.endsWith("/stream") || path.endsWith("/preview-content"))) {
            String qpToken = request.getParameter("token");
            if (StringUtils.hasText(qpToken)) {
                return qpToken;
            }
        }
        return null;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Skip JWT for OPTIONS preflight and ingest endpoint (uses X-Ingest-Token)
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        String path = request.getRequestURI();
        return path.equals("/v1/issues/ingest");
    }
}