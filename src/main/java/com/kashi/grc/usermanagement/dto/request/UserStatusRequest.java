package com.kashi.grc.usermanagement.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UserStatusRequest {
    @NotBlank
    public String status;
    public String reason;
    public boolean effectiveImmediately = true;
    public String suspendUntil;
}
