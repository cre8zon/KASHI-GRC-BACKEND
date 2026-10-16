package com.kashi.grc.firmverification.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.firmverification.service.AuditFirmVerificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * The firm's only touchpoint: the confirmation link in its email.
 *
 * Anonymous at the filter (SecurityConfig lists both POSTs individually, then
 * denies the rest of /v1/firm-verification/public/**) because the firm has no
 * account. The TOKEN is the credential — 32 bytes of SecureRandom, stored only
 * as a SHA-256, single-use, expiring — the same discipline as the trust-centre
 * download links.
 *
 * Both are POST with the token in the body: a token in a URL path ends up in
 * access logs, and mail scanners pre-fetch every GET link in an email.
 */
@RestController
@RequestMapping("/v1/firm-verification/public")
@Tag(name = "Audit firm verification — email confirmation (public)")
@RequiredArgsConstructor
public class FirmVerificationPublicController {

    private final AuditFirmVerificationService service;

    @PostMapping("/preview")
    @Operation(summary = "Details the firm is being asked to confirm. Does not confirm.")
    public ApiResponse<Map<String, Object>> preview(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(service.preview(token(body)));
    }

    @PostMapping("/confirm")
    @Operation(summary = "Confirm — one time")
    public ApiResponse<Map<String, Object>> confirm(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String fwd = req.getHeader("X-Forwarded-For");
        String ip = fwd != null && !fwd.isBlank() ? fwd.split(",")[0].trim() : req.getRemoteAddr();
        return ApiResponse.success(service.confirm(token(body), ip));
    }

    private static String token(Map<String, Object> body) {
        Object t = body == null ? null : body.get("token");
        return t == null ? null : t.toString();
    }
}
