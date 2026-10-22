package com.kashi.grc.common.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashi.grc.common.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * A SIGNED-IN user hitting a URL their side may not reach (e.g. /v1/admin/**
 * without SIDE_SYSTEM). Same JSON shape the controllers' AccessDeniedException
 * handler already returns, so the client sees one consistent 403 — and,
 * crucially, a 403 that is never confused with an expired session (401).
 */
@Component
@RequiredArgsConstructor
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(),
                ApiResponse.error("RBAC_PERMISSION_DENIED", "You do not have permission to perform this action"));
    }
}
