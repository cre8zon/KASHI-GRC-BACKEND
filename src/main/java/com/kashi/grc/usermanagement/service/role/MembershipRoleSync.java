package com.kashi.grc.usermanagement.service.role;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stamps user_roles.membership_id after a role assignment.
 *
 * WHY THIS EXISTS
 *   User.roles is a @ManyToMany whose @JoinTable names only user_id and role_id.
 *   Hibernate therefore writes neither membership_id nor anything else on that
 *   table, so every row created by `user.getRoles().add(role)` lands with
 *   membership_id NULL.
 *
 *   That column is what says "this role applies in that tenant". The picker
 *   queries in DbRepository and the user list both join through it, so a NULL
 *   makes the user invisible in every assignment dropdown — the role exists,
 *   the person cannot be assigned work. Silent, and confusing to debug.
 *
 * WHY NOT MAP user_roles AS A WRITABLE ENTITY
 *   UserRole already maps it read-only so queries can see the column. Making it
 *   writable alongside the @ManyToMany means two mappings over one table, which
 *   is a well-known route to double inserts and stale first-level cache. A
 *   targeted UPDATE after the fact is smaller and cannot double-write.
 *
 *   The proper fix is to retire the @ManyToMany in favour of an explicit
 *   association entity. That is a wider change; this closes the gap safely in
 *   the meantime.
 */
@Slf4j
@Component
public class MembershipRoleSync {

    @PersistenceContext
    private EntityManager em;

    /**
     * Ensures a HOME membership exists for this user in this tenant, then stamps
     * their roles onto it. Idempotent — safe to call on every create or update.
     *
     * WHY THIS IS NEEDED
     *   Migration 12 backfilled a membership for every user that existed at the
     *   time, but nothing created one for users added afterwards. Since the
     *   picker queries and the user list now resolve through
     *   user_tenant_memberships, a user without a membership row is invisible
     *   everywhere: not in assignment dropdowns, not in User Management, and
     *   their roles never stamp because stamp() has nothing to attach them to.
     *
     *   Login still works for them — AuthServiceImpl falls back to
     *   users.tenant_id when no membership is found — which is precisely why
     *   this fails quietly rather than loudly.
     */
    @Transactional
    public void ensureHomeMembership(Long userId, Long tenantId) {
        if (userId == null || tenantId == null) return;

        Object existing = em.createNativeQuery("""
                        SELECT id FROM user_tenant_memberships
                         WHERE user_id = :userId AND tenant_id = :tenantId
                        """)
                .setParameter("userId", userId)
                .setParameter("tenantId", tenantId)
                .getResultStream().findFirst().orElse(null);

        if (existing == null) {
            em.createNativeQuery("""
                            INSERT INTO user_tenant_memberships
                                   (user_id, tenant_id, membership_type, status, is_primary,
                                    created_at, updated_at)
                            VALUES (:userId, :tenantId, 'HOME', 'ACTIVE', 1, NOW(), NOW())
                            """)
                    .setParameter("userId", userId)
                    .setParameter("tenantId", tenantId)
                    .executeUpdate();
            log.info("[MEMBERSHIP] Created HOME membership | userId={} tenantId={}", userId, tenantId);
        }
        stamp(userId, tenantId);
    }

    /**
     * Attaches every unstamped role row for this user to their membership in the
     * given tenant. Idempotent, and a no-op when the user has no membership
     * there — which is correct, since a role in a tenant they do not belong to
     * should not resolve.
     *
     * Call AFTER the roles have been flushed, or the rows do not exist yet.
     */
    @Transactional
    public int stamp(Long userId, Long tenantId) {
        if (userId == null || tenantId == null) return 0;

        int updated = em.createNativeQuery("""
                        UPDATE user_roles ur
                        JOIN   user_tenant_memberships m
                               ON m.user_id = ur.user_id AND m.tenant_id = :tenantId
                        SET    ur.membership_id = m.id
                        WHERE  ur.user_id = :userId
                          AND  ur.membership_id IS NULL
                        """)
                .setParameter("userId", userId)
                .setParameter("tenantId", tenantId)
                .executeUpdate();

        if (updated > 0) {
            log.debug("[MEMBERSHIP-ROLES] Stamped {} role row(s) | userId={} tenantId={}",
                    updated, userId, tenantId);
        } else {
            // Worth a line: it means the user holds no membership in this tenant,
            // so their roles will not resolve in any picker.
            log.debug("[MEMBERSHIP-ROLES] Nothing stamped | userId={} tenantId={} — "
                    + "no membership in this tenant, or already stamped", userId, tenantId);
        }
        return updated;
    }
}