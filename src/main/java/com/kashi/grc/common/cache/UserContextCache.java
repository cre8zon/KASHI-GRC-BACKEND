package com.kashi.grc.common.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.kashi.grc.common.config.multitenancy.AccessScope;
import com.kashi.grc.usermanagement.domain.User;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Cross-request cache of the LOGIN CONTEXT — who the caller is, their roles and
 * permissions, their access scope, and their resolved permission codes.
 *
 * ── WHY ───────────────────────────────────────────────────────────────────────
 *
 * Every authenticated request rebuilt all of it before any controller code ran:
 *
 *   user + roles + permissions (JOIN FETCH)      UtilityService.getLoggedInDataContext
 *   tenant membership (guest or not)             AuditScopeService.resolveScope
 *   role permission grants + user overrides      WorkflowAccessService.resolvePermissions
 *
 * Four database round trips on EVERY request, ~150 ms each against a remote
 * database — most of the time of small endpoints like /notifications (4 queries,
 * 1.2 s). The answers change only when an admin edits roles, permissions or
 * memberships, which is rare.
 *
 * ── HOW IT STAYS CORRECT ──────────────────────────────────────────────────────
 *
 *   1. Short TTL (60 s) — the same window CustomUserDetailsService's auth cache
 *      already accepts for authorities.
 *   2. Write-triggered invalidation: QueryCountInspector passes EVERY statement
 *      Hibernate executes — entity saves, collection join rows, bulk JPQL,
 *      native SQL — to {@link #onSql}. Any INSERT / UPDATE / DELETE on a table
 *      that feeds the login context clears the whole cache after that
 *      transaction commits (and immediately, so this thread never reuses it).
 *      One central hook, so a new admin path cannot forget to evict.
 *   3. A generation counter: a context loaded BEFORE an invalidation is never
 *      stored AFTER it, so a slow request cannot re-cache stale data.
 *   4. Guests (external auditors) do not have their access scope cached — their
 *      engagement set changes whenever they are staffed or delegated work.
 *
 * Not covered: raw JDBC writes outside Hibernate (none to these tables exist
 * today). The TTL bounds them. Per-node cache: on several app instances, other
 * nodes pick a change up within the TTL.
 *
 * The cached User is DETACHED, with roles, permissions and attributes loaded —
 * its only lazy associations — so it is safe to read from any thread. It must
 * not be modified (nothing does: checked); treat it as read-only.
 */
public final class UserContextCache {

    private UserContextCache() {}

    public static final Duration TTL = Duration.ofSeconds(60);

    /** user + scope (scope null = recompute each request, e.g. guests). */
    public record Entry(User user, AccessScope.Scope scope) {}

    private static final Cache<String, Entry> CONTEXTS = Caffeine.newBuilder()
            .expireAfterWrite(TTL).maximumSize(10_000).build();

    private static final Cache<String, List<String>> PERMISSIONS = Caffeine.newBuilder()
            .expireAfterWrite(TTL).maximumSize(10_000).build();

    private static final AtomicLong GENERATION = new AtomicLong();

    /** Tables whose rows feed the login context. */
    private static final Pattern AUTH_WRITE = Pattern.compile(
            "^\\s*(?:insert\\s+(?:ignore\\s+)?into|update|delete\\s+from|replace\\s+into)\\s+`?"
                    + "(users|roles|permissions|role_permissions|user_roles|permission_grants|"
                    + "user_permission_overrides|user_tenant_memberships|user_attributes|"
                    + "firm_access_grants|delegations)`?\\b",
            Pattern.CASE_INSENSITIVE);

    // ── context ───────────────────────────────────────────────────────────────

    public static long generation() {
        return GENERATION.get();
    }

    public static Entry get(Long userId, Long tenantId) {
        return userId == null ? null : CONTEXTS.getIfPresent(key(userId, tenantId));
    }

    /** Stores only if nothing was invalidated since {@code generationAtLoad}. */
    public static void put(Long userId, Long tenantId, Entry entry, long generationAtLoad) {
        if (userId == null || entry == null || entry.user() == null) return;
        if (GENERATION.get() != generationAtLoad) return;
        CONTEXTS.put(key(userId, tenantId), entry);
    }

    // ── resolved permission codes ─────────────────────────────────────────────

    public static List<String> getPermissions(User user) {
        return user == null || user.getId() == null ? null : PERMISSIONS.getIfPresent(permKey(user));
    }

    public static void putPermissions(User user, List<String> permissions, long generationAtLoad) {
        if (user == null || user.getId() == null || permissions == null) return;
        if (GENERATION.get() != generationAtLoad) return;
        PERMISSIONS.put(permKey(user), List.copyOf(permissions));
    }

    // ── invalidation ──────────────────────────────────────────────────────────

    /** Drops everything now — this cache and the auth-authorities cache. */
    public static void invalidateAll() {
        GENERATION.incrementAndGet();
        CONTEXTS.invalidateAll();
        PERMISSIONS.invalidateAll();
        com.kashi.grc.common.config.security.CustomUserDetailsService.invalidateAll();
    }

    /**
     * Drops everything now AND again when the current transaction commits —
     * between the write and the commit another request could still read and
     * re-cache the old rows.
     */
    public static void invalidateAllAfterCommit() {
        invalidateAll();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    invalidateAll();
                }
            });
        }
    }

    /**
     * Called for every SQL statement Hibernate prepares (QueryCountInspector).
     * Cheap: reads are rejected on the first character.
     */
    public static void onSql(String sql) {
        if (sql == null) return;
        int i = 0, n = sql.length();
        while (i < n && Character.isWhitespace(sql.charAt(i))) i++;
        if (i >= n) return;
        char c = Character.toLowerCase(sql.charAt(i));
        if (c != 'i' && c != 'u' && c != 'd' && c != 'r') return;
        if (AUTH_WRITE.matcher(sql).find()) invalidateAllAfterCommit();
    }

    // ── keys ──────────────────────────────────────────────────────────────────

    private static String key(Long userId, Long tenantId) {
        return userId + "|" + tenantId;
    }

    /** Permissions depend on the roles in force — which differ per tenant membership. */
    private static String permKey(User user) {
        String roles = user.getRoles() == null ? "" : user.getRoles().stream()
                                                      .map(r -> r.getId())
                                                      .filter(Objects::nonNull)
                                                      .sorted()
                                                      .map(String::valueOf)
                                                      .collect(Collectors.joining(","));
        return user.getId() + "|" + user.getTenantId() + "|" + roles;
    }
}