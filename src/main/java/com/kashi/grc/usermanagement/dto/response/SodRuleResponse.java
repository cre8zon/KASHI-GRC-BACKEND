package com.kashi.grc.usermanagement.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SodRuleResponse {

    private Long sodRuleId;
    private Long tenantId;
    private RoleInfoResponse conflictingRole1;
    private RoleInfoResponse conflictingRole2;
    private String contextType;
    private String severity;
    private String enforcementMode;
    private String description;
    private LocalDateTime createdAt;
}
