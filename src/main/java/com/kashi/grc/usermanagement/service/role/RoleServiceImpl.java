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
        User loggedInUser = utilityService.getLoggedInDataContext();
        boolean isSystemCreator = loggedInUser.getRoles().stream()
                .anyMatch(r -> r.getSide() == RoleSide.SYSTEM);

        RoleSide side = RoleSide.valueOf(req.getSide());

        // HARD RULE: side=SYSTEM belongs to the one Kashi System Tenant and
        // nothing else. A SYSTEM role is stamped with SYSTEM_TENANT_ID — NOT
        // null. Null would mean "global", and findAllForTenantBySide matches
        // `tenantId = X OR tenantId IS NULL`, so a null-tenant SYSTEM role
        // would be handed to every tenant's role list — the exact opposite
        // of restricting it. Pinning it to tenant 1 means only tenant 1's
        // queries can ever return it.
        if (side == RoleSide.SYSTEM && !isSystemCreator) {
            throw new ValidationException(
                    "Only a SYSTEM-side user can create a SYSTEM-side role. "
                            + "SYSTEM is reserved for the one Kashi System Tenant.");
        }

        // Only a SYSTEM-side caller may create a genuinely global (tenant_id
        // = NULL) role, and only when they EXPLICITLY ask for it via
        // req.global. Global is for the shared cross-tenant catalog of
        // ORGANIZATION/VENDOR/AUDITEE/AUDITOR roles — never for SYSTEM,
        // which is pinned to tenant 1 above.
        Long effectiveTenantId = side == RoleSide.SYSTEM
                ? com.kashi.grc.common.util.Constants.SYSTEM_TENANT_ID
                : (isSystemCreator && req.isGlobal()) ? null : tenantId;

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
        return getRoleHierarchy(tenantId, side, false);
    }

    @Override
    @Transactional
    public Map<String, Object> getRoleHierarchy(Long tenantId, String side, boolean includeSuspended) {
        RoleSide sideFilter = side != null ? RoleSide.valueOf(side) : null;
        List<Role> roles = roleRepository.findAllForTenantBySide(tenantId, sideFilter, includeSuspended);
        Map<String, List<Map<String, Object>>> hierarchy = new LinkedHashMap<>();
        roles.forEach(r -> {
            String key = r.getSide().name();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("role_id", r.getId());
            entry.put("name", r.getName());
            entry.put("level", r.getLevel() != null ? r.getLevel().name() : "null");
            entry.put("user_count", roleRepository.countUsersWithRole(r.getId()));
            entry.put("permissions_count", r.getPermissions().size());
            entry.put("status", r.getStatus() != null ? r.getStatus() : "ACTIVE");
            entry.put("tenant_id", r.getTenantId());          // null = global
            entry.put("is_global", r.getTenantId() == null);
            entry.put("description", r.getDescription());
            entry.put("is_system", Boolean.TRUE.equals(r.getIsSystem()));
            hierarchy.computeIfAbsent(key, k -> new ArrayList<>()).add(entry);
        });
        return Map.of("tenant_id", tenantId, "hierarchy", hierarchy);
    }

    @Override
    @Transactional
    public RoleInfoResponse setRoleStatus(Long roleId, String status) {
        if (!"ACTIVE".equals(status) && !"SUSPENDED".equals(status)) {
            throw new ValidationException("status must be ACTIVE or SUSPENDED");
        }
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new ResourceNotFoundException("Role", roleId));

        // Only a SYSTEM-side user may suspend a global role — it affects
        // every tenant, not just the caller's own.
        User loggedInUser = utilityService.getLoggedInDataContext();
        boolean isSystemUser = loggedInUser.getRoles().stream()
                .anyMatch(r -> r.getSide() == RoleSide.SYSTEM);
        if (role.getTenantId() == null && !isSystemUser) {
            throw new ValidationException(
                    "Only a platform administrator can suspend or reactivate a global role.");
        }
        if (role.getTenantId() != null && !isSystemUser
                && !role.getTenantId().equals(loggedInUser.getTenantId())) {
            throw new ValidationException("Cannot change a role belonging to a different tenant.");
        }

        // Deliberately does NOT strip the role from users who already hold
        // it. Suspending controls the assignable CATALOGUE — "don't offer
        // this role while it's half-built" — not entitlement. Silently
        // revoking live permissions from a bulk action would be the kind of
        // invisible access change that's hard to audit after the fact, and
        // could lock out an ORG_OWNER in one click. Existing holders stay
        // visible on the role and in each user's role list, so nothing is
        // hidden; to actually remove access, unassign the role from those
        // users explicitly. The caller sees userCount below so the blast
        // radius is obvious before they act.
        role.setStatus(status);
        role = roleRepository.save(role);
        return RoleInfoResponse.builder()
                .roleId(role.getId()).roleName(role.getName())
                .side(role.getSide().name())
                .level(role.getLevel() != null ? role.getLevel().name() : null)
                .permissionsCount(role.getPermissions().size())
                .userCount(roleRepository.countUsersWithRole(role.getId()))
                .build();
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

            // A SYSTEM-side role means "this person administers the whole
            // platform" — that only makes sense for a user who actually
            // belongs to the one reserved Kashi System Tenant. Nothing in
            // the schema enforced this before; a normal tenant's user could
            // silently end up holding a SYSTEM role and get treated as a
            // platform admin (wrong dashboard, wrong nav, cross-tenant data
            // visibility) despite their own tenantId pointing at their real
            // organisation the whole time.
            if (role.getSide() == RoleSide.SYSTEM
                    && !com.kashi.grc.common.util.Constants.SYSTEM_TENANT_ID.equals(tenantId)) {
                throw new ValidationException(
                        "Cannot assign a SYSTEM-side role to a user outside the Kashi System Tenant. "
                                + "SYSTEM roles are reserved for platform administration and can only "
                                + "be held by users of that one tenant.");
            }

            // A suspended role is parked — it must not be newly assigned to
            // anyone, even though users who already hold it keep it.
            if ("SUSPENDED".equals(role.getStatus())) {
                throw new ValidationException(
                        "Role '" + role.getName() + "' is suspended and cannot be assigned. "
                                + "Reactivate it from RBAC & Permissions first.");
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