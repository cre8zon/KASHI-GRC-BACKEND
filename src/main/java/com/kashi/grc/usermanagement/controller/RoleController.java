package com.kashi.grc.usermanagement.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.util.Constants;
import com.kashi.grc.usermanagement.domain.Permission;
import com.kashi.grc.usermanagement.dto.request.*;
import com.kashi.grc.usermanagement.dto.response.*;
import com.kashi.grc.usermanagement.repository.PermissionRepository;
import com.kashi.grc.usermanagement.service.role.RoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Role management (RBAC) endpoints.
 * POST   /v1/roles                               Create role (legacy — tenantId from JWT)
 * POST   /v1/tenants/{tenantId}/roles            Create role scoped to tenant (new)
 * DELETE /v1/tenants/{tenantId}/roles/{roleId}   Delete tenant role (new)
 * PUT    /v1/roles/{roleId}/permissions          Update role permissions
 * GET    /v1/tenants/{tenantId}/roles/hierarchy  Get role hierarchy (with optional ?side=X)
 * POST   /v1/roles/users/{tenantId}/{userId}/assign    Assign role(s) to user
 * DELETE /v1/roles/users/{tenantId}/{userId}/remove/{roleId} Remove role from user
 * POST   /v1/users/{userId}/roles                Assign role(s) to user (alt path)
 * DELETE /v1/users/{userId}/roles/{roleId}       Remove role from user (alt path)
 */
