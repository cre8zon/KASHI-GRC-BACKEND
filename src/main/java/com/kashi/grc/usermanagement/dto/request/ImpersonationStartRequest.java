package com.kashi.grc.usermanagement.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ImpersonationStartRequest {
    @NotNull
    public Long targetUserId;
    public String reason;
    public int durationMinutes = 30;
    public boolean readOnly = true;
}
