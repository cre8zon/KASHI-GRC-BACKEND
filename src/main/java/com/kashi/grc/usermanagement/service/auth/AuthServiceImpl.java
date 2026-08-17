package com.kashi.grc.usermanagement.service.auth;

import com.kashi.grc.common.config.multitenancy.TenantContext;
import com.kashi.grc.common.config.security.JwtTokenProvider;
import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.dto.ErrorResponse;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.exception.ValidationException;
import com.kashi.grc.common.service.MailService;
import com.kashi.grc.common.util.Constants;
import com.kashi.grc.common.util.DateTimeUtils;

import com.kashi.grc.tenant.repository.TenantRepository;
import com.kashi.grc.usermanagement.domain.User;
import com.kashi.grc.usermanagement.domain.UserStatus;
import com.kashi.grc.usermanagement.dto.request.LoginRequest;
import com.kashi.grc.usermanagement.dto.request.PasswordResetRequest;
import com.kashi.grc.usermanagement.dto.request.ResendInvitationRequest;
import com.kashi.grc.usermanagement.dto.request.ResetPasswordRequest;
import com.kashi.grc.usermanagement.dto.response.AuthResponse;
import com.kashi.grc.usermanagement.exception.AccountLockedException;
import com.kashi.grc.usermanagement.exception.PasswordExpiredException;
import com.kashi.grc.usermanagement.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRepository      userRepository;
    private final com.kashi.grc.usermanagement.repository.UserTenantMembershipRepository membershipRepository;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;
    private final TenantRepository    tenantRepository;
    private final com.kashi.grc.vendor.repository.VendorRepository vendorRepository;
    private final PasswordEncoder     passwordEncoder;
    private final JwtTokenProvider    tokenProvider;
    private final MailService         mailService;

    @Value("${kashi.app.base-url:https://app.kashigrc.com}")
    private String appBaseUrl;

    // ─────────────────────────────────────────────────────────────
    // LOGIN
    // ─────────────────────────────────────────────────────────────
    @Override
    @Transactional
    public ApiResponse<?> login(LoginRequest request) {
        log.info("Login attempt for: {}", request.getEmail());

        // 1. Find user
        User user = userRepository.findByEmailAndIsDeletedFalse(request.getEmail())
                .orElseThrow(() -> {
                    log.warn("Login failed - user not found: {}", request.getEmail());
                    return new ValidationException("Invalid email or password");
                });

        // 2. SECURITY ANCHOR: ENFORCE ISOLATION IMMEDIATELY
        // Once the user is found, we lock the thread to their tenant.
        // This ensures any further database calls (Roles, Permissions) are strictly isolated.
        TenantContext.setCurrentTenant(user.getTenantId());
        log.debug("TenantContext anchored to: {} for user: {}", user.getTenantId(), user.getId());

        // 2. Check if locked — auto-unlock after timeout
        if (UserStatus.LOCKED.equals(user.getStatus())) {
            if (user.getLockoutUntil() != null && DateTimeUtils.isExpired(user.getLockoutUntil())) {
                log.info("Auto-unlocking account: {}", user.getEmail());
                user.setStatus(UserStatus.ACTIVE);
                user.resetFailedAttempts();
            } else {
                throw new AccountLockedException(user.getLockoutUntil());
            }
        }

        // 3. Check general status
        if (!UserStatus.ACTIVE.equals(user.getStatus())) {
            throw new ValidationException("Account is not active");
        }

        log.info("--- LOGIN DEBUG START ---");
        log.info("Email: {}", request.getEmail());
        log.info("Raw Password Length: {}", request.getPassword().length());
        log.info("Stored Hash: {}", user.getPasswordHash());

        boolean isMatch = passwordEncoder.matches(request.getPassword(), user.getPasswordHash());
        log.info("Matches? {}", isMatch);
        log.info("--- LOGIN DEBUG END ---");

        // 4. Verify password
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            user.incrementFailedAttempts();
            log.warn("Invalid password for: {} (attempt {})", request.getEmail(), user.getFailedLoginAttempts());

            if (user.getFailedLoginAttempts() >= Constants.MAX_FAILED_ATTEMPTS) {
                user.setStatus(UserStatus.LOCKED);
                user.setLockoutUntil(DateTimeUtils.plusMinutes(Constants.LOCKOUT_MINUTES));
                log.warn("Account locked: {} until {}", request.getEmail(), user.getLockoutUntil());
            }
            userRepository.save(user);
            throw new ValidationException("Invalid email or password");
        }

        // 5. Reset failed attempts on success
        user.resetFailedAttempts();

        // 6. Password reset required on first login
        if (Boolean.TRUE.equals(user.getPasswordResetRequired())) {
            String tempToken = generateSecureToken();
            user.setPasswordResetToken(tempToken);
            user.setPasswordResetExpiry(DateTimeUtils.plusMinutes(30));
            userRepository.save(user);
            log.info("Password reset required for first-time login: {}", user.getEmail());

            return ApiResponse.withStatus("PASSWORD_RESET_REQUIRED",
                    AuthResponse.PasswordResetRequired.builder()
                            .userId(user.getId())
                            .message("You must change your password on first login")
                            .tempToken(tempToken)
                            .passwordPolicy(buildPasswordPolicy())
                            .build());
        }

        // 7. Build token with roles + permissions
        List<String>   roleIds     = user.getRoles().stream().map(r -> r.getName()).collect(Collectors.toList());
        List<String> permissions = user.getRoles().stream()
                .flatMap(r -> r.getPermissions().stream())
                .map(p -> p.getCode())
                .distinct()
                .collect(Collectors.toList());

        // ── Which tenant is this session for? ────────────────────────────
        // Almost everyone holds exactly one membership, in their own tenant, and
        // this resolves to user.getTenantId() — byte-for-byte today's behaviour.
        // It differs only for an identity that belongs to more than one tenant,
        // which today means an external auditor staffed onto a client.
        //
        // A user with NO membership row falls back to user.getTenantId() rather
        // than being refused: the backfill may not have run, and locking people
        // out over a missing row in an additive migration would be unforgivable.
        List<com.kashi.grc.usermanagement.domain.UserTenantMembership> memberships =
                membershipRepository.findByUserIdAndStatus(user.getId(), "ACTIVE").stream()
                        .filter(com.kashi.grc.usermanagement.domain.UserTenantMembership::isUsable)
                        .toList();

        com.kashi.grc.usermanagement.domain.UserTenantMembership active = memberships.stream()
                .filter(com.kashi.grc.usermanagement.domain.UserTenantMembership::isPrimary)
                .findFirst()
                .or(() -> memberships.stream()
                        .filter(m -> m.getTenantId().equals(user.getTenantId())).findFirst())
                .or(() -> memberships.stream().findFirst())
                .orElse(null);

        Long activeTenantId = active != null ? active.getTenantId() : user.getTenantId();

        // Roles are per membership. Only re-resolve when the session is NOT the
        // home tenant — otherwise user.getRoles() is already correct and we
        // avoid touching the path every existing login takes.
        if (active != null && !activeTenantId.equals(user.getTenantId())) {
            List<Long> roleIdList = membershipRepository.findRoleIdsByMembershipId(active.getId());
            roleIds     = rolesFor(roleIdList).stream().map(r -> r.getName()).collect(Collectors.toList());
            permissions = rolesFor(roleIdList).stream()
                    .flatMap(r -> r.getPermissions().stream())
                    .map(p -> p.getCode()).distinct().collect(Collectors.toList());
            TenantContext.setCurrentTenant(activeTenantId);
        }

        String accessToken  = tokenProvider.generateAccessToken(user.getId(), activeTenantId,
                user.getEmail(), roleIds, permissions);
        String refreshToken = tokenProvider.generateRefreshToken(user.getId());

        // 8. Update last login
        user.setLastLogin(LocalDateTime.now());
        userRepository.save(user);

        log.info("Login successful: userId={} tenantId={}", user.getId(), user.getTenantId());

        AuthResponse response = buildAuthResponse(user, permissions, accessToken, refreshToken);
        attachMemberships(response, memberships, activeTenantId);
        return ApiResponse.success(response);
    }

    // ─────────────────────────────────────────────────────────────
    // SWITCH TENANT
    // ─────────────────────────────────────────────────────────────

    /**
     * Re-issues the session against another tenant this identity belongs to.
     *
     * This is the only path that can put a tenant OTHER than users.tenant_id in
     * a token, and therefore the only thing that makes a GUEST membership do
     * anything: UtilityService's overlay stays dormant until the JWT names a
     * tenant that differs from the user's home one.
     *
     * Revocation and expiry are re-checked here, not merely at login — a session
     * must not be re-scoped into a tenant the client has since withdrawn.
     */
    @Override
    @Transactional
    public ApiResponse<?> switchTenant(Long userId, Long tenantId) {

        User user = userRepository.findById(userId)
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new ValidationException("User not found"));

        com.kashi.grc.usermanagement.domain.UserTenantMembership target =
                membershipRepository.findByUserIdAndTenantId(userId, tenantId)
                        .orElseThrow(() -> new ValidationException(
                                "You do not have access to that organization"));

        if (!target.isUsable()) {
            throw new ValidationException("Your access to that organization has ended");
        }

        TenantContext.setCurrentTenant(tenantId);

        List<String> roleIds;
        List<String> permissions;

        if (tenantId.equals(user.getTenantId())) {
            // Home tenant — the roles already on the user are the right ones.
            roleIds     = user.getRoles().stream().map(r -> r.getName()).collect(Collectors.toList());
            permissions = user.getRoles().stream()
                    .flatMap(r -> r.getPermissions().stream())
                    .map(p -> p.getCode()).distinct().collect(Collectors.toList());
        } else {
            List<com.kashi.grc.usermanagement.domain.Role> roles =
                    rolesFor(membershipRepository.findRoleIdsByMembershipId(target.getId()));
            roleIds     = roles.stream().map(r -> r.getName()).collect(Collectors.toList());
            permissions = roles.stream().flatMap(r -> r.getPermissions().stream())
                    .map(p -> p.getCode()).distinct().collect(Collectors.toList());
        }

        String accessToken  = tokenProvider.generateAccessToken(
                user.getId(), tenantId, user.getEmail(), roleIds, permissions);
        String refreshToken = tokenProvider.generateRefreshToken(user.getId());

        log.info("[TENANT-SWITCH] userId={} → tenantId={} membershipId={} type={} roles={}",
                userId, tenantId, target.getId(), target.getMembershipType(), roleIds);

        AuthResponse response = buildAuthResponse(user, permissions, accessToken, refreshToken);
        // buildAuthResponse reads user.getTenantId(); the session is for the
        // tenant just switched into, so correct it before returning.
        response.getUser().setTenantId(tenantId);
        response.getUser().setTenantName(
                tenantRepository.findById(tenantId).map(t -> t.getName()).orElse(""));

        List<com.kashi.grc.usermanagement.domain.UserTenantMembership> all =
                membershipRepository.findByUserIdAndStatus(userId, "ACTIVE").stream()
                        .filter(com.kashi.grc.usermanagement.domain.UserTenantMembership::isUsable)
                        .toList();
        attachMemberships(response, all, tenantId);
        return ApiResponse.success(response);
    }

    /** Roles with permissions eagerly loaded, for a membership's role ids. */
    private List<com.kashi.grc.usermanagement.domain.Role> rolesFor(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) return List.of();
        return entityManager.createQuery(
                        "SELECT DISTINCT r FROM Role r LEFT JOIN FETCH r.permissions WHERE r.id IN :ids",
                        com.kashi.grc.usermanagement.domain.Role.class)
                .setParameter("ids", roleIds)
                .getResultList();
    }

    /** Adds the tenant list to the response so the UI can offer a switcher. */
    private void attachMemberships(
            AuthResponse response,
            List<com.kashi.grc.usermanagement.domain.UserTenantMembership> memberships,
            Long activeTenantId) {

        if (response.getUser() == null || memberships == null) return;

        response.getUser().setMemberships(memberships.stream()
                .map(m -> AuthResponse.TenantMembershipInfo.builder()
                        .membershipId(m.getId())
                        .tenantId(m.getTenantId())
                        .tenantName(tenantRepository.findById(m.getTenantId())
                                .map(t -> t.getName()).orElse(""))
                        .membershipType(m.getMembershipType())
                        .firmName(m.getFirmTenantId() == null ? null
                                : tenantRepository.findById(m.getFirmTenantId())
                                  .map(t -> t.getName()).orElse(null))
                        .accessExpiresAt(m.getAccessExpiresAt())
                        .active(m.getTenantId().equals(activeTenantId))
                        .build())
                .collect(Collectors.toList()));
    }

    // ─────────────────────────────────────────────────────────────
    // LOGOUT
    // ─────────────────────────────────────────────────────────────
    @Override
    @Transactional
    public void logout(Long userId) {
        // JWT is stateless; client discards token.
        // Log the event for audit purposes.
        log.info("User logged out: userId={}", userId);
    }

    // ─────────────────────────────────────────────────────────────
    // REQUEST PASSWORD RESET
    // ─────────────────────────────────────────────────────────────
    @Override
    @Transactional
    public void requestPasswordReset(PasswordResetRequest request) {
        // Security: always succeed even if email doesn't exist (prevent enumeration)
        userRepository.findByEmailAndIsDeletedFalse(request.getEmail()).ifPresent(user -> {
            String token = generateSecureToken();
            user.setPasswordResetToken(token);
            user.setPasswordResetExpiry(DateTimeUtils.plusMinutes(Constants.RESET_TOKEN_TTL_MINS));
            userRepository.save(user);
            log.info("Password reset token issued for: {}", request.getEmail());

            // Lane 1 (transactional/security) — direct MailService, deliberately
            // bypasses notification rules/preferences. afterCommit so the email
            // (or its kashigrc.email.requested event) never fires for a token
            // that was rolled back.
            final String toEmail   = user.getEmail();
            final String firstName = user.getFirstName() != null ? user.getFirstName() : "there";
            final String resetUrl  = appBaseUrl + "/auth/reset-password?token=" + token;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    mailService.sendPasswordReset(toEmail, firstName, resetUrl);
                }
            });
        });
    }

    // ─────────────────────────────────────────────────────────────
    // RESET PASSWORD
    // ─────────────────────────────────────────────────────────────
    @Override
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        User user = userRepository.findByPasswordResetTokenAndIsDeletedFalse(request.getToken())
                .orElseThrow(() -> new ValidationException("Invalid or expired reset token"));

        if (DateTimeUtils.isExpired(user.getPasswordResetExpiry())) {
            throw new ValidationException("Reset token has expired. Please request a new one.");
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        user.setPasswordResetToken(null);
        user.setPasswordResetExpiry(null);
        user.setPasswordResetRequired(false);
        userRepository.save(user);

        log.info("Password reset successfully for userId={}", user.getId());
    }


    @Transactional
    public String generateAndSaveInvitationToken(User user) {
        String token = generateSecureToken();
        user.setPasswordResetToken(token);
        user.setPasswordResetExpiry(LocalDateTime.now().plusHours(24));
        user.setPasswordResetRequired(true);

        // We save here to ensure the token is persisted
        userRepository.save(user);
        return token;
    }

    // ─────────────────────────────────────────────────────────────
    // RESEND INVITATION — generate new temp password + optional email
    // ─────────────────────────────────────────────────────────────
    @Override
    @Transactional
    public ApiResponse<Map<String, Object>> resendInvitation(ResendInvitationRequest req) {

        User admin = userRepository.findById(req.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User", req.getUserId()));

        // Generate new temp password with guaranteed complexity
        String newTempPassword = UUID.randomUUID().toString().replace("-", "").substring(0, 16) + "Aa1!";

        String orgName = "";
        try {
            if (admin.getTenantId() != null) {
                orgName = tenantRepository.findById(admin.getTenantId())
                        .map(t -> t.getName()).orElse("");
            }
        } catch (Exception ignored) {}

        admin.setPasswordHash(passwordEncoder.encode(newTempPassword));
        admin.setPasswordResetRequired(true);
        admin.setPasswordResetToken(null);
        admin.setPasswordResetExpiry(null);
        userRepository.save(admin);

        log.info("Invitation resent — new temp password generated for userId={}", admin.getId());

        if (req.isSendEmail()) {
            String firstName = admin.getFirstName() != null ? admin.getFirstName() : "Admin";
            String loginUrl  = appBaseUrl + "/auth/login";
            mailService.send("user-invitation", req.getEmail(), Map.ofEntries(
                    Map.entry("firstName",         firstName),
                    Map.entry("email",             admin.getEmail()),
                    Map.entry("admin_email",       admin.getEmail()),
                    Map.entry("tempPassword",      newTempPassword),
                    Map.entry("temp_password",     newTempPassword),
                    Map.entry("loginUrl",          loginUrl),
                    Map.entry("login_url",         loginUrl),
                    Map.entry("resetUrl",          loginUrl),
                    Map.entry("organization_name", orgName),
                    Map.entry("supportUrl",        "https://support.kashigrc.com"),
                    Map.entry("support_url",       "https://support.kashigrc.com"),
                    Map.entry("admin_name",        firstName)
            ));
        }

        return ApiResponse.success(Map.of(
                "userId",            admin.getId(),
                "email",             admin.getEmail(),
                "temporaryPassword", newTempPassword,
                "passwordReset",     true,
                "emailSent",         req.isSendEmail()
        ));
    }

    // ─────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────
    private AuthResponse buildAuthResponse(User user, List<String> permissions,
                                           String accessToken, String refreshToken) {
        List<AuthResponse.RoleInfo> roleInfos = user.getRoles().stream()
                .map(r -> AuthResponse.RoleInfo.builder()
                        .roleId(r.getId())
                        .roleName(r.getName())
                        .side(r.getSide() != null ? r.getSide().name() : null)
                        .level(r.getLevel() != null ? r.getLevel().name() : null)
                        .build())
                .collect(Collectors.toList());

        Map<String, String> attributes = user.getAttributes().stream()
                .collect(Collectors.toMap(
                        a -> a.getAttributeKey(),
                        a -> a.getAttributeValue() != null ? a.getAttributeValue() : ""));

        return AuthResponse.builder()
                .user(AuthResponse.UserInfo.builder()
                        .userId(user.getId())
                        .email(user.getEmail())
                        .fullName(user.getFullName())
                        .tenantId(user.getTenantId())
                        .tenantName(tenantRepository.findById(user.getTenantId())
                                .map(t -> t.getName()).orElse(""))
                        .vendorId(user.getVendorId())
                        .vendorName(user.getVendorId() != null
                                ? vendorRepository.findById(user.getVendorId())
                                  .map(v -> v.getName()).orElse("")
                                : null)
                        .status(user.getStatus().name())
                        .requiresPasswordReset(user.getPasswordResetRequired())
                        .roles(roleInfos)
                        .permissions(permissions)
                        .attributes(attributes)
                        .build())
                .session(AuthResponse.SessionInfo.builder()
                        .token(accessToken)
                        .expiresAt(LocalDateTime.now().plusSeconds(86400))
                        .refreshToken(refreshToken)
                        .build())
                .build();
    }

    private AuthResponse.PasswordPolicy buildPasswordPolicy() {
        return AuthResponse.PasswordPolicy.builder()
                .minLength(12)
                .requireUppercase(true)
                .requireLowercase(true)
                .requireNumbers(true)
                .requireSpecialChars(true)
                .build();
    }

    private String generateSecureToken() {
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }
}