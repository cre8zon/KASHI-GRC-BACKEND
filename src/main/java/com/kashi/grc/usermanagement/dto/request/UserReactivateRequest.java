package com.kashi.grc.usermanagement.dto.request;

import lombok.Data;

@Data
public class UserReactivateRequest {
    public boolean restorePreviousRoles = true;
    public boolean sendReactivationEmail = true;
    public boolean resetPasswordRequired = true;
}
