package com.kashi.grc.usermanagement.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ImpersonationResponse {

    private String impersonationSessionId;
    private UserRef admin;
    private UserRef targetUser;
    private LocalDateTime startedAt;
    private LocalDateTime expiresAt;
    private LocalDateTime endedAt;
    private Boolean readOnly;
    private String impersonationToken;
    private Integer durationMinutes;
    private Long actionsPerformed;

    // ── Nested Class ──────────────────────────────────────────────
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class UserRef {
        private Long userId;
        private String name;
        private String role;
    }
}
