package com.kashi.grc.usermanagement.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Set;

@Data
public class RoleCreateRequest {
    @NotBlank
    public String name;
    @NotBlank
    public String side;
    public String level;
    public String description;
    public boolean isSystem;
    public Set<Long> permissionIds;
}
