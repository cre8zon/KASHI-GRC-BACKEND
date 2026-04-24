package com.kashi.grc.usermanagement.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class VendorAccessGrantRequest {
    @NotNull
    public Long vendorId;
    @NotBlank
    public String accessLevel;
    public String reason;
}
