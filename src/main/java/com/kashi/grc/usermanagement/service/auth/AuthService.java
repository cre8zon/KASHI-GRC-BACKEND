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
     * Authenticate user. Returns:
     *   - ApiResponse.success(AuthResponse)          on normal login
     *   - ApiResponse.withStatus("PASSWORD_RESET_REQUIRED", ...) on first login
     */
    ApiResponse<?> login(LoginRequest request);

    /** Invalidate session / log logout event */
    void logout(Long userId);

    /** Send password reset link to email */
    void requestPasswordReset(PasswordResetRequest request);

    /** Reset password using token from email */
    void resetPassword(ResetPasswordRequest request);

    ApiResponse<Map<String, Object>> resendInvitation(ResendInvitationRequest req);
}
