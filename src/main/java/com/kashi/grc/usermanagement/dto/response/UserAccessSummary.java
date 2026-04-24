package com.kashi.grc.usermanagement.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserAccessSummary {

    private Long userId;
    private String email;
    private String fullName;
    private String status;
    private TenantRef tenant;
    private List<RoleInfoResponse> roles;
    private List<String> permissions;
    private Map<String, String> attributes;
    private List<VendorAccessInfo> vendorAccess;
    private DelegationCounts activeDelegations;
    private AssignmentCounts activeAssignments;
    private SodStatus sodStatus;
    private LocalDateTime lastLogin;
    private LocalDateTime passwordLastChanged;
    private LocalDateTime passwordExpiresAt;

    // ── Nested Classes ────────────────────────────────────────────
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TenantRef {
        private Long tenantId;
        private String tenantName;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class VendorAccessInfo {
        private Long vendorId;
        private String vendorName;
        private String accessLevel;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DelegationCounts {
        private Long delegatedToMe;
        private Long delegatedByMe;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AssignmentCounts {
        private Long workflows;
        private Long tasks;
        private Long assessments;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SodStatus {
        private Long violations;
        private Long warnings;
    }
}
