package com.kashi.grc.usermanagement.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SodRuleCreateRequest {
    @NotNull
    public Long conflictingRole1Id;
    @NotNull
    public Long conflictingRole2Id;
    public String contextType;
    public String description;
    public String severity = "HIGH";
    public String enforcementMode = "HARD_BLOCK";
}
