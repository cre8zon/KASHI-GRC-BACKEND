package com.kashi.grc.usermanagement.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AccessEvaluationResponse {

    private String accessDecision;
    private Long userId;
    private String action;
    private String resource;
    private Long evaluationTimeMs;
    private EvaluationDetails evaluationDetails;
    private List<DenialReason> denialReasons;

    // ── Nested Classes ────────────────────────────────────────────
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class EvaluationDetails {
        private RbacCheck rbacCheck;
        private AbacCheck abacCheck;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RbacCheck {
        private Boolean hasPermission;
        private String permissionCode;
        private String grantedByRole;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AbacCheck {
        private Boolean passed;
        private List<ConditionResult> conditionsEvaluated;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ConditionResult {
        private String condition;
        private String note;
        private Boolean result;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DenialReason {
        private String type;
        private String message;
        private String condition;
    }
}
