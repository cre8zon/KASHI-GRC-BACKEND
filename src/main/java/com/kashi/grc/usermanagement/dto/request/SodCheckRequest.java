package com.kashi.grc.usermanagement.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SodCheckRequest {
    @NotNull
    public Long userId;
    @NotNull
    public Long proposedRoleId;
    public String contextType;
    public Long contextId;
}
