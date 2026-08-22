package com.kashi.grc.common.config.multitenancy;

import java.util.Set;

/**
 * What the current request's identity may reach WITHIN its tenant.
 *
 * ── WHY THIS EXISTS ─────────────────────────────────────────────────────────
 * TenantContext answers "which tenant". That was a sufficient boundary while
 * one identity meant one tenant. It stopped being sufficient the moment an
 * outsider — an auditor employed by an external firm — could hold a membership
 * in a client's tenant: tenant scope alone let them see every engagement in
 * that client, including internal audits and rival firms' work, plus the
 * client's user directory.
 *
 * This holds the second half of the answer: within that tenant, WHICH ROWS.
 *
 * ── WHY IT IS A PLAIN THREADLOCAL AND NOT A BEAN ────────────────────────────
 * It is read by DbRepository, which is itself the dependency of nearly every
 * service in the project. Making the scope a Spring bean would put a new edge
 * into that graph and risk a cycle on a class that cannot afford one. It is
 * pure data with no behaviour, so a static holder costs nothing and cannot
 * create a cycle. TenantContext next door works the same way for the same
 * reason.
 *
 * ── WHY HOME MEMBERS GET null SETS, NOT "EVERYTHING" SETS ───────────────────
 * A HOME member is unrestricted — that is precisely what tenancy already
 * meant. Null lets every consumer return before building any predicate, so the
 * SQL produced for the client's own staff is byte-for-byte what it was before
 * this existed. Materialising an "all ids" set instead would put an IN clause
 * containing every engagement into every query in the platform.
 *
 * An EMPTY set is a different thing entirely and must never be confused with
 * null: it is a guest who has been admitted to the tenant but not yet staffed
 * on anything, and they must see nothing rather than everything.
 *
 * ── LIFECYCLE ───────────────────────────────────────────────────────────────
 * Set by UtilityService.getLoggedInDataContext(), cleared by
 * UtilityService.clearRequestCache(), which RequestUserCacheFilter calls in a
 * finally block after every request. Tomcat reuses threads, so a scope that
 * outlived its request would hand the next caller on that thread another
 * identity's row filters — the same hazard the user cache next to it exists to
 * avoid.
 *
 * On a non-HTTP thread (Kafka consumers, scheduled jobs) nothing sets it, so
 * isGuest() is false and no filtering occurs. That is the correct default:
 * background work acts as the system, not as a guest.
 */
public final class AccessScope {

    /**
     * The resolved scope for one request. A record because it is written once
     * at the start of the request and read many times after; nothing
     * downstream may mutate it.
     *
     * @param userId        the acting identity
     * @param tenantId      the tenant being acted in
     * @param guest         true only when this identity is inside someone else's tenant
     * @param firmTenantId  the auditor's own firm, null for HOME members
     * @param engagementIds engagements this guest may see; null = unrestricted
     * @param userIds       users this guest may see; null = unrestricted
     * @param frameworkRefs frameworks those engagements are against; carried so
     *                      navigation can later be narrowed to what the auditor
     *                      was actually hired for, and not currently consumed
     * @param issueIds      issues this guest may see; null = unrestricted. Issues
     *                      carry no engagement of their own, so this is derived
     *                      from the findings that were escalated into them
     * @param vendorId      set when the caller is a VENDOR-side user. A vendor's
     *                      staff are members of the CLIENT's tenant, so tenancy
     *                      alone puts them inside the client's data — including
     *                      its audit programme, which is none of their business.
     *                      Null for everyone else.
     */
    public record Scope(Long userId,
                        Long tenantId,
                        boolean guest,
                        Long firmTenantId,
                        Set<Long> engagementIds,
                        Set<Long> userIds,
                        Set<String> frameworkRefs,
                        Set<Long> issueIds,
                        Long vendorId) {

        /** Unrestricted — every HOME member, i.e. very nearly every request. */
        public static Scope home(Long userId, Long tenantId) {
            return new Scope(userId, tenantId, false, null, null, null, null, null, null);
        }

        /**
         * A vendor's own user, inside the client's tenant.
         *
         * Not a "guest" — guest means an external auditor with engagement scope,
         * and conflating the two would give a vendor an engagement id set, which
         * is exactly the data they must not have.
         */
        public static Scope vendor(Long userId, Long tenantId, Long vendorId) {
            return new Scope(userId, tenantId, false, null, null, null, null, null, vendorId);
        }
    }

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private AccessScope() {
    }

    public static void set(Scope scope) {
        CURRENT.set(scope);
    }

    /** Null on unauthenticated requests, which never reach a scoped query. */
    public static Scope get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** True only for an external auditor acting inside a client tenant. */
    public static boolean isGuest() {
        Scope s = CURRENT.get();
        return s != null && s.guest();
    }

    /** Engagement ids the request may see, or null for no restriction. */
    public static Set<Long> engagementIds() {
        Scope s = CURRENT.get();
        return s == null ? null : s.engagementIds();
    }

    /** User ids the request may see, or null for no restriction. */
    public static Set<Long> userIds() {
        Scope s = CURRENT.get();
        return s == null ? null : s.userIds();
    }

    /** The audit firm this guest belongs to, or null when not a guest. */
    public static Long firmTenantId() {
        Scope s = CURRENT.get();
        return s == null ? null : s.firmTenantId();
    }

    /** Frameworks this guest's engagements are against, or null. */
    public static Set<String> frameworkRefs() {
        Scope s = CURRENT.get();
        return s == null ? null : s.frameworkRefs();
    }

    /** Issue ids the request may see, or null for no restriction. */
    public static Set<Long> issueIds() {
        Scope s = CURRENT.get();
        return s == null ? null : s.issueIds();
    }

    /** True when the caller acts on behalf of a vendor rather than the org. */
    public static boolean isVendor() {
        Scope s = CURRENT.get();
        return s != null && s.vendorId() != null;
    }

    /** The vendor this caller belongs to, or null when they are not vendor-side. */
    public static Long vendorId() {
        Scope s = CURRENT.get();
        return s == null ? null : s.vendorId();
    }
}