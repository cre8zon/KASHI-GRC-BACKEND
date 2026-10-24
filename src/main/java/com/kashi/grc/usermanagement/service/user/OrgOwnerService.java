package com.kashi.grc.usermanagement.service.user;

import com.kashi.grc.common.config.security.CustomUserDetailsService;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.security.PermissionGate;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.tenant.domain.Tenant;
import com.kashi.grc.tenant.repository.TenantRepository;
import com.kashi.grc.usermanagement.domain.AccountStatusChange;
import com.kashi.grc.usermanagement.domain.Role;
import com.kashi.grc.usermanagement.domain.RoleSide;
import com.kashi.grc.usermanagement.domain.User;
import com.kashi.grc.usermanagement.domain.UserStatus;
import com.kashi.grc.usermanagement.repository.AccountStatusChangeRepository;
import com.kashi.grc.usermanagement.repository.RoleRepository;
import com.kashi.grc.usermanagement.repository.UserRepository;
import com.kashi.grc.usermanagement.service.role.MembershipRoleSync;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Each organisation has exactly one Org Owner: the buyer / head of the
 * organisation, the first person created when the tenant is provisioned.
 *
 * Stored as tenants.owner_user_id. The ORG_OWNER role follows that column and
 * moves ONLY through transfer() — RoleServiceImpl refuses to grant or remove
 * it, UserServiceImpl.createUser gives it only to a tenant's first owner, and
 * UserLifecycleService refuses to suspend, deactivate or delete the owner.
 * Before this, the owner was just whoever held the role: it could be given to
 * several people, removed, or left on a suspended account.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrgOwnerService {

    public static final String OWNER_ROLE = "ORG_OWNER";
    /** What the previous owner keeps, so they do not lose all administration. */
    public static final String ADMIN_ROLE = "ORG_ADMIN";

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final MembershipRoleSync membershipRoleSync;
    private final AccountStatusChangeRepository statusChangeRepository;
    private final UtilityService utilityService;
    private final PermissionGate permissionGate;

    /** True when this role is the Org Owner role (by name — it is a global role). */
    public static boolean isOwnerRole(Role r) {
        return r != null && OWNER_ROLE.equals(r.getName());
    }

    /** The owner's user id for a tenant, or null. */
    @Transactional(readOnly = true)
    public Long ownerOf(Long tenantId) {
        return tenantId == null ? null : tenantRepository.findById(tenantId).map(Tenant::getOwnerUserId).orElse(null);
    }

    /**
     * Hand the organisation to another of its people.
     *
     * Allowed for the current owner and for platform administrators. The new
     * owner must be an active, organisation-side person of the same tenant (not
     * a vendor user). They receive ORG_OWNER; the previous owner loses it and
     * keeps ORG_ADMIN so they can still administer.
     */
    @Transactional
    public Map<String, Object> transfer(Long tenantId, Long newOwnerId, String reason) {
        User caller = utilityService.getLoggedInDataContext();
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant", tenantId));
        permissionGate.requireSameTenant(tenantId, caller.getTenantId());

        Long currentOwnerId = tenant.getOwnerUserId();
        boolean platform = permissionGate.isPlatform();
        if (!platform && !caller.getId().equals(currentOwnerId)) {
            throw new BusinessException("OWNER_ONLY",
                    "Only the organisation's owner can transfer ownership.", HttpStatus.FORBIDDEN);
        }

        User next = userRepository.findById(newOwnerId)
                .filter(u -> !u.isDeleted() && tenantId.equals(u.getTenantId()))
                .orElseThrow(() -> new ResourceNotFoundException("User", newOwnerId));
        if (next.getId().equals(currentOwnerId)) {
            throw new BusinessException("ALREADY_OWNER", "That person is already the owner.", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (next.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException("OWNER_NOT_ACTIVE",
                    "Ownership can only go to an active account.", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (next.getVendorId() != null || next.getRoles().stream()
                .anyMatch(r -> r.getSide() != null && r.getSide() != RoleSide.ORGANIZATION)) {
            throw new BusinessException("OWNER_WRONG_SIDE",
                    "The owner must be one of the organisation's own people, not a vendor or auditor account.",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }

        Role ownerRole = roleRepository.findByNameAndSide(OWNER_ROLE, RoleSide.ORGANIZATION)
                .orElseThrow(() -> new BusinessException("OWNER_ROLE_MISSING",
                        "The ORG_OWNER role is not configured.", HttpStatus.INTERNAL_SERVER_ERROR));

        // The previous owner gives up ORG_OWNER and keeps (or gains) ORG_ADMIN.
        if (currentOwnerId != null) {
            userRepository.findById(currentOwnerId).ifPresent(prev -> {
                prev.getRoles().removeIf(OrgOwnerService::isOwnerRole);
                roleRepository.findByNameAndSide(ADMIN_ROLE, RoleSide.ORGANIZATION).ifPresent(admin -> {
                    if (prev.getRoles().stream().noneMatch(r -> r.getId().equals(admin.getId()))) prev.getRoles().add(admin);
                });
                userRepository.save(prev);
            });
        }
        next.getRoles().add(ownerRole);
        userRepository.save(next);
        userRepository.flush();
        // The @ManyToMany cannot write user_roles.membership_id — stamp both, as
        // RoleServiceImpl does after every role change.
        if (currentOwnerId != null) membershipRoleSync.ensureHomeMembership(currentOwnerId, tenantId);
        membershipRoleSync.ensureHomeMembership(next.getId(), tenantId);

        tenant.setOwnerUserId(next.getId());
        tenantRepository.save(tenant);

        statusChangeRepository.save(AccountStatusChange.builder()
                .subjectType(AccountStatusChange.TENANT)
                .subjectId(tenantId)
                .tenantId(tenantId)
                .fromStatus(currentOwnerId == null ? null : "OWNER " + currentOwnerId)
                .toStatus("OWNER_TRANSFERRED")
                .reason(reason == null || reason.isBlank() ? null : reason.trim())
                .actorUserId(caller.getId())
                .detail("owner: " + currentOwnerId + " → " + next.getId())
                .build());

        CustomUserDetailsService.invalidate(next.getId());
        if (currentOwnerId != null) CustomUserDetailsService.invalidate(currentOwnerId);
        log.info("[ORG-OWNER] Transferred | tenantId={} | from={} | to={} | by={}",
                tenantId, currentOwnerId, next.getId(), caller.getId());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenantId", tenantId);
        out.put("previousOwnerId", currentOwnerId);
        out.put("ownerUserId", next.getId());
        out.put("ownerName", next.getFullName().trim());
        return out;
    }
}
