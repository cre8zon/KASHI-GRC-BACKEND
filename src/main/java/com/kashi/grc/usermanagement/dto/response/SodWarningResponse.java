package com.kashi.grc.usermanagement.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SodWarningResponse {

    private Long ruleId;
    private String conflictingRole1;
    private String conflictingRole2;
    private String severity;
    private String recommendation;
    private String enforcementMode;
}
