package com.kashi.grc.usermanagement.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DelegationResponse {

    private Long delegationId;
    private UserRef delegator;
    private UserRef delegatee;
    private ScopeInfo scope;
    private LocalDateTime startDate;
    private LocalDateTime endDate;
    private LocalDateTime revokedAt;
    private List<String> permissionsDelegated;
    private String status;
    private String notes;
    private Long daysRemaining;

    // ── Nested Classes ────────────────────────────────────────────
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class UserRef {
        private Long userId;
        private String name;
        private String role;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ScopeInfo {
        private String type;
        private String name;
        private Long id;
    }
}
