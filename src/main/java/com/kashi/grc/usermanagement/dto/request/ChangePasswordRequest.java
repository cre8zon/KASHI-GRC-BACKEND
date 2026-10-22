package com.kashi.grc.usermanagement.dto.request;

import com.kashi.grc.common.security.PasswordPolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class ChangePasswordRequest {

    /** Required unless the account is flagged for a forced reset (checked server-side). */
    public String currentPassword;

    @NotBlank(message = "New password is required")
    @Pattern(regexp = PasswordPolicy.REGEX, message = PasswordPolicy.MESSAGE)
    public String newPassword;

    /**
     * Accepted for backwards compatibility and IGNORED. It used to let the
     * client skip the current-password check by sending true; whether a check
     * is required is now decided from the user's own passwordResetRequired flag.
     */
    public boolean isFirstLoginReset;
}
