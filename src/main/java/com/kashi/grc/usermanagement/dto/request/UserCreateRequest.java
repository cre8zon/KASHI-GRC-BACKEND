package com.kashi.grc.usermanagement.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;
import java.util.Set;

@Data
public class UserCreateRequest {

    @NotBlank
    @Email
    private String email;

    @NotBlank
    private String firstName;

    @NotBlank
    private String lastName;

    private String department;
    private String jobTitle;
    private String phone;
    private Long managerId;
    private Long vendorId;

    @NotNull
    private Long tenantId;

    /** Role IDs to assign at creation */
    private Set<Long> roleIds;
    private String defaultRoleName;
    public Map<String, String> attributes;
    public boolean sendWelcomeEmail = true;
}
