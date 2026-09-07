package com.kashi.grc.usermanagement.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserResponse {

    private Long userId;
    private Long tenantId, managerId, vendorId;

    /**
     * For an invited external auditor: which audit firm they belong to.
     *
     * Null for your own staff. Without it a lookup can only show a name and an
     * email, and "Sneha Kapoor" says nothing about whether she is yours or which
     * of several invited firms she came from.
     */
    private Long   firmTenantId;
    private String firmName;
    private String email;
    private String firstName;
    private String lastName;
    private String fullName;
    private String department;
    private String jobTitle;
    private String phone;
    private String status;
    private String timezone;
    private LocalDateTime lastLogin;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<AuthResponse.RoleInfo> roles;
    private Map<String, String> attributes;
    private boolean passwordResetRequired;
    private String temporaryPassword;
}