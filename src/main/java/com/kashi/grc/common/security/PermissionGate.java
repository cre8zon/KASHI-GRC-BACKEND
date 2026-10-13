package com.kashi.grc.common.security;

import com.kashi.grc.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Objects;

/**
 * One place to ask "may this caller do X", in the terms the product already
 * speaks: permission codes.
 *
 * ── WHY THIS EXISTS ───────────────────────────────────────────────────────────
 *
 * Across 81 controllers, twelve files contain a @PreAuthorize and most of those
 * carry exactly one. Everything else is gated by nothing beyond "a valid JWT
 * was presented". The permissions table has 160-odd rows and the roles that
 * reference them are configured per tenant, so the authorisation model exists
 * in full — it is simply not consulted at the edge.
 *
 * Where a check does exist it was hand-rolled, e.g.
 * VendorTemplateSelectionController.hasOverridePermission(): reach into
 * SecurityContextHolder, stream the authorities, string-compare one code. That
 * is the right IDEA — permission-based, so a renamed or newly added role keeps
 * working with no code change — and the wrong place for it to live, because
 * every copy is a chance to compare the wrong string or forget the null check.
 *
 * ── WHAT AN AUTHORITY LOOKS LIKE HERE ─────────────────────────────────────────
 *
 * CustomUserDetailsService builds three kinds, and this class is written
 * against exactly that:
 *
 *   permission codes, verbatim   "system:write", "USER_CREATE", "vendor.lifecycle"
 *   ROLE_<NAME>                  role name, upper-cased, spaces to underscores
 *   SIDE_<SIDE>                  SIDE_SYSTEM, SIDE_ORGANIZATION, SIDE_VENDOR …
 *
 * No prefix is added to permission codes, so the string passed here is the
 * string in permissions.code. Check it against the table, not against memory:
 * the codes are inconsistent by era — VENDOR_EDIT and vendor.edit are different
 * rows, and audit uses audit:engagement:update.
 *
 * ── WHY NOT @PreAuthorize ─────────────────────────────────────────────────────
 *
 * @PreAuthorize would be the idiomatic answer for a controller method whose
 * rule is a constant. Most of the rules that are missing here are not: they
 * depend on the row being touched, as in "platform admins may create a user in
 * another tenant, everyone else may not", which is a decision made after the
 * request body has been read. A gate that can be called mid-method covers both
 * shapes, and leaves @PreAuthorize available where it genuinely fits.
 *
 * Nothing here replaces the tenant check. A permission says what KIND of thing
 * you may do; it never says WHICH rows you may do it to. Every call site that
 * loads by id still has to scope that load to the caller's tenant — see
 * requireSameTenant.
 */
@Slf4j
@Component
public class PermissionGate {

    /** Platform side. The hard boundary a customer's token can never satisfy. */
    public static final String SIDE_SYSTEM = "SIDE_SYSTEM";

    /** Blanket platform write. Held by platform admins; implies the rest. */
    public static final String SYSTEM_WRITE = "system:write";

    /** Cross-tenant administration, e.g. creating a user inside another org. */
    public static final String MANAGE_TENANTS = "system:manageTenants";

    // ── Reading the caller ───────────────────────────────────────────────────

    private static Authentication auth() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    /** True when the caller holds this exact authority. */
    public boolean has(String authority) {
        Authentication a = auth();
        if (a == null || authority == null) return false;
        return a.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(Objects::nonNull)
                .anyMatch(authority::equals);
    }

    /** True when the caller holds at least one of these. */
    public boolean hasAny(String... authorities) {
        if (authorities == null) return false;
        return Arrays.stream(authorities).anyMatch(this::has);
    }

    /**
     * A platform operator.
     *
     * SIDE_SYSTEM rather than a role name, deliberately: the side comes from
     * Role.side, which is structural, while role NAMES are tenant-configurable
     * and a customer could create one called "Platform Admin" tomorrow.
     *
     * system:write is accepted alongside it so an explicitly granted platform
     * permission works even on a token whose role carries no side.
     */
    public boolean isPlatform() {
        return hasAny(SIDE_SYSTEM, SYSTEM_WRITE);
    }

    // ── Demanding it ─────────────────────────────────────────────────────────

    /**
     * Refuse the request unless the caller holds one of these authorities.
     *
     * Platform operators pass everything. That mirrors what system:write
     * already means everywhere else in the product, and keeps a support path
     * open that does not depend on granting the platform team every code in the
     * table one by one.
     *
     * 403, not 401: the caller IS authenticated. A 401 would send the frontend
     * to the login screen, which is the wrong remedy and looks like a session
     * bug to the person hitting it.
     */
    public void require(String... anyOf) {
        if (isPlatform()) return;
        if (hasAny(anyOf)) return;

        Authentication a = auth();
        log.warn("[PERM] Refused | principal={} | needed one of {} | held={}",
                a == null ? "anonymous" : a.getName(),
                Arrays.toString(anyOf),
                a == null ? "[]" : a.getAuthorities());

        throw new BusinessException(
                "PERMISSION_DENIED",
                "You do not have permission to perform this action.",
                HttpStatus.FORBIDDEN);
    }

    /**
     * Refuse unless the caller is a platform operator.
     *
     * For the handful of operations that are platform-only by nature and have
     * no per-tenant equivalent: editing another organisation's data, rewriting
     * platform-wide reference tables, resetting somebody else's credentials.
     */
    public void requirePlatform() {
        if (isPlatform()) return;

        Authentication a = auth();
        log.warn("[PERM] Refused — platform only | principal={} | held={}",
                a == null ? "anonymous" : a.getName(),
                a == null ? "[]" : a.getAuthorities());

        throw new BusinessException(
                "PLATFORM_ONLY",
                "This action is restricted to platform administrators.",
                HttpStatus.FORBIDDEN);
    }

    /**
     * The other half, and the half that is easy to forget.
     *
     * A permission answers "may you edit users"; it never answers "may you edit
     * THIS user". An endpoint that loads by id alone is cross-tenant no matter
     * how well it is permissioned, because the id space is global.
     *
     * Platform operators are exempt — reaching across tenants is their job.
     *
     * @param rowTenantId tenant_id of the row being touched
     * @param callerTenantId tenant of the caller, from
     *                       UtilityService.getLoggedInDataContext()
     */
    public void requireSameTenant(Long rowTenantId, Long callerTenantId) {
        if (isPlatform()) return;
        if (rowTenantId != null && rowTenantId.equals(callerTenantId)) return;

        log.warn("[PERM] Refused — cross-tenant | callerTenant={} | rowTenant={}",
                callerTenantId, rowTenantId);

        // Deliberately the same message and status as a missing row would give.
        // "Forbidden" on a record in another tenant confirms that the id exists,
        // which is a membership oracle across the whole customer base.
        throw new BusinessException(
                "NOT_FOUND",
                "The requested record does not exist.",
                HttpStatus.NOT_FOUND);
    }
}