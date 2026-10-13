package com.kashi.grc.usermanagement.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.usermanagement.dto.request.LoginRequest;
import com.kashi.grc.usermanagement.dto.request.PasswordResetRequest;
import com.kashi.grc.usermanagement.dto.request.ResendInvitationRequest;
import com.kashi.grc.usermanagement.dto.request.ResetPasswordRequest;
import com.kashi.grc.usermanagement.service.auth.AuthService;
import com.kashi.grc.common.util.UtilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Authentication endpoints — all public (no JWT required).
 * POST /v1/auth/login
 * POST /v1/auth/logout
 * POST /v1/auth/request-password-reset
 * POST /v1/auth/reset-password
 */
@Slf4j
@RestController
@RequestMapping("/v1/auth")
@Tag(name = "Authentication", description = "Login, logout, and password management")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final UtilityService utilityService;

    @PostMapping("/login")
    @Operation(summary = "Authenticate user and return JWT token")
    public ResponseEntity<ApiResponse<?>> login(@Valid @RequestBody LoginRequest request) {
        ApiResponse<?> response = authService.login(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/switch-tenant")
    @Operation(summary = "Re-issue the session scoped to another tenant this user belongs to")
    public ResponseEntity<ApiResponse<?>> switchTenant(@RequestBody Map<String, Object> body) {
        Long userId   = utilityService.getLoggedInDataContext().getId();
        Object raw    = body.get("tenantId");
        Long tenantId = raw instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(raw));
        return ResponseEntity.ok(authService.switchTenant(userId, tenantId));
    }

    @PostMapping("/logout")
    @Operation(summary = "Invalidate user session")
    public ResponseEntity<ApiResponse<Map<String, Object>>> logout(@RequestParam Long userId) {
        authService.logout(userId);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "message", "Logged out successfully",
                "logged_out_at", LocalDateTime.now()
        )));
    }

    @PostMapping("/request-password-reset")
    @Operation(summary = "Send password reset link to email")
    public ResponseEntity<ApiResponse<Map<String, Object>>> requestPasswordReset(
            @Valid @RequestBody PasswordResetRequest request) {
        authService.requestPasswordReset(request);
        // Always return success to prevent user enumeration
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "message", "Password reset link sent to your email",
                "email", request.getEmail(),
                "expires_in_minutes", 15
        )));
    }

    @PostMapping("/reset-password")
    @Operation(summary = "Reset password using token from email")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "message", "Password reset successfully",
                "reset_at", LocalDateTime.now()
        )));
    }

    /**
     * Regenerate a user's temporary password.
     *
     * The summary has said "Platform Admin" since the day it was written and
     * nothing enforced it, at either layer. AuthServiceImpl.resendInvitation
     * now carries the real checks - USER_EDIT, the caller's tenant, and who
     * the new password is allowed to reach - because that is where the target
     * user is loaded and its tenant is known.
     *
     * The class javadoc above still says "all public (no JWT required)". That
     * was true of login and the reset-token pair and has never been true of
     * this one; SecurityConfig decides what is public, not the comment.
     */
    @PostMapping("/resend-invitation")
    @Operation(summary = "Regenerate temp password and optionally resend welcome email — "
            + "requires USER_EDIT and the target user must be in your own tenant")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resendInvitation(
            @Valid @RequestBody ResendInvitationRequest req) {
        ApiResponse<Map<String, Object>> response = authService.resendInvitation(req);
        return ResponseEntity.ok(response);
    }
}