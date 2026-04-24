package com.kashi.grc.usermanagement.dto.request;

import lombok.Data;

@Data
public class PasswordPolicyRequest {
    public int minLength = 12;
    public int maxLength = 128;
    public boolean requireUppercase = true;
    public boolean requireLowercase = true;
    public boolean requireNumbers = true;
    public boolean requireSpecialChars = true;
    public int expirationDays = 90;
    public int passwordHistoryCount = 5;
    public int lockoutThreshold = 5;
    public int lockoutDurationMinutes = 30;
}
