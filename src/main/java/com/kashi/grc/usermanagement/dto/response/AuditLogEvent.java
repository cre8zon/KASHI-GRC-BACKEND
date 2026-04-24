package com.kashi.grc.usermanagement.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuditLogEvent {

    private Long logId;
    private Long tenantId;
    private Long entityId;
    private String entityName;
    private String actionType;
    private Object oldValue;
    private Object newValue;
    private UserRef performedBy;
    private LocalDateTime performedAt;

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
