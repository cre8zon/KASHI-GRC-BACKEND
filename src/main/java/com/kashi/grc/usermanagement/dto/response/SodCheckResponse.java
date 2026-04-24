package com.kashi.grc.usermanagement.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SodCheckResponse {

    private String checkResult;
    private Long userId;
    private Long proposedRoleId;
    private String proposedRoleName;
    private Boolean safeToAssign;
    private List<ViolationInfo> violations;
    private String recommendation;

    // ── Nested Classes ────────────────────────────────────────────
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ViolationInfo {
        private Long sodRuleId;
        private String severity;
        private String enforcementMode;
        private String description;
        private String contextType;
        private Long contextId;
        private ConflictingRoleInfo conflictingWith;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ConflictingRoleInfo {
        private Long existingRoleId;
        private String existingRoleName;
    }
}
