package com.kashi.grc.trustcenter.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.trustcenter.service.TrustCenterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * The unauthenticated surface.
 *
 * ── EVERY METHOD HERE RUNS WITH NO SESSION ────────────────────────────────
 * There is no logged-in user and no tenant context. The tenant is derived from
 * the slug or the token, inside the service, and NEVER from anything the caller
 * sends. Any future method added to this class has to hold to that.
 *
 * ── SecurityConfig ────────────────────────────────────────────────────────
 * Mirrors the /v1/content/public/** pattern already in that file — endpoints
 * listed individually rather than a blanket permitAll on the prefix, so an
 * endpoint added here later is CLOSED until someone deliberately opens it:
 *
 *   .requestMatchers(HttpMethod.GET,  "/v1/trust/public/*").permitAll()
 *   .requestMatchers(HttpMethod.POST, "/v1/trust/public/*&#47;request-access").permitAll()
 *   .requestMatchers(HttpMethod.GET,  "/v1/trust/public/download/**").permitAll()
 *
 * The download route is permitAll at the filter only because the TOKEN is the
 * credential and it is validated in the service on every call. That is
 * authorisation, just not session-based.
 */
@Slf4j
@RestController
@RequestMapping("/v1/trust/public")
@RequiredArgsConstructor
@Tag(name = "Trust center (public)", description = "Read by prospects with no account")
public class TrustPublicController {

    private final TrustCenterService service;

    @GetMapping("/{slug}")
    @Operation(summary = "A published trust page")
    public ResponseEntity<ApiResponse<Map<String, Object>>> page(@PathVariable String slug) {
        return ResponseEntity.ok(ApiResponse.success(service.publicPage(slug)));
    }

    @PostMapping("/{slug}/request-access")
    @Operation(summary = "Ask for a gated document")
    public ResponseEntity<ApiResponse<Map<String, Object>>> requestAccess(
            @PathVariable String slug,
            @RequestBody Map<String, Object> body,
            HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.success(
                service.requestAccess(slug, body, clientIp(http), http.getHeader("User-Agent"))));
    }

    /**
     * Resolves a token to an internal document id.
     *
     * Returns the id rather than streaming the bytes, so the existing document
     * service stays the single place that knows how to fetch from storage —
     * the same reason the training player resolves a playback URL rather than
     * proxying the video.
     */
    @GetMapping("/download/{token}/{documentId}")
    @Operation(summary = "Fetch a granted document — the token is checked on every call")
    public ResponseEntity<ApiResponse<Map<String, Object>>> download(
            @PathVariable String token,
            @PathVariable Long documentId,
            HttpServletRequest http) {
        Long internalId = service.resolveDownload(token, documentId, clientIp(http));
        return ResponseEntity.ok(ApiResponse.success(Map.of("documentId", internalId)));
    }

    /**
     * The originating address, honouring one proxy hop.
     *
     * getRemoteAddr behind a load balancer returns the balancer, which would
     * make every download log identical and useless for the dispute this log
     * exists to settle.
     */
    private String clientIp(HttpServletRequest http) {
        String fwd = http.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) return fwd.split(",")[0].trim();
        return http.getRemoteAddr();
    }
}
