package com.kashi.grc.usermanagement.service.role;

import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.exception.ValidationException;
import com.kashi.grc.guard.domain.SodRule;
import com.kashi.grc.guard.repository.SodRuleRepository;
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
    // SodRuleRepository is now guard.SodRuleRepository — unified entity in guard module.
    // ROLE_PAIR rules (role conflict at assignment time) and PERMISSION_PAIR rules
    // (permission conflict at access resolution time) both live in guard.SodRule / sod_rules table.
    private final SodRuleRepository    sodRuleRepository;
    private final com.kashi.grc.common.util.UtilityService utilityService;

    @Override
    @Transactional
    public RoleInfoResponse createRole(RoleCreateRequest req) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return buildAndSaveRole(tenantId, req);
    }

    @Override
    @Transactional
    public RoleInfoResponse createRoleForTenant(Long tenantId, RoleCreateRequest req) {
        // The path tenantId was previously ignored entirely — this endpoint
        // silently stamped every role with the CALLER's own JWT tenantId
        // instead, regardless of which tenant's roles URL was actually hit.
        // For a same-tenant org admin the two values happen to match, which
        // is why this went unnoticed; the bug shows up for anyone whose own
        // tenant differs from the tenant they're managing (a platform/system
        // admin acting on a specific org's roles) — the role gets created
        // under the wrong tenant_id and silently never appears in that org's
        // own role hierarchy again, even though it can still be assigned to
        // that org's users via the roles list (which enforces user-tenant,
        // not role-tenant). Enforce instead of silently substituting:
        // same-tenant callers proceed as before; only SYSTEM-side callers
        // may act across tenants, and everyone else is rejected outright
        // rather than quietly redirected to a different tenant's data.
        User loggedInUser = utilityService.getLoggedInDataContext();
        boolean isSystemUser = loggedInUser.getRoles().stream()
                .anyMatch(r -> r.getSide() == RoleSide.SYSTEM);
        if (!isSystemUser && !tenantId.equals(loggedInUser.getTenantId())) {
            throw new com.kashi.grc.common.exception.BusinessException(
                    "FORBIDDEN_TENANT",
                    "Cannot create a role for a different tenant",
                    org.springframework.http.HttpStatus.FORBIDDEN);
        }
        return buildAndSaveRole(tenantId, req);
    }

    private RoleInfoResponse buildAndSaveRole(Long tenantId, RoleCreateRequest req) {
        // Only a SYSTEM-side caller may create a genuinely global (tenant_id
        // = NULL) role, and only when they EXPLICITLY ask for it via
        // req.global — not simply because they happen to be SYSTEM-side.
        // A system admin can also target one specific tenant on purpose:
        // createRoleForTenant already permits a SYSTEM caller to act on a
        // tenantId other than their own (see its FORBIDDEN_TENANT check).
        // Inferring "global" purely from the creator's own side, as this
        // used to do, silently discarded that explicit targeting and made
        // every system-created role global regardless of actual intent —
        // there was no way for a system admin to create a role for just one
        // tenant. Non-system callers can never set global=true no matter
        // what they send; effectiveTenantId always falls back to the
        // tenantId the request actually resolved to.
        User loggedInUser = utilityService.getLoggedInDataContext();
        boolean isSystemCreator = loggedInUser.getRoles().stream()
                .anyMatch(r -> r.getSide() == RoleSide.SYSTEM);
        Long effectiveTenantId = (isSystemCreator && req.isGlobal()) ? null : tenantId;

        RoleSide side = RoleSide.valueOf(req.getSide());
        if (roleRepository.existsByNameAndSideAndTenantId(req.getName(), side, effectiveTenantId)) {
            throw new ValidationException("Role with this name already exists");
        }
        Set<Permission> permissions = new HashSet<>();
        if (req.getPermissionIds() != null) {
            req.getPermissionIds().forEach(id -> permissions.add(
                    permissionRepository.findById(id).orElseThrow(
                            () -> new ResourceNotFoundException("Permission", id))));
        }
        Role role = Role.builder()
                .tenantId(effectiveTenantId).name(req.getName()).side(side)
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

        // A user's roles must all come from exactly one side — an auditor can
        // hold several AUDITOR-side roles at once, but never an AUDITOR role
        // alongside an ORGANIZATION role. A user does not belong to multiple
        // sides. Determine the side they're already committed to (if any);
        // if they hold no roles yet, whichever role is assigned first in
        // this call establishes it, and every other role in the same call
        // must match.
        RoleSide committedSide = user.getRoles().stream()
                .map(Role::getSide)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);

        for (Long roleId : req.getRoleIds()) {
            final Long finalRoleId = roleId;
            var role = roleRepository.findById(finalRoleId)
                    .orElseThrow(() -> new ResourceNotFoundException("Role", finalRoleId));

            if (committedSide == null) {
                committedSide = role.getSide();
            } else if (role.getSide() != committedSide) {
                throw new ValidationException(
                        "Cannot assign a " + role.getSide() + "-side role — this user already holds "
                                + committedSide + "-side role(s). A user's roles must all come from "
                                + "the same side; a user cannot belong to more than one side.");
            }

            if (!req.isSkipSodCheck()) {
                List<Long> existingRoleIds = user.getRoles().stream()
                        .map(Role::getId).collect(Collectors.toList());
                for (Long existingId : existingRoleIds) {
                    // findConflictBetween returns only ROLE_PAIR rules (see SodRuleRepository query).
                    List<SodRule> conflicts = sodRuleRepository
                            .findConflictBetween(tenantId, existingId, finalRoleId);
                    if (!conflicts.isEmpty()) {
                        SodRule rule = conflicts.get(0);
                        // Unified check: ConflictType.HARD blocks the assignment.
                        // Replaces the old "HARD_BLOCK".equals(rule.getEnforcementMode()) string check.
                        if (rule.getConflictType() == SodRule.ConflictType.HARD) {
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