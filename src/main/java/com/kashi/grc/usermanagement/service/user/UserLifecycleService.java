package com.kashi.grc.usermanagement.service.user;

import com.kashi.grc.common.config.security.CustomUserDetailsService;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.security.AccountAccessService;
import com.kashi.grc.common.security.PermissionGate;
import com.kashi.grc.common.service.MailService;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.tenant.repository.TenantRepository;
import com.kashi.grc.usermanagement.domain.AccountStatusChange;
import com.kashi.grc.usermanagement.domain.Delegation;
import com.kashi.grc.usermanagement.domain.User;
import com.kashi.grc.usermanagement.domain.UserStatus;
import com.kashi.grc.usermanagement.repository.AccountStatusChangeRepository;
import com.kashi.grc.usermanagement.repository.DelegationRepository;
import com.kashi.grc.usermanagement.repository.UserRepository;
import com.kashi.grc.workflow.service.WorkflowEngineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every change to whether a person may use the product — suspend, activate,
 * deactivate, reactivate, delete — goes through here, so every path follows
 * the same rules and leaves the same trail.
 *
 * ── WHO MAY ───────────────────────────────────────────────────────────────────
 * Before this, none of the status endpoints checked anything: any signed-in
 * user of a tenant could suspend anyone in it, including vendor users (who
 * share the organisation's tenant) suspending the Org Owner, and reactivate
 * worked across tenants. Now:
 *   • the caller holds USER_EDIT (the code user management already gates on;
 *     a permission, never a role name) or is a platform administrator;
 *   • the target is in the caller's organisation (404 otherwise, so ids in
 *     other tenants cannot be probed);
 *   • a vendor-side caller may only manage people of their own vendor;
 *   • nobody can suspend, deactivate or delete themselves;
 *   • the Org Owner cannot be suspended, deactivated or deleted at all — the
 *     owner changes only by transfer (OrgOwnerService), and an owner is only
 *     suspended with the whole organisation (platform administrators).
 *
 * ── WHAT HAPPENS WHEN SOMEONE STOPS BEING ACTIVE ─────────────────────────────
 *   • a row in account_status_changes: who, when, why, from what;
 *   • their open workflow tasks go back to unassigned (the organisation's
 *     rule), so nothing waits on them and nothing silently lands on somebody
 *     else — WorkflowEngineService.releaseTasksOfInactiveUser;
 *   • delegations to and from them end;
 *   • their cached sign-in data is dropped, so the very next request is
 *     refused (AccountAccessService), and token refresh refuses them too.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserLifecycleService {

    /** The permission user management already uses for acting on another user. */
    public static final String EDIT_PERMISSION = "USER_EDIT";

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final DelegationRepository delegationRepository;
    private final AccountStatusChangeRepository statusChangeRepository;
    private final UtilityService utilityService;
    private final PermissionGate permissionGate;
    private final MailService mailService;
    /** Lazily: the workflow engine is a large bean that itself reads users. */
    private final ObjectProvider<WorkflowEngineService> workflow;
    /** Their other assignments: action items, assessment and audit work, task sections. */
    private final AssignmentReleaseService assignments;
    private final com.kashi.grc.common.security.LiveSessionRegistry liveSessions;

    @Value("${kashi.app.base-url:https://app.kashigrc.com}")
    private String appBaseUrl;

    // ── Commands ─────────────────────────────────────────────────────────────

    @Transactional
    public User suspend(Long userId, String reason) {
        User target = requireManageable(userId, true, false);
        makeInactive(target, UserStatus.SUSPENDED, reason, false);
        return target;
    }

    @Transactional
    public User activate(Long userId) {
        User target = requireManageable(userId, false, false);
        UserStatus from = target.getStatus();
        if (target.isDeleted()) {
            throw new BusinessException("USER_DEACTIVATED",
                    "This account was deactivated. Use Reactivate to restore it.", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        target.setStatus(UserStatus.ACTIVE);
        target.resetFailedAttempts();
        userRepository.save(target);
        record(target, from, UserStatus.ACTIVE, null, null);
        dropCaches(target.getId());
        log.info("[USER-LIFECYCLE] Activated | userId={} | by={}", target.getId(), callerId());
        return target;
    }

    /**
     * PATCH /users/{id}/status. Only ACTIVE and SUSPENDED: LOCKED belongs to the
     * sign-in lockout, and DEACTIVATED has its own endpoint because it also
     * removes the account. Any other value used to be accepted and stored.
     */
    @Transactional
    public User changeStatus(Long userId, String status, String reason) {
        String s = status == null ? "" : status.trim().toUpperCase();
        return switch (s) {
            case "ACTIVE"    -> activate(userId);
            case "SUSPENDED" -> suspend(userId, reason);
            default -> throw new BusinessException("INVALID_STATUS",
                    "Status must be ACTIVE or SUSPENDED. To remove someone, use Deactivate.",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        };
    }

    @Transactional
    public Map<String, Object> deactivate(Long userId, String reason) {
        User target = requireManageable(userId, true, false);
        int released = makeInactive(target, UserStatus.DEACTIVATED, reason, true);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user_id", target.getId());
        out.put("email", target.getEmail());
        out.put("deactivated_at", LocalDateTime.now());
        // Honest numbers: tasks are returned to unassigned, not moved to a
        // named person (the organisation's rule for inactive assignees).
        out.put("tasks_returned_to_unassigned", released);
        out.put("reassignments_completed", 0);
        out.put("audit_trail_preserved", true);
        return out;
    }

    /** Soft delete — same rules and same cleanup as deactivation. */
    @Transactional
    public void delete(Long userId) {
        User target = requireManageable(userId, true, false);
        makeInactive(target, UserStatus.DEACTIVATED, "Deleted", true);
    }

    /**
     * Bring back a suspended or deactivated account. Tenant-scoped (it used to
     * find the user by id alone, across every tenant). When a new password is
     * required, the person gets a normal reset link by email — never the
     * password itself, which used to be emailed inside a link and returned in
     * the response.
     */
    @Transactional
    public User reactivate(Long userId, boolean resetPassword, boolean sendEmail) {
        User target = requireManageable(userId, false, true);
        UserStatus from = target.getStatus();
        target.setStatus(UserStatus.ACTIVE);
        target.setDeleted(false);
        target.resetFailedAttempts();
        if (resetPassword) {
            String token = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
            target.setPasswordResetToken(token);
            target.setPasswordResetExpiry(LocalDateTime.now().plusHours(72));
            target.setPasswordResetRequired(true);
            if (sendEmail) {
                final String to = target.getEmail();
                final String first = target.getFirstName() != null ? target.getFirstName() : "there";
                final String url = appBaseUrl + "/auth/reset-password?token=" + token;
                afterCommit(() -> mailService.sendPasswordReset(to, first, url));
            }
        }
        userRepository.save(target);
        record(target, from, UserStatus.ACTIVE, "Reactivated", null);
        dropCaches(target.getId());
        log.info("[USER-LIFECYCLE] Reactivated | userId={} | resetPassword={} | by={}",
                target.getId(), resetPassword, callerId());
        return target;
    }

    /** What suspending this person would affect — shown in the dialog first. */
    @Transactional(readOnly = true)
    public Map<String, Object> responsibilities(Long userId) {
        User target = requireManageable(userId, false, true);
        long tasks = workflow.getObject().countOpenTasks(target.getId());
        long toMe = delegationRepository.countActiveDelegationsToMe(target.getId());
        long byMe = delegationRepository.countActiveDelegationsByMe(target.getId());
        boolean owner = isOwner(target);
        Map<String, Object> blocking = new LinkedHashMap<>();
        blocking.put("open_tasks", tasks);
        blocking.put("active_delegations", toMe + byMe);
        // action_items, reviews, assessment_assignments, audit_assignments
        blocking.putAll(assignments.count(target.getId()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user_id", target.getId());
        out.put("user_name", target.getFullName().trim());
        out.put("is_owner", owner);
        out.put("can_deactivate", !owner);
        out.put("blocking_responsibilities", blocking);
        out.put("recommended_actions", owner
                ? List.of("Transfer ownership to another person first.")
                : tasks > 0 ? List.of("Their " + tasks + " open task(s) will go back to unassigned for someone to pick up.")
                : List.of());
        return out;
    }

    /** Status history for a user, newest first. */
    @Transactional(readOnly = true)
    public List<AccountStatusChange> history(Long userId) {
        User target = requireManageable(userId, false, true);
        return statusChangeRepository.findBySubjectTypeAndSubjectIdOrderByIdDesc(AccountStatusChange.USER, target.getId());
    }

    // ── Rules ────────────────────────────────────────────────────────────────

    /**
     * The target as this caller may manage them, or an exception.
     *
     * @param leavingActive  the action takes access away (suspend, deactivate,
     *                       delete): refused for oneself and for the owner
     * @param includeDeleted reactivate / read paths reach deactivated accounts
     */
    private User requireManageable(Long userId, boolean leavingActive, boolean includeDeleted) {
        permissionGate.require(EDIT_PERMISSION);
        User caller = utilityService.getLoggedInDataContext();

        User target = userRepository.findById(userId)
                .filter(u -> includeDeleted || !u.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        permissionGate.requireSameTenant(target.getTenantId(), caller.getTenantId());

        // A vendor's people manage their own colleagues only — never the
        // organisation's staff or another vendor's.
        if (!permissionGate.isPlatform() && caller.getVendorId() != null
                && !caller.getVendorId().equals(target.getVendorId())) {
            throw new ResourceNotFoundException("User", userId);
        }
        if (leavingActive && target.getId().equals(caller.getId())) {
            throw new BusinessException("SELF_SUSPEND", "You can't suspend or remove your own account.",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (leavingActive && isOwner(target)) {
            throw new BusinessException("OWNER_PROTECTED",
                    "This is the organisation's owner. Transfer ownership to someone else first — "
                            + "an owner can't be suspended or removed on their own.",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return target;
    }

    private boolean isOwner(User u) {
        return u.getTenantId() != null && tenantRepository.findById(u.getTenantId())
                .map(t -> u.getId().equals(t.getOwnerUserId())).orElse(false);
    }

    // ── Effects ──────────────────────────────────────────────────────────────

    /** Returns how many open tasks went back to unassigned. */
    private int makeInactive(User target, UserStatus to, String reason, boolean remove) {
        UserStatus from = target.getStatus();
        Long actor = callerId();

        target.setStatus(to);
        if (remove) target.setDeleted(true);
        userRepository.save(target);

        // Delegations to and from them end now.
        int delegations = 0;
        for (Delegation d : delegationRepository.findActive(target.getTenantId(), target.getId(), null, LocalDateTime.now())) {
            d.setStatus("REVOKED");
            d.setRevokedAt(LocalDateTime.now());
            d.setRevokedBy(actor);
            d.setRevokeReason("Account " + to.name().toLowerCase() + (reason == null || reason.isBlank() ? "" : ": " + reason.trim()));
            delegationRepository.save(d);
            delegations++;
        }

        // Their open tasks go back to unassigned.
        int released = workflow.getObject().releaseTasksOfInactiveUser(target.getId(), actor, reason);
        // And everything finer-grained they were holding goes back to whoever hands it out.
        AssignmentReleaseService.Released other =
                assignments.release(target.getId(), actor, target.getFullName().trim(), reason);

        record(target, from, to, reason,
                "open tasks returned to unassigned: " + released + "; delegations ended: " + delegations
                        + "; " + other.summary());
        dropCaches(target.getId());
        // Any live connection (chat, notifications) they still have is closed now,
        // not on their next click.
        liveSessions.closeUserAfterCommit(target.getId(), "AUTH_ACCOUNT_SUSPENDED");
        log.info("[USER-LIFECYCLE] {} | userId={} | tasksReleased={} | otherReleased={} | delegationsEnded={} | by={} | reason={}",
                to, target.getId(), released, other.total(), delegations, actor, reason);
        return released;
    }

    private void record(User target, UserStatus from, UserStatus to, String reason, String detail) {
        statusChangeRepository.save(AccountStatusChange.builder()
                .subjectType(AccountStatusChange.USER)
                .subjectId(target.getId())
                .tenantId(target.getTenantId())
                .fromStatus(from == null ? null : from.name())
                .toStatus(to.name())
                .reason(reason == null || reason.isBlank() ? null : reason.trim())
                .actorUserId(callerId())
                .detail(detail)
                .build());
    }

    private static void dropCaches(Long userId) {
        CustomUserDetailsService.invalidate(userId);
        AccountAccessService.invalidateUser(userId);
    }

    private Long callerId() {
        try { return utilityService.getLoggedInDataContext().getId(); } catch (RuntimeException e) { return null; }
    }

    private static void afterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { r.run(); }
            });
        } else {
            r.run();
        }
    }
}
