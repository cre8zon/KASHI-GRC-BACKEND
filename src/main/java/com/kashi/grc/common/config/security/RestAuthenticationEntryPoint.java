package com.kashi.grc.common.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashi.grc.common.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Answers an unauthenticated request with 401 + the standard ApiResponse body.
 *
 * WHY THIS EXISTS
 *   SecurityConfig never set an entry point. With a stateless chain and no
 *   formLogin/httpBasic, Spring Security falls back to Http403ForbiddenEntryPoint
 *   — so an EXPIRED token came back as an empty-bodied 403. The client's 401
 *   handler (logout + redirect to sign-in) therefore never ran; every screen
 *   just failed its queries and looked as if the backend were down and the
 *   data gone. A 401 with a machine-readable code fixes that at the source.
 *
 * Codes
 *   AUTH_SESSION_EXPIRED   the bearer token was valid once and has expired
 *   AUTH_TOKEN_INVALID     the bearer token is malformed / tampered / a refresh token
 *   AUTH_REQUIRED          no credentials were sent at all
 *   AUTH_ACCOUNT_SUSPENDED / AUTH_TENANT_SUSPENDED / AUTH_VENDOR_SUSPENDED
 *                          a valid token for an account that may not be used
 *                          any more (AccountAccessService)
 */
@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        Object status = request.getAttribute(JwtTokenProvider.TOKEN_STATUS_ATTR);
        Object block  = request.getAttribute(JwtAuthenticationFilter.ACCOUNT_BLOCK_ATTR);

        String code;
        String message;
        if (block instanceof com.kashi.grc.common.security.AccountAccessService.Verdict v && v.code() != null) {
            // A valid token for an account that may not be used any more:
            // suspended user, tenant or vendor. The client signs out and shows
            // this message on the sign-in page.
            code = v.code();
            message = v.message();
        } else if (status == JwtTokenProvider.TokenStatus.EXPIRED) {
            code = "AUTH_SESSION_EXPIRED";
            message = "Your session has expired. Please sign in again.";
        } else if (status != null) {
            code = "AUTH_TOKEN_INVALID";
            message = "Your session is no longer valid. Please sign in again.";
        } else {
            code = "AUTH_REQUIRED";
            message = "Please sign in to continue.";
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        // Lets the client distinguish "sign in again" from "you lack access"
        // without parsing the body (and survives proxies that strip bodies).
        response.setHeader("X-Auth-Status", code);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(code, message));
    }
}