@Slf4j
@RestController
@Tag(name = "Role Management (RBAC)", description = "Role creation, permission management, and user role assignment")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;
    private final PermissionRepository permissionRepository;

    // ── CREATE ROLE (legacy — tenantId from JWT) ──────────────────
    @PostMapping("/v1/roles")
    @Operation(summary = "Create custom role for tenant (tenantId from JWT)")
    public ResponseEntity<ApiResponse<RoleInfoResponse>> createRole(
            @Valid @RequestBody RoleCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(roleService.createRole(request)));
    }

    // ── CREATE ROLE (tenant-scoped path) ──────────────────────────
    /**
     * POST /v1/tenants/{tenantId}/roles
     *
     * Used by UserManagementPage.RoleAssignPanel when org/vendor admins
     * create a new role for their side.
     * tenantId in the path is for routing clarity — actual tenant enforcement
     * is done in RoleServiceImpl via JWT (consistent with rest of the platform).
     */
    @PostMapping("/v1/tenants/{tenantId}/roles")
    @Operation(summary = "Create role scoped to a specific tenant")
    public ResponseEntity<ApiResponse<RoleInfoResponse>> createTenantRole(
            @PathVariable Long tenantId,
            @Valid @RequestBody RoleCreateRequest request) {
        // tenantId in path is for documentation; service reads tenant from JWT.
        // This endpoint exists as a REST-friendly alias.
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(roleService.createRole(request)));
    }

    // ── DELETE ROLE ───────────────────────────────────────────────
    /**
     * DELETE /v1/tenants/{tenantId}/roles/{roleId}
     *
     * Deletes a tenant-specific role. System roles (isSystem=true) cannot be deleted.
     */
    @DeleteMapping("/v1/tenants/{tenantId}/roles/{roleId}")
    @Operation(summary = "Delete a tenant role (non-system roles only)")
    public ResponseEntity<ApiResponse<Void>> deleteTenantRole(
            @PathVariable Long tenantId,
            @PathVariable Long roleId) {
        roleService.deleteRole(tenantId, roleId);
        return ResponseEntity.ok(ApiResponse.success());
    }

    // ── UPDATE ROLE PERMISSIONS ───────────────────────────────────
    @PutMapping("/v1/roles/{roleId}/permissions")
    @Operation(summary = "Add or remove permissions from role")
    public ResponseEntity<ApiResponse<RoleInfoResponse>> updateRolePermissions(
            @PathVariable Long roleId,
            @Valid @RequestBody RolePermissionUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.success(roleService.updateRolePermissions(roleId, request)));
    }

    // ── GET ROLE HIERARCHY ────────────────────────────────────────
    @GetMapping("/v1/tenants/{tenantId}/roles/hierarchy")
    @Operation(summary = "Retrieve complete role structure with levels")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getRoleHierarchy(
            @PathVariable Long tenantId,
            @RequestParam(required = false) String side) {
        return ResponseEntity.ok(ApiResponse.success(roleService.getRoleHierarchy(tenantId, side)));
    }

    // ── ASSIGN ROLE TO USER (alt path used by rolesApi.assignToUser) ─
    @PostMapping("/v1/roles/users/{tenantId}/{userId}/assign")
    @Operation(summary = "Assign role(s) to user")
    public ResponseEntity<ApiResponse<UserResponse>> assignRoleAlt(
            @PathVariable Long tenantId,
            @PathVariable Long userId,
            @Valid @RequestBody RoleAssignmentRequest request) {
        return ResponseEntity.ok(ApiResponse.success(roleService.assignRoleToUser(tenantId, userId, request)));
    }

    // ── REMOVE ROLE FROM USER (alt path used by rolesApi.removeFromUser) ─
    @DeleteMapping("/v1/roles/users/{tenantId}/{userId}/remove/{roleId}")
    @Operation(summary = "Remove role from user")
    public ResponseEntity<ApiResponse<UserResponse>> removeRoleAlt(
            @PathVariable Long tenantId,
            @PathVariable Long userId,
            @PathVariable Long roleId) {
        return ResponseEntity.ok(ApiResponse.success(
                roleService.removeRoleFromUser(tenantId, userId, roleId, null)));
    }

    // ── ASSIGN ROLE TO USER (original path) ──────────────────────
    @PostMapping("/v1/users/{userId}/roles")
    @Operation(summary = "Add role(s) to user (with SoD check)")
    public ResponseEntity<ApiResponse<UserResponse>> assignRole(
            @RequestHeader(Constants.TENANT_HEADER) Long tenantId,
            @PathVariable Long userId,
            @Valid @RequestBody RoleAssignmentRequest request) {
        return ResponseEntity.ok(ApiResponse.success(roleService.assignRoleToUser(tenantId, userId, request)));
    }

    // ── REMOVE ROLE FROM USER (original path) ─────────────────────
    @DeleteMapping("/v1/users/{userId}/roles/{roleId}")
    @Operation(summary = "Revoke role from user")
    public ResponseEntity<ApiResponse<UserResponse>> removeRole(
            @RequestHeader(Constants.TENANT_HEADER) Long tenantId,
            @PathVariable Long userId,
            @PathVariable Long roleId,
            @RequestBody(required = false) RoleRemoveRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                roleService.removeRoleFromUser(tenantId, userId, roleId, request)));
    }

    @GetMapping("/v1/permissions")
    @Operation(summary = "List all permissions")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getAllPermissions() {
        return ResponseEntity.ok(ApiResponse.success(
                permissionRepository.findAll().stream()
                        .map(p -> {
                            Map<String, Object> m = new java.util.LinkedHashMap<>();
                            m.put("id",           p.getId());
                            m.put("code",         p.getCode());
                            m.put("name",         p.getName());
                            m.put("resourceType", p.getResourceType());
                            m.put("moduleId",     p.getModuleId());
                            return m;
                        })
                        .toList()
        ));
    }

    @PostMapping("/v1/permissions")
    @Operation(summary = "Create a new permission")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createPermission(
            @RequestBody Map<String, String> req) {
        Permission p = new Permission();
        p.setCode(req.get("code"));
        p.setName(req.get("name"));
        p.setResourceType(req.get("resourceType"));
        p.setModuleId(1L); // default system module
        p = permissionRepository.save(p);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(Map.of("id", p.getId(), "code", p.getCode())));
    }

    @DeleteMapping("/v1/permissions/{id}")
    @Operation(summary = "Delete a permission")
    public ResponseEntity<ApiResponse<Void>> deletePermission(@PathVariable Long id) {
        permissionRepository.deleteById(id);
        return ResponseEntity.ok(ApiResponse.success());
    }
}
