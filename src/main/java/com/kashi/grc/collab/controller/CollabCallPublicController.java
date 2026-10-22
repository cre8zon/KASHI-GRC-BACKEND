package com.kashi.grc.collab.controller;

import com.kashi.grc.collab.service.CollabCallLinkService;
import com.kashi.grc.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Guest entry to in-app calls — no account, no session.
 *
 *   GET  /v1/collab/public/call-links/{code}                  what the call is; whether guests may ask to join
 *   POST /v1/collab/public/call-links/{code}/knock            { name, email?, website (honeypot) } → { requestId, secret }
 *   POST /v1/collab/public/call-links/requests/{id}/status    { secret } → WAITING | ADMITTED (+ join token) | DENIED | …
 *   POST /v1/collab/public/call-links/requests/{id}/leave     { secret }
 *
 * Opened one by one in SecurityConfig with a denyAll behind them. Nothing here
 * reads a tenant from the caller: the link code decides it. The secret travels
 * in a POST body, never the URL, so it stays out of access logs.
 */
@RestController
@RequestMapping("/v1/collab/public/call-links")
@RequiredArgsConstructor
@Tag(name = "Collaboration call links (public)", description = "Guests asking to join an in-app call")
public class CollabCallPublicController {

    private final CollabCallLinkService service;

    @GetMapping("/{code}")
    @Operation(summary = "The call behind a link, for its join page")
    public ResponseEntity<ApiResponse<Map<String, Object>>> info(@PathVariable String code, HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.success(service.publicInfo(code, clientIp(http))));
    }

    @PostMapping("/{code}/knock")
    @Operation(summary = "Ask to join as a guest")
    public ResponseEntity<ApiResponse<Map<String, Object>>> knock(@PathVariable String code, @RequestBody Map<String, Object> body,
                                                                  HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.success(service.knock(code, body, clientIp(http))));
    }

    @PostMapping("/requests/{id}/status")
    @Operation(summary = "A guest's request: still waiting, refused, or let in (with a join token)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.success(service.guestStatus(id, body)));
    }

    @PostMapping("/requests/{id}/leave")
    @Operation(summary = "Stop waiting")
    public ResponseEntity<ApiResponse<Map<String, Object>>> leave(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.success(service.guestLeave(id, body)));
    }

    /** X-Forwarded-For first: behind the load balancer getRemoteAddr is the balancer. */
    private static String clientIp(HttpServletRequest http) {
        String fwd = http.getHeader("X-Forwarded-For");
        return fwd != null && !fwd.isBlank() ? fwd.split(",")[0].trim() : http.getRemoteAddr();
    }
}
