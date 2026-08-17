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
        private String vendorName;
        private String status;
        private Boolean requiresPasswordReset;
        private List<RoleInfo> roles;
        private List<String> permissions;
        private Map<String, String> attributes;

        /**
         * Every tenant this identity may act in. One entry for almost everyone;
         * an external auditor has their firm plus each client they are staffed
         * on. Purely additive — a client that ignores it behaves as before.
         */
        private List<TenantMembershipInfo> memberships;
    }

    @Data
    @Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class TenantMembershipInfo {
        private Long    membershipId;
        private Long    tenantId;
        private String  tenantName;
        /** HOME = own organisation, GUEST = invited external auditor. */
        private String  membershipType;
        private String  firmName;
        private LocalDateTime accessExpiresAt;
        /** True for the tenant this session's token is scoped to. */
        private Boolean active;
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