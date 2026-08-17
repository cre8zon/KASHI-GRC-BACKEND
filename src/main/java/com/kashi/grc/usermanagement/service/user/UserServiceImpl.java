package com.kashi.grc.usermanagement.service.user;

import com.kashi.grc.common.dto.PageDetails;
import com.kashi.grc.common.dto.PaginatedResponse;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.exception.ValidationException;
import com.kashi.grc.common.repository.EmailTemplateRepository;
import com.kashi.grc.common.service.EmailSenderService;
import com.kashi.grc.common.service.MailService;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.common.repository.DbRepository;
import com.kashi.grc.usermanagement.domain.Role;
import com.kashi.grc.usermanagement.domain.RoleSide;
import com.kashi.grc.usermanagement.domain.User;
import com.kashi.grc.usermanagement.domain.UserAttribute;
import com.kashi.grc.usermanagement.domain.UserStatus;
import com.kashi.grc.usermanagement.dto.request.*;
import com.kashi.grc.usermanagement.dto.response.*;
import com.kashi.grc.usermanagement.repository.DelegationRepository;
import com.kashi.grc.usermanagement.repository.RoleRepository;
import com.kashi.grc.usermanagement.repository.UserAttributeRepository;
import com.kashi.grc.usermanagement.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository  userRepository;
    private final RoleRepository  roleRepository;
    private final com.kashi.grc.usermanagement.repository.UserTenantMembershipRepository membershipRepository;
    private final com.kashi.grc.usermanagement.service.role.MembershipRoleSync membershipRoleSync;
    private final UserAttributeRepository attributeRepository;
    private final DelegationRepository delegationRepository;

    @org.springframework.beans.factory.annotation.Value("${kashi.app.base-url:https://app.kashigrc.com}")
    private String appBaseUrl;
    private final PasswordEncoder passwordEncoder;
    private final MailService mailService;
    private final EmailTemplateRepository emailTemplateRepository;
    private final EmailSenderService emailSenderService;
    private final DbRepository dbRepository;
    private final UtilityService utilityService;

    // ─────────────────────────────────────────────────────────────
    // CREATE
    // ─────────────────────────────────────────────────────────────
    @Override
    @Transactional
    public UserResponse createUser(UserCreateRequest request) {
        Long loggedInTenantId = utilityService.getLoggedInDataContext().getTenantId();
        // If request explicitly provides a tenantId (e.g. Platform Admin creating org user),
        // use it — otherwise fall back to the logged-in user's tenant
        Long tenantId = (request.getTenantId() != null && !request.getTenantId().equals(loggedInTenantId))
                ? request.getTenantId()
                : loggedInTenantId;
        // Email is now a globally unique identity — was scoped per-tenant here,
        // which let the same email exist as separate accounts in different
        // tenants. login() has always assumed the opposite (findByEmail
        // returning exactly one row), so that mismatch is what caused
        // NonUniqueResultException on login for any email invited into more
        // than one tenant. See UserRepository.existsByEmailAndIsDeletedFalse.
        if (userRepository.existsByEmailAndIsDeletedFalse(request.getEmail())) {
            throw new ValidationException("A user with email " + request.getEmail() + " already exists");
        }

        // Generate temporary password; user must reset on first login
        String tempPassword = UUID.randomUUID().toString();

        // A vendor-side creator can only ever create users for their OWN
        // vendor — their own vendorId is authoritative and overrides
        // anything sent in the request (same rule listUsers already applies
        // when scoping reads). Org/System creators supply it explicitly via
        // the vendor picker.
        User creator = utilityService.getLoggedInDataContext();
        Long effectiveVendorId = creator.getVendorId() != null
                ? creator.getVendorId()
                : request.getVendorId();

        User user = User.builder()
                .tenantId(tenantId)
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(tempPassword))
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .department(request.getDepartment())
                .jobTitle(request.getJobTitle())
                .phone(request.getPhone())
                .managerId(request.getManagerId())
                .vendorId(effectiveVendorId)
                .status(UserStatus.ACTIVE)
                .passwordResetRequired(true)
                .build();

        // Assign roles
        if (request.getRoleIds() != null && !request.getRoleIds().isEmpty()) {
            Set<Role> roles = new HashSet<>(roleRepository.findAllById(request.getRoleIds()));
            // Same rule as RoleServiceImpl.assignRoleToUser: a user's roles
            // must all come from exactly one side. Checked here too since
            // createUser assigns roles directly rather than going through
            // that method — a brand-new user could otherwise be created
            // already in a state (multi-side) that's supposed to be
            // unreachable.
            long distinctSides = roles.stream()
                    .map(Role::getSide).filter(java.util.Objects::nonNull).distinct().count();
            if (distinctSides > 1) {
                throw new ValidationException(
                        "Cannot assign roles from more than one side to a single user. "
                                + "A user's roles must all come from exactly one side.");
            }
            // Same rule as RoleServiceImpl.assignRoleToUser: SYSTEM-side
            // roles are reserved for the one Kashi System Tenant — creating
            // a user with a SYSTEM role under any other tenant would give
            // them platform-admin treatment despite belonging to a normal
            // organisation.
            boolean hasSystemRole = roles.stream().anyMatch(r -> r.getSide() == RoleSide.SYSTEM);
            if (hasSystemRole && !com.kashi.grc.common.util.Constants.SYSTEM_TENANT_ID.equals(tenantId)) {
                throw new ValidationException(
                        "Cannot assign a SYSTEM-side role to a user outside the Kashi System Tenant. "
                                + "SYSTEM roles are reserved for platform administration and can only "
                                + "be held by users of that one tenant.");
            }
            // A VENDOR-side user must be tied to an actual vendor. When a
            // vendor-side admin onboards a colleague this was implicit —
            // the frontend passed their own vendorId — but an ORGANIZATION
            // admin onboarding a vendor contact has no such context, so
            // vendorId arrived null and the user was created orphaned:
            // invisible to every vendor-scoped query (listUsers filters on
            // vendorId) and unattached to any assessment. Require it
            // explicitly rather than silently creating a broken row.
            boolean hasVendorRole = roles.stream().anyMatch(r -> r.getSide() == RoleSide.VENDOR);
            if (hasVendorRole && user.getVendorId() == null) {
                throw new ValidationException(
                        "A vendor must be selected when creating a VENDOR-side user — "
                                + "vendor users have to belong to a specific vendor.");
            }
            if (!hasVendorRole && user.getVendorId() != null) {
                throw new ValidationException(
                        "vendorId can only be set on a user holding VENDOR-side role(s).");
            }
            user.setRoles(roles);
        }

        // 3. Ensure data is written to DB now
        user = userRepository.save(user);
        userRepository.flush();

        final User savedUser = user;
        if (request.getDefaultRoleName() != null) {
            roleRepository.findAll().stream()
                    .filter(r -> r.getName().equals(request.getDefaultRoleName()) && r.getTenantId() == null)
                    .findFirst()
                    .ifPresent(role -> {
                        // This path bypassed both guards above — it resolves a
                        // role by NAME with no side or tenant validation, so
                        // passing defaultRoleName="PLATFORM_ADMIN" would attach
                        // a SYSTEM role to any user in any tenant. Same two
                        // rules apply here as everywhere else: SYSTEM roles are
                        // for the Kashi System Tenant only, and a user's roles
                        // must all come from a single side.
                        if (role.getSide() == RoleSide.SYSTEM
                                && !com.kashi.grc.common.util.Constants.SYSTEM_TENANT_ID.equals(tenantId)) {
                            throw new ValidationException(
                                    "Cannot assign a SYSTEM-side role to a user outside the Kashi "
                                            + "System Tenant. SYSTEM roles are reserved for platform "
                                            + "administration.");
                        }
                        boolean conflictsWithExistingSide = savedUser.getRoles().stream()
                                .map(Role::getSide)
                                .filter(java.util.Objects::nonNull)
                                .anyMatch(s -> s != role.getSide());
                        if (conflictsWithExistingSide) {
                            throw new ValidationException(
                                    "Cannot assign a " + role.getSide() + "-side default role — this "
                                            + "user already holds role(s) from a different side. A user "
                                            + "cannot belong to more than one side.");
                        }
                        savedUser.getRoles().add(role);
                        userRepository.save(savedUser);
                    });
        }

        // user_roles rows written through the @ManyToMany carry no membership_id,
        // which makes the user invisible to every membership-scoped picker.
        // Stamp them once, after the roles are flushed.
        userRepository.flush();
        membershipRoleSync.stamp(savedUser.getId(), savedUser.getTenantId());

        if (request.getAttributes() != null) {
            request.getAttributes().forEach((k, v) -> attributeRepository.save(
                    UserAttribute.builder().user(savedUser).attributeKey(k).attributeValue(v).build()));
        }

        // Send email ONLY after the transaction commits successfully
        if (request.isSendWelcomeEmail()) {
            var template = emailTemplateRepository.findByName("user-invitation").orElse(null);
            if (template != null) {
                // Generate a dedicated invite token
                // when passwordResetRequired=true (AUTH generates its own token on login)
                final String loginUrl   = appBaseUrl + "/auth/login";
                final String subject    = template.getSubject();
                final String content    = template.getContent()
                        .replace("{{firstName}}",         request.getFirstName())
                        .replace("{{email}}",             request.getEmail())
                        .replace("{{admin_email}}",       request.getEmail())
                        .replace("{{tempPassword}}",      tempPassword)
                        .replace("{{temp_password}}",     tempPassword)
                        .replace("{{loginUrl}}",          loginUrl)
                        .replace("{{login_url}}",         loginUrl)
                        .replace("{{resetUrl}}",          loginUrl)
                        .replace("{{organization_name}}", "")
                        .replace("{{supportUrl}}",        "https://support.kashigrc.com")
                        .replace("{{support_url}}",       "https://support.kashigrc.com")
                        .replace("{{admin_name}}",        request.getFirstName());
                final String mimeType   = template.getMimeType();
                final String toEmail    = user.getEmail();
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        mailService.sendRaw(subject, content, mimeType, toEmail);
                    }
                });
            }
        }

        UserResponse response = toResponse(user);
        response.setTemporaryPassword(tempPassword);
        return response;
    }

    // ─────────────────────────────────────────────────────────────
    // READ
    // ─────────────────────────────────────────────────────────────
    @Override
    @Transactional(readOnly = true)
    public UserResponse getUserById(Long userId) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();

        // findByIdAndTenantId reads users.tenant_id, which for an external
        // auditor is their FIRM — so every attempt to display a guest working in
        // this tenant 404'd. Anyone with a usable membership here is visible
        // here; that is what membership means.
        User user = userRepository.findByIdAndTenantIdAndIsDeletedFalse(userId, tenantId)
                .or(() -> membershipRepository.findByUserIdAndTenantId(userId, tenantId)
                        .filter(m -> m.isUsable())
                        .flatMap(m -> userRepository.findById(userId))
                        .filter(u -> !u.isDeleted()))
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        return toResponse(user);
    }

    // ── LIST (Criteria API via DbRepository) ──────────────────────

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<UserResponse> listUsers(PageDetails pageDetails, String side, boolean noRoles, Long vendorIdParam, Long roleIdParam, Long tenantIdParam) {
        return listUsers(pageDetails, side, noRoles, vendorIdParam, roleIdParam, tenantIdParam, null);
    }

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<UserResponse> listUsers(PageDetails pageDetails, String side, boolean noRoles, Long vendorIdParam, Long roleIdParam, Long tenantIdParam, String membershipType) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        User loggedInUser = utilityService.getLoggedInDataContext();

        // Check if user is SYSTEM side — Platform Admin sees all users
        boolean isSystemUser = loggedInUser.getRoles().stream()
                .anyMatch(r -> r.getSide() != null &&
                        r.getSide().name().equals("SYSTEM"));

        // SYSTEM callers may drill into one specific tenant's users (the
        // per-tenant, side-wise view); everyone else stays locked to their
        // own tenant regardless of what tenantIdParam they send.
        final Long effectiveTenantId = (isSystemUser && tenantIdParam != null) ? tenantIdParam : tenantId;

        // Determine the effective vendorId for scoping:
        //   1. Vendor-side users are ALWAYS scoped to their own vendor (security gate).
        //      Their loggedInUser.getVendorId() is the authoritative value — ignore any
        //      client-supplied param so a malicious actor can't request another vendor's users.
        //   2. Org/System callers may pass vendorId to drill into a specific vendor's team.
        boolean isVendorUser = loggedInUser.getVendorId() != null;
        final Long effectiveVendorId = isVendorUser
                ? loggedInUser.getVendorId()   // always enforce their own vendor
                : vendorIdParam;               // org/system: use supplied param (may be null)

        // Parse RoleSide enum safely
        com.kashi.grc.usermanagement.domain.RoleSide roleSide = null;
        if (side != null && !side.isBlank()) {
            try {
                roleSide = com.kashi.grc.usermanagement.domain.RoleSide.valueOf(side.toUpperCase());
            } catch (IllegalArgumentException ignored) {}
        }
        final com.kashi.grc.usermanagement.domain.RoleSide finalRoleSide = roleSide;
        final Long finalRoleId = roleIdParam;
        final String finalMembershipType =
                (membershipType != null && !membershipType.isBlank()) ? membershipType.toUpperCase() : null;

        return dbRepository.findAll(
                User.class,
                pageDetails,
                (cb, root) -> {
                    List<jakarta.persistence.criteria.Predicate> predicates =
                            new java.util.ArrayList<>();
                    predicates.add(cb.isFalse(root.get("isDeleted")));
                    // Tenant scope resolves through user_tenant_memberships, not
                    // users.tenant_id. An invited external auditor's home tenant is
                    // their own firm, so scoping on the column would hide them from
                    // the very client whose data they can reach — a client admin
                    // would have auditors in their tenant with no way to see or
                    // revoke them.
                    if (!isSystemUser) {
                        predicates.add(cb.exists(membershipOf(cb, root, tenantId, finalMembershipType)));
                    } else if (tenantIdParam != null) {
                        // SYSTEM caller drilling into one specific tenant
                        predicates.add(cb.exists(membershipOf(cb, root, effectiveTenantId, finalMembershipType)));
                    }
                    // else: SYSTEM caller with no tenantIdParam — unscoped, all tenants
                    // Filter by role side — only users who have at least one role with this side
                    if (finalRoleSide != null) {
                        if (finalRoleSide == com.kashi.grc.usermanagement.domain.RoleSide.VENDOR) {
                            // VENDOR side — must have a vendor_id (i.e. be a vendor user)
                            predicates.add(cb.isNotNull(root.get("vendorId")));
                            // ── VENDOR SCOPING FIX ──────────────────────────────────────────
                            // Scope to a specific vendor:
                            //   • Vendor-side callers: always their own vendorId (enforced above).
                            //   • Org/System callers: only when vendorId param was supplied
                            //     (e.g. org-admin viewing a vendor's team tab).
                            // Without this, ANY vendor user could see all vendors' users.
                            if (effectiveVendorId != null) {
                                predicates.add(cb.equal(root.get("vendorId"), effectiveVendorId));
                            }
                        } else {
                            // ORG/SYSTEM/AUDITOR/AUDITEE — no vendor_id
                            predicates.add(cb.isNull(root.get("vendorId")));
                            // For non-vendor sides, also filter by role side
                            if (!noRoles) {
                                // Roles are read through the membership for the tenant
                                // being listed. Without this a guest auditor's
                                // ORGANIZATION roles at their own firm would make them
                                // show up under side=ORGANIZATION in the client's list.
                                jakarta.persistence.criteria.Subquery<Long> sub =
                                        cb.createQuery(Long.class).subquery(Long.class);
                                jakarta.persistence.criteria.Root<com.kashi.grc.usermanagement.domain.UserRole> urRoot =
                                        sub.from(com.kashi.grc.usermanagement.domain.UserRole.class);
                                jakarta.persistence.criteria.Root<com.kashi.grc.usermanagement.domain.UserTenantMembership> memRoot =
                                        sub.from(com.kashi.grc.usermanagement.domain.UserTenantMembership.class);
                                jakarta.persistence.criteria.Root<com.kashi.grc.usermanagement.domain.Role> roleRoot =
                                        sub.from(com.kashi.grc.usermanagement.domain.Role.class);
                                sub.select(urRoot.get("userId"))
                                        .where(
                                                cb.equal(urRoot.get("userId"), root.get("id")),
                                                cb.equal(urRoot.get("membershipId"), memRoot.get("id")),
                                                cb.equal(memRoot.get("tenantId"),
                                                        isSystemUser && tenantIdParam != null
                                                                ? effectiveTenantId : tenantId),
                                                cb.equal(roleRoot.get("id"), urRoot.get("roleId")),
                                                cb.equal(roleRoot.get("side"), finalRoleSide));
                                predicates.add(cb.exists(sub));
                            }
                        }
                    } else if (effectiveVendorId != null) {
                        // No side filter but vendorId given — scope to that vendor regardless
                        predicates.add(cb.equal(root.get("vendorId"), effectiveVendorId));
                    }
                    // Role filter — user must hold the given role (e.g. LEAD_AUDITOR).
                    // Composable with the side filter above: side=AUDITOR & roleId=33
                    // → auditor-side users who are Lead Auditors.
                    if (finalRoleId != null) {
                        jakarta.persistence.criteria.Subquery<Long> roleSub =
                                cb.createQuery(Long.class).subquery(Long.class);
                        jakarta.persistence.criteria.Root<com.kashi.grc.usermanagement.domain.User> roleSubRoot =
                                roleSub.correlate(root);
                        jakarta.persistence.criteria.Join<Object, Object> rJoin = roleSubRoot.join("roles");
                        roleSub.select(roleSubRoot.get("id"))
                                .where(cb.equal(rJoin.get("id"), finalRoleId));
                        predicates.add(cb.exists(roleSub));
                    }
                    return predicates;
                },
                (cb, root) -> Map.of(
                        "email",      root.get("email"),
                        "firstname",  root.get("firstName"),
                        "lastname",   root.get("lastName"),
                        "status",     root.get("status"),
                        "department", root.get("department"),
                        "jobtitle",   root.get("jobTitle")
                ),
                this::toResponse
        );
    }

    /**
     * EXISTS subquery: "this user holds an active, unexpired membership in the
     * given tenant". Replaces the old cb.equal(root.get("tenantId"), tenantId)
     * so that invited external auditors are visible to the client whose tenant
     * they work in, and disappear again when the membership is revoked or its
     * access window closes.
     */
    private jakarta.persistence.criteria.Subquery<Long> membershipOf(
            jakarta.persistence.criteria.CriteriaBuilder cb,
            jakarta.persistence.criteria.Root<User> userRoot,
            Long tenantId,
            String membershipType) {

        jakarta.persistence.criteria.Subquery<Long> sub =
                cb.createQuery(Long.class).subquery(Long.class);
        jakarta.persistence.criteria.Root<com.kashi.grc.usermanagement.domain.UserTenantMembership> m =
                sub.from(com.kashi.grc.usermanagement.domain.UserTenantMembership.class);

        List<jakarta.persistence.criteria.Predicate> preds = new java.util.ArrayList<>();
        preds.add(cb.equal(m.get("userId"), userRoot.get("id")));
        preds.add(cb.equal(m.get("tenantId"), tenantId));
        preds.add(cb.equal(m.get("status"), "ACTIVE"));
        preds.add(cb.or(cb.isNull(m.get("accessExpiresAt")),
                cb.greaterThan(m.get("accessExpiresAt"),
                        cb.literal(java.time.LocalDateTime.now()))));
        if (membershipType != null) {
            preds.add(cb.equal(m.get("membershipType"), membershipType));
        }
        sub.select(m.get("userId")).where(preds.toArray(new jakarta.persistence.criteria.Predicate[0]));
        return sub;
    }


    // ─────────────────────────────────────────────────────────────
    // UPDATE
    // ─────────────────────────────────────────────────────────────
    @Override
    @Transactional
    public UserResponse updateUser(Long userId, UserUpdateRequest request) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        User user = userRepository.findByIdAndTenantIdAndIsDeletedFalse(userId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        if (request.getFirstName()  != null) user.setFirstName(request.getFirstName());
        if (request.getLastName()   != null) user.setLastName(request.getLastName());
        if (request.getDepartment() != null) user.setDepartment(request.getDepartment());
        if (request.getJobTitle()   != null) user.setJobTitle(request.getJobTitle());
        if (request.getPhone()      != null) user.setPhone(request.getPhone());
        if (request.getManagerId()  != null) user.setManagerId(request.getManagerId());
        if (request.getTimezone()   != null) user.setTimezone(request.getTimezone());

        user = userRepository.save(user);
        log.info("User updated: id={}", userId);
        return toResponse(user);
    }

    // ─────────────────────────────────────────────────────────────
    // DELETE (soft)
    // ─────────────────────────────────────────────────────────────
    @Override
    @Transactional
    public void deleteUser(Long userId) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        User user = userRepository.findByIdAndTenantIdAndIsDeletedFalse(userId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        user.setDeleted(true);
        user.setStatus(UserStatus.DEACTIVATED);
        userRepository.save(user);
        log.info("User soft-deleted: id={}", userId);
    }

    // ─────────────────────────────────────────────────────────────
    // STATUS MANAGEMENT
    // ─────────────────────────────────────────────────────────────
    @Override
    @Transactional
    public UserResponse suspendUser(Long userId) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        User user = userRepository.findByIdAndTenantIdAndIsDeletedFalse(userId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        user.setStatus(UserStatus.SUSPENDED);
        user = userRepository.save(user);
        log.info("User suspended: id={}", userId);
        return toResponse(user);
    }

    @Override
    @Transactional
    public UserResponse activateUser(Long userId) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        User user = userRepository.findByIdAndTenantIdAndIsDeletedFalse(userId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        user.setStatus(UserStatus.ACTIVE);
        user.resetFailedAttempts();
        user = userRepository.save(user);
        log.info("User activated: id={}", userId);
        return toResponse(user);
    }

    // ── STATUS ────────────────────────────────────────────────────

    @Override
    @Transactional
    public Map<String, Object> updateStatus(Long userId, UserStatusRequest req) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        User user = userRepository.findByIdAndTenantIdAndIsDeletedFalse(userId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        user.setStatus(UserStatus.valueOf(req.getStatus()));
        userRepository.save(user);
        return Map.of("user_id", userId, "new_status", req.getStatus(), "updated_at", java.time.LocalDateTime.now());
    }

    // ── RESPONSIBILITIES ──────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getResponsibilities(Long userId, boolean includeDelegations) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        User user = userRepository.findByIdAndTenantId(userId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        long activeDelegations = includeDelegations
                ? delegationRepository.countActiveDelegationsToMe(userId) : 0;
        return Map.of(
                "user_id", userId,
                "user_name", user.getFullName(),
                "can_deactivate", activeDelegations == 0,
                "blocking_responsibilities", Map.of(
                        "open_tasks", 0, "pending_workflows", 0,
                        "active_delegations", activeDelegations,
                        "owned_audits", 0, "assigned_assessments", 0),
                "recommended_actions", Collections.emptyList()
        );
    }

    // ── DEACTIVATE / REACTIVATE ───────────────────────────────────

    @Override
    @Transactional
    public Map<String, Object> deactivateUser(Long userId, UserDeactivateRequest req) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        User user = userRepository.findByIdAndTenantId(userId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        user.setStatus(UserStatus.DEACTIVATED);
        user.setDeleted(true);
        userRepository.save(user);
        return Map.of(
                "user_id", userId, "email", user.getEmail(),
                "deactivated_at", java.time.LocalDateTime.now(),
                "reassignments_completed", req.getReassignments() != null ? req.getReassignments().size() : 0,
                "audit_trail_preserved", req.isPreserveAuditTrail()
        );
    }

    @Override
    @Transactional
    public UserResponse reactivateUser(Long userId, UserReactivateRequest req) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        user.setStatus(UserStatus.ACTIVE);
        user.setDeleted(false);
        String tempPassword = null;
        if (req.isResetPasswordRequired()) {
            // Generate temporary password; user must reset on first login
            tempPassword = UUID.randomUUID().toString();
            user.setPasswordHash(passwordEncoder.encode(tempPassword));
            user.setPasswordResetRequired(true);
            if (req.isSendReactivationEmail()) {
                mailService.sendUserInvitation(user.getEmail(), user.getFirstName(),
                        "https://app.kashigrc.com/set-password?token=" + tempPassword);
            }
        }
        userRepository.save(user);
        UserResponse response = toResponse(user);
        response.setTemporaryPassword(tempPassword);
        return response;
    }

    // ── PASSWORD ──────────────────────────────────────────────────

    @Override
    @Transactional
    public Map<String, Object> changePassword(ChangePasswordRequest req) {
        Long userId = utilityService.getLoggedInDataContext().getId();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        if (!req.isFirstLoginReset() && req.getCurrentPassword() != null) {
            if (!passwordEncoder.matches(req.getCurrentPassword(), user.getPasswordHash())) {
                throw new BusinessException("AUTH_INVALID_CREDENTIALS",
                        "Current password is incorrect", HttpStatus.UNAUTHORIZED);
            }
        }
        user.setPasswordHash(passwordEncoder.encode(req.getNewPassword()));
        user.setPasswordResetRequired(false);
        userRepository.save(user);
        return Map.of(
                "message", "Password changed successfully",
                "user_id", userId,
                "changed_at", java.time.LocalDateTime.now(),
                "password_expires_at", java.time.LocalDateTime.now().plusDays(90)
        );
    }

    // ── ACCESS SUMMARY ────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public UserAccessSummary getAccessSummary(Long userId) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        User user = userRepository.findByIdAndTenantId(userId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        // Use helper methods to avoid duplication and fix type errors
        List<RoleInfoResponse> detailedRoles = mapToDetailedRoles(user);
        Map<String, String> attrs = mapAttributes(user);

        long delegatedToMe = delegationRepository.countActiveDelegationsToMe(userId);
        long delegatedByMe = delegationRepository.countActiveDelegationsByMe(userId);

        return UserAccessSummary.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .status(user.getStatus().name())
                .roles(detailedRoles) // Fixed: Now matches RoleInfoResponse
                .attributes(attrs)
                .tenant(UserAccessSummary.TenantRef.builder().tenantId(tenantId).build())
                .vendorAccess(Collections.emptyList())
                .activeDelegations(UserAccessSummary.DelegationCounts.builder()
                        .delegatedToMe(delegatedToMe).delegatedByMe(delegatedByMe).build())
                .activeAssignments(UserAccessSummary.AssignmentCounts.builder()
                        .workflows(0L).tasks(0L).assessments(0L).build()) // Fixed: used 0L for Long
                .sodStatus(UserAccessSummary.SodStatus.builder().violations(0L).warnings(0L).build()) // Fixed: 0L
                .lastLogin(user.getLastLogin())
                .build();
    }

    // ── ACTIVITY LOG ──────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getActivityLog(Long userId, String startDate, String endDate,
                                              String actionType, int limit) {
        return Map.of("user_id", userId, "total_events", 0, "events", Collections.emptyList());
    }

    // ── BULK UPLOAD ───────────────────────────────────────────────

    @Override
    @Transactional
    public Map<String, Object> bulkUpload(org.springframework.web.multipart.MultipartFile file,
                                          Long defaultRoleId, boolean sendWelcomeEmails) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        log.info("Bulk upload for tenant {} file={}", tenantId, file.getOriginalFilename());
        return Map.of("total_rows", 0, "successful", 0, "failed", 0,
                "results", Collections.emptyList());
    }

    // ─────────────────────────────────────────────────────────────
    // MAPPER
    // ─────────────────────────────────────────────────────────────

    private Map<String, String> mapAttributes(User user) {
        return user.getAttributes().stream()
                .collect(Collectors.toMap(
                        UserAttribute::getAttributeKey,
                        a -> a.getAttributeValue() != null ? a.getAttributeValue() : "",
                        (existing, replacement) -> replacement));
    }

    private List<RoleInfoResponse> mapToDetailedRoles(User user) {
        return user.getRoles().stream()
                .map(r -> RoleInfoResponse.builder()
                        .roleId(r.getId())
                        .roleName(r.getName())
                        .side(r.getSide() != null ? r.getSide().name() : null)
                        .level(r.getLevel() != null ? r.getLevel().name() : null)
                        .permissionsCount(r.getPermissions() != null ? r.getPermissions().size() : 0)
                        .userCount(0L)
                        .build())
                .collect(Collectors.toList());
    }

    private UserResponse toResponse(User user) {
        List<AuthResponse.RoleInfo> roleInfos = user.getRoles().stream()
                .map(r -> AuthResponse.RoleInfo.builder()
                        .roleId(r.getId())
                        .roleName(r.getName())
                        .side(r.getSide() != null ? r.getSide().name() : null)
                        .level(r.getLevel() != null ? r.getLevel().name() : null)
                        .build())
                .collect(Collectors.toList());

        return UserResponse.builder()
                .userId(user.getId())
                .tenantId(user.getTenantId())
                .vendorId(user.getVendorId())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .fullName(user.getFullName())
                .department(user.getDepartment())
                .jobTitle(user.getJobTitle())
                .phone(user.getPhone())
                .status(user.getStatus().name())
                .timezone(user.getTimezone())
                .lastLogin(user.getLastLogin())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .roles(roleInfos)
                .attributes(mapAttributes(user))
                .passwordResetRequired(Boolean.TRUE.equals(user.getPasswordResetRequired()))
                .build();
    }

    private static final List<String> ALLOWED_PREF_KEYS =
            java.util.Arrays.asList("ui_app_theme", "ui_sidebar_theme", "ui_sidebar_color");

    @Override
    @Transactional
    public void savePreferences(Map<String, String> prefs) {
        User user = utilityService.getLoggedInDataContext();
        prefs.forEach((key, value) -> {
            if (!ALLOWED_PREF_KEYS.contains(key)) return;
            attributeRepository.findByUserIdAndAttributeKey(user.getId(), key)
                    .ifPresentOrElse(
                            attr -> attr.setAttributeValue(value),
                            () -> attributeRepository.save(
                                    UserAttribute.builder()
                                            .user(user)
                                            .attributeKey(key)
                                            .attributeValue(value)
                                            .build())
                    );
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, String> getPreferences() {
        User user = utilityService.getLoggedInDataContext();
        Map<String, String> result = new java.util.HashMap<>();
        ALLOWED_PREF_KEYS.forEach(key ->
                attributeRepository.findByUserIdAndAttributeKey(user.getId(), key)
                        .ifPresent(attr -> result.put(key, attr.getAttributeValue()))
        );
        return result;
    }
}