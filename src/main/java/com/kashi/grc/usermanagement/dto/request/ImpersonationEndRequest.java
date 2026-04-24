package com.kashi.grc.usermanagement.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ImpersonationEndRequest {
    @NotBlank
    public String impersonationSessionId;
}
