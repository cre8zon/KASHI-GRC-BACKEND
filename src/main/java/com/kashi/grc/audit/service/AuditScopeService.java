package com.kashi.grc.audit.service;

import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.usermanagement.repository.UserTenantMembershipRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What an EXTERNAL auditor may see inside a client's tenant.
 *
 * THE PROBLEM THIS SOLVES
 *   A GUEST membership grants a role in the client's tenant, and every query in
 *   the platform scopes by tenant alone. So an auditor from one firm, staffed on
 *   one engagement, could see every engagement in that client — including
 *   internal audits and work by other firms — plus the client's users, settings
 *   and navigation, exactly as if they worked there.
 *
 *   Tenant scoping was the right boundary when one identity meant one tenant. It
 *   stopped being sufficient the moment an outsider could hold a membership.
 *
 * THE RULE
 *   A guest sees an engagement only if they are connected to it: named as its
 *   lead auditor, assigned to one of its sections, or assigned to one of its
 *   controls. HOME members are unaffected — the client's own staff keep
 *   tenant-wide visibility, which is what tenancy already meant.
 *
 * WHY DERIVED, NOT STORED
 *   A membership→engagement link table would need maintaining on every
 *   assignment and every revocation, and would drift the first time one was
 *   missed. Deriving it from the assignments that already exist means access
 *   follows the work: assign an auditor to a section and they can see that
 *   engagement; unassign them and they cannot.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditScopeService {

    private final UserTenantMembershipRepository membershipRepository;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager em;

    /** True when this user is in the tenant as an external auditor. */
    @Transactional(readOnly = true)
    public boolean isGuest(Long userId, Long tenantId) {
        return membershipRepository.findByUserIdAndTenantId(userId, tenantId)
                .map(m -> "GUEST".equalsIgnoreCase(m.getMembershipType()))
                .orElse(false);
    }

    /**
     * Engagement ids this user may see, or null for "no restriction".
     *
     * Null rather than "all ids" deliberately: callers can skip filtering
     * entirely for the common case, and a HOME member's query is unchanged —
     * no new joins, no behaviour change, no performance cost for the 99%.
     */
    @Transactional(readOnly = true)
    public Set<Long> visibleEngagementIds(Long userId, Long tenantId) {
        if (!isGuest(userId, tenantId)) return null;

        // One query rather than three table loads filtered in memory. This runs
        // on every list request for a guest, so it has to be cheap.
        @SuppressWarnings("unchecked")
        List<Number> rows = em.createNativeQuery("""
                        SELECT e.id FROM audit_engagements e
                         WHERE e.tenant_id = :tenantId AND e.lead_auditor_id = :userId
                        UNION
                        SELECT s.engagement_id FROM audit_section_instances s
                         WHERE s.tenant_id = :tenantId AND s.assigned_auditor_id = :userId
                        UNION
                        SELECT c.engagement_id FROM audit_control_instances c
                         WHERE c.tenant_id = :tenantId AND c.assigned_auditor_id = :userId
                        """)
                .setParameter("tenantId", tenantId)
                .setParameter("userId", userId)
                .getResultList();

        Set<Long> ids = new LinkedHashSet<>();
        rows.stream().filter(java.util.Objects::nonNull).forEach(n -> ids.add(n.longValue()));

        log.debug("[AUDIT-SCOPE] Guest userId={} tenantId={} → {} engagement(s)",
                userId, tenantId, ids.size());
        return ids;
    }

    /**
     * The full row scope for one request, or the unrestricted scope for a HOME
     * member. Called once per request by UtilityService, which is the only
     * place that knows whether this identity is a guest.
     *
     * WHY IT RESOLVES EVERYTHING UP FRONT
     *   The alternative — resolving each set lazily on first use — means a query
     *   firing in the middle of an unrelated transaction, from inside
     *   DbRepository, on a thread that may already hold a pessimistic lock. Two
     *   extra cheap SELECTs on the guest path only is the safer trade; HOME
     *   members return before any of it.
     */
    @Transactional(readOnly = true)
    public com.kashi.grc.common.config.multitenancy.AccessScope.Scope resolveScope(
            Long userId, Long tenantId, Long vendorId) {

        // Vendor first, and it short-circuits. A vendor's staff are users of the
        // CLIENT's tenant, so every tenant-scoped query already treats them as
        // insiders — that is how a vendor with a URL could read the client's audit
        // engagements, controls and findings. They are never also a guest, and
        // giving them an engagement id set would hand over the very thing being
        // withheld, so this returns before any engagement resolution runs.
        if (vendorId != null) {
            return com.kashi.grc.common.config.multitenancy.AccessScope.Scope
                    .vendor(userId, tenantId, vendorId);
        }

        var membership = membershipRepository.findByUserIdAndTenantId(userId, tenantId).orElse(null);

        boolean guest = membership != null
                && "GUEST".equalsIgnoreCase(membership.getMembershipType());

        if (!guest) {
            return com.kashi.grc.common.config.multitenancy.AccessScope.Scope.home(userId, tenantId);
        }

        Long firmTenantId = membership.getFirmTenantId();
        Set<Long> engagementIds = visibleEngagementIds(userId, tenantId);

        return new com.kashi.grc.common.config.multitenancy.AccessScope.Scope(
                userId, tenantId, true, firmTenantId,
                engagementIds,
                visibleUserIds(tenantId, firmTenantId),
                frameworkRefsFor(engagementIds),
                visibleIssueIds(tenantId, engagementIds),
                null);   // a guest is never vendor-side
    }

    /**
     * Issues a guest may see: only those raised from a finding on one of their
     * own engagements.
     *
     * WHY IT NEEDS ITS OWN QUERY
     *   Issue carries no engagement_id — it records source_entity_type /
     *   source_entity_id, and audit_findings carries the reverse pointer in
     *   linked_issue_id. So the metamodel rule in DbRepository cannot see it and
     *   issues would otherwise stay tenant-wide, which for a guest means every
     *   issue the client has ever raised, including from other firms' audits and
     *   from vendor assessments they have nothing to do with.
     *
     * WHY BOTH DIRECTIONS ARE UNIONED
     *   escalate-to-issue writes both links — the issue's source_entity_id and
     *   the finding's linked_issue_id. They should agree, and if one was ever
     *   written without the other the safe reading of "linked" is either, since
     *   the alternative is hiding an auditor's own issue from them.
     */
    @Transactional(readOnly = true)
    public Set<Long> visibleIssueIds(Long tenantId, Set<Long> engagementIds) {
        if (engagementIds == null || engagementIds.isEmpty()) return Set.of();

        @SuppressWarnings("unchecked")
        List<Number> rows = em.createNativeQuery("""
                        SELECT f.linked_issue_id FROM audit_findings f
                         WHERE f.tenant_id = :tenantId
                           AND f.engagement_id IN (:ids)
                           AND f.linked_issue_id IS NOT NULL
                        UNION
                        SELECT i.id FROM issues i
                          JOIN audit_findings f2 ON f2.id = i.source_entity_id
                         WHERE i.tenant_id = :tenantId
                           AND i.source_entity_type = 'AUDIT_FINDING'
                           AND f2.engagement_id IN (:ids)
                        """)
                .setParameter("tenantId", tenantId)
                .setParameter("ids", engagementIds)
                .getResultList();

        Set<Long> ids = new LinkedHashSet<>();
        rows.stream().filter(java.util.Objects::nonNull).forEach(n -> ids.add(n.longValue()));
        return ids;
    }

    /**
     * Throws unless the current request may see this engagement.
     *
     * Reads the scope already resolved for this request rather than re-running
     * the derivation. The three-argument overload below queries every time it is
     * called, which is fine once per list request and wrong on a detail endpoint
     * that guards each of nine handlers.
     */
    public void requireEngagementVisible(Long engagementId) {
        // Vendors are refused outright — the by-id endpoints do not go through
        // DbRepository, so the row filter there never sees them.
        if (com.kashi.grc.common.config.multitenancy.AccessScope.isVendor()) {
            log.warn("[AUDIT-SCOPE] Vendor user denied engagementId={}", engagementId);
            throw notAccessible("engagement",
                    "Vendor accounts do not have access to audit engagements");
        }
        Set<Long> visible = com.kashi.grc.common.config.multitenancy.AccessScope.engagementIds();
        if (visible == null) return;                       // not a guest
        if (engagementId != null && visible.contains(engagementId)) return;

        log.warn("[AUDIT-SCOPE] Guest denied engagementId={}", engagementId);
        throw notAccessible("engagement",
                "You do not have access to this engagement. External auditors see only the "
                        + "engagements they are staffed on.");
    }

    /**
     * Throws unless the current request may see this issue.
     *
     * A guest sees an issue only when it came from a finding on one of their
     * engagements — an issue raised from a vendor assessment, or from a rival
     * firm's audit, is not theirs to read even though it sits in the same tenant.
     */
    public void requireIssueVisible(Long issueId) {
        if (com.kashi.grc.common.config.multitenancy.AccessScope.isVendor()) {
            log.warn("[AUDIT-SCOPE] Vendor user denied issueId={}", issueId);
            throw notAccessible("issue",
                    "Vendor accounts do not have access to audit issues");
        }
        Set<Long> visible = com.kashi.grc.common.config.multitenancy.AccessScope.issueIds();
        if (visible == null) return;                       // not a guest
        if (issueId != null && visible.contains(issueId)) return;

        log.warn("[AUDIT-SCOPE] Guest denied issueId={}", issueId);
        throw notAccessible("issue",
                "You do not have access to this issue. External auditors see only issues "
                        + "raised from findings on their own engagements.");
    }

    /**
     * One message, one code, whether the row exists or not.
     *
     * A distinguishable "not found" would let a guest walk the id space and
     * learn how many engagements or issues the client has, which is most of what
     * the boundary is there to prevent.
     */
    private BusinessException notAccessible(String what, String message) {
        return new BusinessException(
                "AUDIT".equals(what) ? "NOT_ACCESSIBLE" : (what.toUpperCase() + "_NOT_ACCESSIBLE"),
                message, HttpStatus.FORBIDDEN);
    }

    /**
     * Users a guest may see in this tenant: the client's own people, plus their
     * own firm's colleagues — never a rival firm's auditors working the same
     * client. Two audit firms engaged by one company must not be able to
     * enumerate each other's staff through an assignee picker.
     *
     * The UNION against users.tenant_id is not redundant. Membership rows were
     * backfilled, and a client employee missing one would otherwise vanish from
     * every picker the auditor sees, which reads as a platform bug rather than
     * as a boundary.
     */
    @Transactional(readOnly = true)
    public Set<Long> visibleUserIds(Long tenantId, Long firmTenantId) {
        @SuppressWarnings("unchecked")
        List<Number> rows = em.createNativeQuery("""
                        SELECT u.id FROM users u
                         WHERE u.tenant_id = :tenantId
                        UNION
                        SELECT m.user_id FROM user_tenant_memberships m
                         WHERE m.tenant_id = :tenantId
                           AND (m.membership_type = 'HOME'
                                OR (m.membership_type = 'GUEST'
                                    AND m.firm_tenant_id = :firmTenantId))
                        """)
                .setParameter("tenantId", tenantId)
                .setParameter("firmTenantId", firmTenantId)
                .getResultList();

        Set<Long> ids = new LinkedHashSet<>();
        rows.stream().filter(java.util.Objects::nonNull).forEach(n -> ids.add(n.longValue()));
        return ids;
    }

    /**
     * Frameworks the guest's engagements are against. Carried on the scope so
     * navigation can later be narrowed to what the firm was actually hired for
     * — an auditor engaged for ISO 27001 has no business being shown the
     * client's SOC 2 module. Nothing consumes it yet; narrowing needs the
     * framework-to-feature-key mapping confirmed against feature_flags first,
     * and guessing it wrong empties the auditor's sidebar.
     */
    @Transactional(readOnly = true)
    public Set<String> frameworkRefsFor(Set<Long> engagementIds) {
        if (engagementIds == null || engagementIds.isEmpty()) return Set.of();

        @SuppressWarnings("unchecked")
        List<String> rows = em.createNativeQuery("""
                        SELECT DISTINCT e.framework_ref FROM audit_engagements e
                         WHERE e.id IN (:ids) AND e.framework_ref IS NOT NULL
                        """)
                .setParameter("ids", engagementIds)
                .getResultList();

        return new LinkedHashSet<>(rows);
    }

    /**
     * Throws unless this user may see this engagement.
     *
     * Needed separately from the list filter because a detail page is reachable
     * by URL — filtering the list without guarding the record leaves the data one
     * guessed id away, which is not a boundary at all.
     */
    @Transactional(readOnly = true)
    public void requireEngagementVisible(Long engagementId, Long userId, Long tenantId) {
        Set<Long> visible = visibleEngagementIds(userId, tenantId);
        if (visible == null) return;                       // not a guest
        if (visible.contains(engagementId)) return;

        log.warn("[AUDIT-SCOPE] Guest userId={} denied engagementId={} in tenantId={}",
                userId, engagementId, tenantId);

        // Deliberately the same message whether the engagement exists or not: a
        // distinguishable "not found" would let a guest enumerate the client's
        // engagements by id.
        throw new BusinessException("ENGAGEMENT_NOT_ACCESSIBLE",
                "You do not have access to this engagement. External auditors see only the "
                        + "engagements they are staffed on.",
                HttpStatus.FORBIDDEN);
    }
}