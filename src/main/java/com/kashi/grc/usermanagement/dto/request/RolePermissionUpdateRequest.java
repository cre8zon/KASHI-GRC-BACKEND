package com.kashi.grc.usermanagement.dto.request;

import lombok.Data;

import java.util.Set;

@Data
public class RolePermissionUpdateRequest {
    public Set<Long> addPermissionIds;
    public Set<Long> removePermissionIds;
}
