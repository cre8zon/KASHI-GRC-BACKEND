package com.kashi.grc.usermanagement.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AccessReviewResponse {

    private String reviewId;
    private Long tenantId;
    private String reviewType;
    private LocalDateTime generatedAt;
    private ReviewSummary summary;
    private List<ReviewFinding> findings;
    private String exportUrl;

    // ── Nested Classes ────────────────────────────────────────────
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ReviewSummary {
        private Long totalUsers;
        private Long activeUsers;
        private Long inactiveUsers;
        private Long totalRoles;
        private Long totalPermissions;
        private Long sodViolationsFound;
        private Long orphanedAccounts;
        private Long excessivePermissionsFlagged;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ReviewFinding {
        private String type;
        private String severity;
        private String details;
        private Long userId;
        private String userName;
    }
}
