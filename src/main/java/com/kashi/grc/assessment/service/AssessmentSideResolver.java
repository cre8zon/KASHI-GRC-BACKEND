package com.kashi.grc.assessment.service;

import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.usermanagement.domain.RoleSide;
import com.kashi.grc.usermanagement.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Which side of the assessment is the caller on.
 *
 * ── WHY THIS IS A CLASS AND NOT A HELPER METHOD ───────────────────────────
 * AssessmentController answers this question inline in at least four places
 * (lines 2025, 3489, 3563, 3567), each time by streaming the user's roles and
 * checking RoleSide. Four copies of one rule is four places for it to drift,
 * and it has already drifted: some copies treat SYSTEM as organisation and
 * some return early on it.
 *
 * Every rule below — who may comment where, who may validate a remediation,
 * whose evidence attaches to whose answer — depends on this being decided
 * once and the same way.
 *
 * ── AND WHY IT NEVER TAKES THE SIDE AS A PARAMETER ────────────────────────
 * The side is derived from the authenticated user's roles, never from anything
 * the caller sends. A `side` field in a request body is a claim, and the whole
 * point of comment visibility is that it cannot be claimed.
 */
@Service
@RequiredArgsConstructor
public class AssessmentSideResolver {

    private final UtilityService utilityService;

    public enum Side { ORGANIZATION, VENDOR, SYSTEM }

    /**
     * The caller's side.
     *
     * SYSTEM is its own answer rather than being folded into ORGANIZATION.
     * A platform operator is not the assessing organisation, and treating them
     * as one is how a support user ends up able to post in an org's private
     * thread.
     */
    public Side current() {
        User u = utilityService.getLoggedInDataContext();
        return of(u);
    }

    public Side of(User u) {
        if (u == null || u.getRoles() == null) return Side.SYSTEM;

        boolean system = u.getRoles().stream().anyMatch(r -> r.getSide() == RoleSide.SYSTEM);
        if (system) return Side.SYSTEM;

        boolean org = u.getRoles().stream().anyMatch(r ->
                r.getSide() == RoleSide.ORGANIZATION
                        || r.getSide() == RoleSide.AUDITOR);
        if (org) return Side.ORGANIZATION;

        boolean vendor = u.getRoles().stream().anyMatch(r ->
                r.getSide() == RoleSide.VENDOR
                        || r.getSide() == RoleSide.AUDITEE);
        if (vendor) return Side.VENDOR;

        // A user with no side-bearing role. Vendor is the safe assumption:
        // it is the narrower view, so a misclassification hides information
        // rather than leaking it.
        return Side.VENDOR;
    }

    public boolean isVendor()       { return current() == Side.VENDOR; }
    public boolean isOrganisation() { return current() == Side.ORGANIZATION; }
    public boolean isSystem()       { return current() == Side.SYSTEM; }
}
