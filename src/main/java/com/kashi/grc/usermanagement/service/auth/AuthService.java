package com.kashi.grc.usermanagement.service.auth;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.usermanagement.dto.request.LoginRequest;
import com.kashi.grc.usermanagement.dto.request.PasswordResetRequest;
import com.kashi.grc.usermanagement.dto.request.ResendInvitationRequest;
import com.kashi.grc.usermanagement.dto.request.ResetPasswordRequest;
import com.kashi.grc.usermanagement.dto.response.AuthResponse;
import jakarta.validation.Valid;

import java.util.Map;

public interface AuthService {

    /**
     * Re-issues the session for another tenant this identity is a member of.
     * The only way a token can name a tenant other than the user's home one,
     * which is what makes an external auditor's GUEST membership usable.
     */
    ApiResponse<?> switchTenant(Long userId, Long tenantId);

    /**
     * Authenticate user. Returns:
     *   - ApiResponse.success(AuthResponse)          on normal login
     *   - ApiResponse.withStatus("PASSWORD_RESET_REQUIRED", ...) on first login
     */
    ApiResponse<?> login(LoginRequest request);

    /**
     * Exchanges a refresh token for a new session in the given tenant (or the
     * user's home tenant when null). Re-checks account status and tenant
     * membership exactly as login/switch-tenant do.
     */
    ApiResponse<?> refresh(String refreshToken, Long tenantId);

    /** End a session: revokes the presented refresh token (if any) and logs the event. */
    void logout(Long userId, String refreshToken);

    /** Send password reset link to email */
    void requestPasswordReset(PasswordResetRequest request);

    /** Reset password using token from email */
    void resetPassword(ResetPasswordRequest request);

    ApiResponse<Map<String, Object>> resendInvitation(ResendInvitationRequest req);
}