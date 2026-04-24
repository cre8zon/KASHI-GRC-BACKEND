package com.kashi.grc.usermanagement.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Full authentication response matching the API spec:
 * { user: {...}, session: { token, expires_at, refresh_token } }
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuthResponse {

    private UserInfo user;
    private SessionInfo session;

    // ── Sub-objects ───────────────────────────────────────────────
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class UserInfo {
        private Long userId;
        private String email;
        private String fullName;
        private Long tenantId;
        private String tenantName;
        private Long vendorId;
        private String status;
        private Boolean requiresPasswordReset;
        private List<RoleInfo> roles;
        private List<String> permissions;
        private Map<String, String> attributes;
    }

    @Data
    @Builder
    public static class RoleInfo {
        private Long roleId;
        private String roleName;
        private String side;
        private String level;
    }

    @Data
    @Builder
    public static class SessionInfo {
        private String token;
        private LocalDateTime expiresAt;
        private String refreshToken;
    }

    // ── Used for PASSWORD_RESET_REQUIRED response ─────────────────
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PasswordResetRequired {
        private Long userId;
        private String message;
        private String tempToken;
        private PasswordPolicy passwordPolicy;
    }

    @Data
    @Builder
    public static class PasswordPolicy {
        private int minLength;
        private boolean requireUppercase;
        private boolean requireLowercase;
        private boolean requireNumbers;
        private boolean requireSpecialChars;
    }
}
