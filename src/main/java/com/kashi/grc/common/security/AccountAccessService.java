package com.kashi.grc.common.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.kashi.grc.tenant.domain.Tenant;
import com.kashi.grc.tenant.repository.TenantRepository;
import com.kashi.grc.usermanagement.domain.User;
import com.kashi.grc.usermanagement.domain.UserStatus;
import com.kashi.grc.usermanagement.repository.UserRepository;
import com.kashi.grc.vendor.domain.Vendor;
import com.kashi.grc.vendor.repository.VendorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * May this person use the product right now — and how much of it?
 *
 * ── WHY THIS EXISTS ───────────────────────────────────────────────────────────
 * Suspension used to be a label. A suspended user could not sign in again, but
 * a session already open kept working until its token expired; a suspended
 * tenant or vendor blocked nothing at all. The only status checks lived in the
 * password check at sign-in.
 *
 * This is the one place that decides, from three facts:
 *   the user     ACTIVE (or LOCKED — a temporary lockout after failed
 *                sign-ins is not a suspension) and not deleted;
 *   the tenant   the session's tenant: ACTIVE or TRIAL is full access;
 *                SUSPENDED is READ-ONLY for a grace period, then blocked;
 *                CANCELLED is blocked;
 *   the vendor   for a vendor-side user, their vendor must not be SUSPENDED
 *                or TERMINATED.
 *
 * Asked by JwtAuthenticationFilter on every request, by sign-in, token refresh
 * and tenant switch, and by the WebSocket handshake — so a suspension takes
 * effect on the person's next request, not at the end of their session.
 *
 * Platform administrators are exempt (the callers check SIDE_SYSTEM): they are
 * the people who suspend and reactivate, and must be able to reach a suspended
 * organisation to do it.
 *
 * ── CACHING ───────────────────────────────────────────────────────────────────
 * Three lookups per request would be wasteful, so verdicts are cached for 30 s.
 * Every status change in the product calls invalidateUser / invalidateAll, so
 * the cache never delays a suspension made through the app; only a change
 * written straight into the database waits up to 30 seconds.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountAccessService {

    public static final String ACCOUNT_SUSPENDED = "AUTH_ACCOUNT_SUSPENDED";
    public static final String TENANT_SUSPENDED  = "AUTH_TENANT_SUSPENDED";
    public static final String VENDOR_SUSPENDED  = "AUTH_VENDOR_SUSPENDED";
    public static final String ACCOUNT_GONE      = "AUTH_TOKEN_INVALID";
    /** A write refused while the organisation is read-only (403, not a sign-out). */
    public static final String TENANT_READ_ONLY  = "TENANT_READ_ONLY";

    /** Tenant statuses that mean full access. Anything not listed below is also allowed. */
    private static final Set<String> TENANT_BLOCKED = Set.of("CANCELLED");
    private static final Set<String> VENDOR_BLOCKED = Set.of("SUSPENDED", "TERMINATED");

    public enum Access { FULL, READ_ONLY, BLOCKED }

    /**
     * code and message are set for READ_ONLY and BLOCKED; readOnlyUntil only for
     * READ_ONLY (when the grace period ends and access is blocked).
     */
    public record Verdict(Access access, String code, String message, LocalDateTime readOnlyUntil) {
        public static final Verdict FULL = new Verdict(Access.FULL, null, null, null);
        public boolean blocked()  { return access == Access.BLOCKED; }
        public boolean readOnly() { return access == Access.READ_ONLY; }
        static Verdict blocked(String code, String message) { return new Verdict(Access.BLOCKED, code, message, null); }
    }

    private final UserRepository   userRepository;
    private final TenantRepository tenantRepository;
    private final VendorRepository vendorRepository;

    /** Days a suspended organisation stays readable before access is blocked. */
    @Value("${app.tenant.suspension.read-only-days:14}")
    private int readOnlyDays;

    private static final Cache<String, Verdict> CACHE = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30))
            .maximumSize(20_000)
            .build();

    /**
     * The verdict for this user in this tenant (the tenant the session is for —
     * an audit-firm auditor working in a client's tenant is judged by the
     * client's status). tenantId may be null: the user's home tenant is used.
     */
    @Transactional(readOnly = true)
    public Verdict check(Long userId, Long tenantId) {
        if (userId == null) return Verdict.blocked(ACCOUNT_GONE, "Your session is no longer valid. Please sign in again.");
        String key = userId + ":" + tenantId;
        Verdict cached = CACHE.getIfPresent(key);
        if (cached != null) return cached;
        Verdict v = evaluate(userId, tenantId);
        CACHE.put(key, v);
        return v;
    }

    private Verdict evaluate(Long userId, Long tenantId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || user.isDeleted()) {
            return Verdict.blocked(ACCOUNT_GONE, "Your session is no longer valid. Please sign in again.");
        }
        Verdict own = userVerdict(user);
        if (own.blocked()) return own;

        if (user.getVendorId() != null) {
            Vendor vendor = vendorRepository.findById(user.getVendorId()).orElse(null);
            if (vendor != null && vendor.getStatus() != null
                    && VENDOR_BLOCKED.contains(vendor.getStatus().toUpperCase())) {
                return Verdict.blocked(VENDOR_SUSPENDED,
                        "Your company's access has been suspended by the organisation you work with. "
                                + "Contact them to restore it.");
            }
        }

        Long tid = tenantId != null ? tenantId : user.getTenantId();
        Tenant tenant = tid == null ? null : tenantRepository.findById(tid).orElse(null);
        return tenant == null ? Verdict.FULL : tenantVerdict(tenant);
    }

    /** The user's own status — also used by sign-in, before a session exists. */
    public Verdict userVerdict(User user) {
        UserStatus s = user.getStatus();
        if (s == null || s == UserStatus.ACTIVE || s == UserStatus.LOCKED) return Verdict.FULL;
        if (s == UserStatus.DEACTIVATED) {
            return Verdict.blocked(ACCOUNT_SUSPENDED, "This account has been deactivated. Contact your administrator.");
        }
        return Verdict.blocked(ACCOUNT_SUSPENDED, "This account has been suspended. Contact your administrator.");
    }

    /** A tenant's verdict on its own: full, read-only during the grace period, or blocked. */
    public Verdict tenantVerdict(Tenant tenant) {
        String status = tenant.getStatus() == null ? "ACTIVE" : tenant.getStatus().toUpperCase();
        if (TENANT_BLOCKED.contains(status)) {
            return Verdict.blocked(TENANT_SUSPENDED, "Your organisation's account is closed. Contact Kashiguard support.");
        }
        if (!"SUSPENDED".equals(status)) return Verdict.FULL;

        LocalDateTime until = graceEnd(tenant);
        if (until != null && LocalDateTime.now().isBefore(until)) {
            return new Verdict(Access.READ_ONLY, TENANT_READ_ONLY,
                    "Your organisation's account is suspended. You can view and export until "
                            + until.toLocalDate() + "; changes are switched off.",
                    until);
        }
        return Verdict.blocked(TENANT_SUSPENDED,
                "Your organisation's account is suspended. Contact Kashiguard support to restore access.");
    }

    /** When the read-only grace ends for a suspended tenant (null when unknown → blocked). */
    public LocalDateTime graceEnd(Tenant tenant) {
        return tenant.getSuspendedAt() == null ? null : tenant.getSuspendedAt().plusDays(readOnlyDays);
    }

    public int readOnlyDays() { return readOnlyDays; }

    // ── For scheduled jobs ───────────────────────────────────────────────────

    private static final Cache<Long, Boolean> OPERATING = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30))
            .maximumSize(5_000)
            .build();

    /**
     * Is this organisation operating — not suspended (read-only or blocked) and
     * not closed? Scheduled jobs ask before they remind, escalate, expire or
     * import anything for a tenant: its people cannot act on any of it, and a
     * suspended customer should not keep receiving mail from us. Work skipped
     * this way is date-driven, so it catches up on the first run after the
     * organisation is reactivated.
     *
     * Unknown tenant id (null, or no row) → true, so a job never silently stops
     * for data that predates tenancy.
     */
    @Transactional(readOnly = true)
    public boolean tenantOperating(Long tenantId) {
        if (tenantId == null) return true;
        return OPERATING.get(tenantId, id -> tenantRepository.findById(id)
                .map(t -> tenantVerdict(t).access() == Access.FULL)
                .orElse(true));
    }

    /** Is this person able to act — for jobs deciding whom to remind. */
    public boolean userOperating(Long userId, Long tenantId) {
        return userId != null && check(userId, tenantId).access() == Access.FULL;
    }

    // ── Invalidation ─────────────────────────────────────────────────────────

    /** After a user's status changes. */
    public static void invalidateUser(Long userId) {
        if (userId == null) return;
        String prefix = userId + ":";
        CACHE.asMap().keySet().removeIf(k -> k.startsWith(prefix));
    }

    /** After a tenant's or a vendor's status changes — affects many users at once. */
    public static void invalidateAll() {
        CACHE.invalidateAll();
        OPERATING.invalidateAll();
    }
}
