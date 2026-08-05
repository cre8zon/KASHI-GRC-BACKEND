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
    /** Only meaningful for a SYSTEM-side creator (ignored otherwise — see
     *  RoleServiceImpl.buildAndSaveRole). True = genuinely global (tenant_id
     *  = NULL), available to every tenant. False = scoped to whichever
     *  tenant the request actually targets, which for a SYSTEM caller using
     *  createRoleForTenant may be a SPECIFIC tenant other than their own —
     *  that explicit targeting must not be silently overridden into global. */
    public boolean global;
}