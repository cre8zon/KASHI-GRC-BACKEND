package com.kashi.grc.usermanagement.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ChangePasswordRequest {
    public String currentPassword;
    @NotBlank
    @Size(min = 12)
    public String newPassword;
    public boolean isFirstLoginReset;
}
