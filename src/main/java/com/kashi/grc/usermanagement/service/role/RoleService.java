package com.kashi.grc.usermanagement.service.role;

import com.kashi.grc.usermanagement.dto.request.*;
import com.kashi.grc.usermanagement.dto.response.*;
import java.util.Map;

public interface RoleService {
    RoleInfoResponse createRole(RoleCreateRequest request);
    RoleInfoResponse updateRolePermissions(Long roleId, RolePermissionUpdateRequest request);
    Map<String, Object> getRoleHierarchy(Long tenantId, String side);
    UserResponse assignRoleToUser(Long tenantId, Long userId, RoleAssignmentRequest request);
    UserResponse removeRoleFromUser(Long tenantId, Long userId, Long roleId, RoleRemoveRequest request);
    void deleteRole(Long tenantId, Long roleId);
}
