package com.kashi.grc.usermanagement.dto.request;

import lombok.Data;

@Data
public class UserUpdateRequest {
    private String firstName;
    private String lastName;
    private String department;
    private String jobTitle;
    private String phone;
    private Long managerId;
    private String timezone;
}
