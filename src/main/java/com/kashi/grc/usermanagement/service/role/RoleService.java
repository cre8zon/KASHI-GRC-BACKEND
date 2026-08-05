package com.kashi.grc.usermanagement.service.role;

import com.kashi.grc.usermanagement.dto.request.*;
import com.kashi.grc.usermanagement.dto.response.*;
import java.util.Map;

public interface RoleService {
    RoleInfoResponse createRole(RoleCreateRequest request);

    /** Same as createRole, but for the tenant-scoped path — enforces the
     *  caller's own tenant matches, instead of silently substituting it. */
    RoleInfoResponse createRoleForTenant(Long tenantId, RoleCreateRequest request);
    RoleInfoResponse updateRolePermissions(Long roleId, RolePermissionUpdateRequest request);
    Map<String, Object> getRoleHierarchy(Long tenantId, String side);
    UserResponse assignRoleToUser(Long tenantId, Long userId, RoleAssignmentRequest request);
    UserResponse removeRoleFromUser(Long tenantId, Long userId, Long roleId, RoleRemoveRequest request);
    void deleteRole(Long tenantId, Long roleId);
}