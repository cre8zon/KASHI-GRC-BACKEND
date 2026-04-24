package com.kashi.grc.usermanagement.service.role;

import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.exception.ValidationException;
import com.kashi.grc.usermanagement.domain.*;
import com.kashi.grc.usermanagement.dto.request.*;
import com.kashi.grc.usermanagement.dto.response.*;
import com.kashi.grc.usermanagement.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RoleServiceImpl implements RoleService {

    private final RoleRepository       roleRepository;
    private final PermissionRepository permissionRepository;
    private final UserRepository       userRepository;
    private final SodRuleRepository    sodRuleRepository;
    private final com.kashi.grc.common.util.UtilityService utilityService;

    @Override
    @Transactional
    public RoleInfoResponse createRole(RoleCreateRequest req) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        RoleSide side = RoleSide.valueOf(req.getSide());
        if (roleRepository.existsByNameAndSideAndTenantId(req.getName(), side, tenantId)) {
            throw new ValidationException("Role with this name already exists");
        }
        Set<Permission> permissions = new HashSet<>();
        if (req.getPermissionIds() != null) {
            req.getPermissionIds().forEach(id -> permissions.add(
                    permissionRepository.findById(id).orElseThrow(
                            () -> new ResourceNotFoundException("Permission", id))));
        }
        Role role = Role.builder()
                .tenantId(tenantId).name(req.getName()).side(side)
                .level(req.getLevel() != null ? RoleLevel.valueOf(req.getLevel()) : null)
                .description(req.getDescription()).isSystem(req.isSystem()).permissions(permissions)
                .build();
        role = roleRepository.save(role);
        return RoleInfoResponse.builder()
                .roleId(role.getId()).roleName(role.getName())
                .side(role.getSide().name())
                .level(role.getLevel() != null ? role.getLevel().name() : null)
                .permissionsCount(role.getPermissions().size()).userCount(0L)
                .build();
    }

    @Override
    @Transactional
    public RoleInfoResponse updateRolePermissions(Long roleId, RolePermissionUpdateRequest req) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        Role role = roleRepository.findByIdAndTenantId(roleId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Role", roleId));

        // Capture permissions set before lambdas — role is reassigned later so not effectively final
        Set<Permission> perms = role.getPermissions();
        if (req.getAddPermissionIds() != null) {
            req.getAddPermissionIds().forEach(id ->
                    permissionRepository.findById(id).ifPresent(perms::add));
        }
        if (req.getRemovePermissionIds() != null) {
            perms.removeIf(p -> req.getRemovePermissionIds().contains(p.getId()));
        }
        role = roleRepository.save(role);
        long affectedUsers = roleRepository.countUsersWithRole(role.getId());
        return RoleInfoResponse.builder()
                .roleId(role.getId()).roleName(role.getName())
                .side(role.getSide().name())
                .level(role.getLevel() != null ? role.getLevel().name() : null)
                .permissionsCount(role.getPermissions().size()).userCount(affectedUsers)
                .build();
    }

    @Override
    @Transactional
    public Map<String, Object> getRoleHierarchy(Long tenantId, String side) {
        RoleSide sideFilter = side != null ? RoleSide.valueOf(side) : null;
        List<Role> roles = roleRepository.findAllForTenantBySide(tenantId, sideFilter);
        Map<String, List<Map<String, Object>>> hierarchy = new LinkedHashMap<>();
        roles.forEach(r -> {
            String key = r.getSide().name();
            hierarchy.computeIfAbsent(key, k -> new ArrayList<>()).add(Map.of(
                    "role_id", r.getId(), "name", r.getName(),
                    "level", r.getLevel() != null ? r.getLevel().name() : "null",
                    "user_count", roleRepository.countUsersWithRole(r.getId()),
                    "permissions_count", r.getPermissions().size()));
        });
        return Map.of("tenant_id", tenantId, "hierarchy", hierarchy);
    }

    @Override
    @Transactional
    public UserResponse assignRoleToUser(Long tenantId, Long userId, RoleAssignmentRequest req) {
        var user = userRepository.findByIdAndTenantIdAndIsDeletedFalse(userId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        for (Long roleId : req.getRoleIds()) {
            final Long finalRoleId = roleId;
            var role = roleRepository.findById(finalRoleId)
                    .orElseThrow(() -> new ResourceNotFoundException("Role", finalRoleId));

            if (!req.isSkipSodCheck()) {
                List<Long> existingRoleIds = user.getRoles().stream()
                        .map(Role::getId).collect(Collectors.toList());
                for (Long existingId : existingRoleIds) {
                    List<SodRule> conflicts = sodRuleRepository
                            .findConflictBetween(tenantId, existingId, finalRoleId);
                    if (!conflicts.isEmpty()) {
                        SodRule rule = conflicts.get(0);
                        if ("HARD_BLOCK".equals(rule.getEnforcementMode())) {
                            throw new ValidationException("SOD_VIOLATION: " + rule.getDescription());
                        }
                    }
                }
            }
            user.getRoles().add(role);
        }
        return buildUserResponse(userRepository.save(user));
    }

    @Override
    @Transactional
    public UserResponse removeRoleFromUser(Long tenantId, Long userId, Long roleId, RoleRemoveRequest req) {
        var user = userRepository.findByIdAndTenantIdAndIsDeletedFalse(userId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        user.getRoles().removeIf(r -> r.getId().equals(roleId));
        return buildUserResponse(userRepository.save(user));
    }

    @Override
    @Transactional
    public void deleteRole(Long tenantId, Long roleId) {
        Role role = roleRepository.findByIdAndTenantId(roleId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Role", roleId));

        if (Boolean.TRUE.equals(role.getIsSystem())) {
            throw new ValidationException("System roles cannot be deleted");
        }

        // Remove role from all users first to avoid FK violation
        long usersWithRole = roleRepository.countUsersWithRole(roleId);
        if (usersWithRole > 0) {
            throw new ValidationException(
                    "Cannot delete role — " + usersWithRole + " user(s) still have this role assigned. " +
                            "Remove the role from all users first.");
        }

        roleRepository.delete(role);
    }

    private UserResponse buildUserResponse(User user) {
        List<AuthResponse.RoleInfo> roles = user.getRoles().stream()
                .map(r -> AuthResponse.RoleInfo.builder()
                        .roleId(r.getId()).roleName(r.getName())
                        .side(r.getSide() != null ? r.getSide().name() : null)
                        .level(r.getLevel() != null ? r.getLevel().name() : null)
                        .build())
                .collect(Collectors.toList());
        return UserResponse.builder()
                .userId(user.getId()).email(user.getEmail()).fullName(user.getFullName())
                .tenantId(user.getTenantId()).status(user.getStatus().name())
                .roles(roles).build();
    }
}