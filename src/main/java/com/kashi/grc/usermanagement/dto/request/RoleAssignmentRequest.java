package com.kashi.grc.usermanagement.dto.request;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.Set;

@Data
public class RoleAssignmentRequest {

    @NotEmpty(message = "At least one role ID is required")
    private Set<Long> roleIds;

    private boolean skipSodCheck = false;
}
