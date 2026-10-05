package com.kashi.grc.usermanagement.service.role;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * WHO HOLDS A PERMISSION in a tenant — the question a people picker asks.
 *
 * "Gate on permissions, never on role names or sides" applied to choosing
 * people: an auditor picker lists whoever may record test results, an evidence
 * owner picker whoever may submit evidence, wherever their roles came from.
 *
 * ── SAME RULE AS THE CALLER-SIDE CHECK ────────────────────────────────────────
 *
 * WorkflowAccessService.computePermissions decides what the LOGGED-IN user may
 * do, from three layers. This answers the same question for many users at once,
 * from the same layers and the same tables:
 *
 *   1. role_permissions of the roles the user holds IN THIS TENANT — read
 *      through user_tenant_memberships (an ACTIVE, unexpired membership) and
 *      user_roles.membership_id, exactly as DbRepository's MEMBERSHIP_USER_SQL
 *      and UtilityService.applyActiveMembership do. An invited auditor's roles
 *      at their own firm do not count here.
 *   2. permission_grants on those roles: granted adds, denied removes.
 *      permission_code, falling back to permissions.code — as
 *      PermissionGrantRepositoryImpl.findGrantsForUserRoles reads it.
 *   3. user_permission_overrides: active and unexpired, by permission_code —
 *      as UserPermissionOverrideRepositoryImpl.findActiveByUserId and the
 *      loop over it read them. Granted adds, denied removes, and it wins over
 *      layers 1–2.
 *
 * Where the caller-side loop applies rows in database order (a grant and a
 * deny for the same code on two of one user's roles), this takes DENY — the
 * order there is unspecified, and a picker must not offer someone the server
 * may refuse.
 *
 * Not included: permissions a workflow STEP adds for the length of a task
 * (step UI overrides). Those belong to whoever holds the task, not to a pool of
 * people to choose from.
 *
 * Four indexed queries per call, independent of how many users there are.
 */
@Slf4j
@Service
public class PermissionHolderService {

    @PersistenceContext
    private EntityManager em;

    /** One member of the tenant who holds the permission. */
    public record Holder(Long userId, String membershipType, Long firmTenantId, List<Long> roleIds) {}

    /** User ids in {@code tenantId} who hold {@code permissionCode}. */
    @Transactional(readOnly = true)
    public Set<Long> holderIds(Long tenantId, String permissionCode) {
        return holders(tenantId, permissionCode).keySet();
    }

    /** True when {@code userId} holds {@code permissionCode} in {@code tenantId}. */
    @Transactional(readOnly = true)
    public boolean holds(Long userId, Long tenantId, String permissionCode) {
        if (userId == null) return false;
        return holders(tenantId, permissionCode, List.of(userId)).containsKey(userId);
    }

    /** Holders with their membership (HOME / GUEST, firm) and their roles in the tenant. */
    @Transactional(readOnly = true)
    public Map<Long, Holder> holders(Long tenantId, String permissionCode) {
        return holders(tenantId, permissionCode, null);
    }

    @SuppressWarnings("unchecked")
    private Map<Long, Holder> holders(Long tenantId, String permissionCode, Collection<Long> onlyUserIds) {
        Map<Long, Holder> result = new LinkedHashMap<>();
        if (tenantId == null || permissionCode == null || permissionCode.isBlank()) return result;
        if (onlyUserIds != null && onlyUserIds.isEmpty()) return result;
        String code = permissionCode.trim();

        // ── Members of the tenant and their roles here ───────────────────────
        String memberSql = """
                SELECT m.user_id, m.membership_type, m.firm_tenant_id, ur.role_id
                FROM   user_tenant_memberships m
                JOIN   users u       ON u.id = m.user_id
                JOIN   user_roles ur ON ur.membership_id = m.id
                WHERE  m.tenant_id = :tenantId
                  AND  m.status = 'ACTIVE'
                  AND  (m.access_expires_at IS NULL OR m.access_expires_at > NOW())
                  AND  u.is_deleted = 0
                """ + (onlyUserIds != null ? " AND m.user_id IN (:userIds)" : "");
        var memberQuery = em.createNativeQuery(memberSql).setParameter("tenantId", tenantId);
        if (onlyUserIds != null) memberQuery.setParameter("userIds", onlyUserIds);

        Map<Long, Set<Long>> rolesByUser = new LinkedHashMap<>();
        Map<Long, Object[]> membershipByUser = new HashMap<>();
        for (Object[] row : (List<Object[]>) memberQuery.getResultList()) {
            Long userId = toLong(row[0]);
            if (userId == null) continue;
            membershipByUser.putIfAbsent(userId, row);
            Long roleId = toLong(row[3]);
            rolesByUser.computeIfAbsent(userId, k -> new HashSet<>());
            if (roleId != null) rolesByUser.get(userId).add(roleId);
        }

        // ── Layer 1: roles that carry the permission ─────────────────────────
        Set<Long> grantingRoles = new HashSet<>();
        for (Object r : (List<Object>) em.createNativeQuery("""
                SELECT rp.role_id
                FROM   role_permissions rp
                JOIN   permissions p ON p.id = rp.permission_id
                WHERE  p.code = :code
                """).setParameter("code", code).getResultList()) {
            Long id = toLong(r);
            if (id != null) grantingRoles.add(id);
        }

        // ── Layer 2: per-role grants and denies ──────────────────────────────
        Set<Long> denyingRoles = new HashSet<>();
        for (Object[] row : (List<Object[]>) em.createNativeQuery("""
                SELECT g.role_id, g.granted
                FROM   permission_grants g
                LEFT JOIN permissions p ON p.id = g.permission_id
                WHERE  COALESCE(g.permission_code, p.code) = :code
                """).setParameter("code", code).getResultList()) {
            Long roleId = toLong(row[0]);
            if (roleId == null) continue;
            if (toBool(row[1])) grantingRoles.add(roleId);
            else denyingRoles.add(roleId);
        }

        // ── Layer 3: per-user overrides ──────────────────────────────────────
        Set<Long> overrideGrant = new HashSet<>();
        Set<Long> overrideDeny  = new HashSet<>();
        for (Object[] row : (List<Object[]>) em.createNativeQuery("""
                SELECT o.user_id, o.granted
                FROM   user_permission_overrides o
                WHERE  o.permission_code = :code
                  AND  o.is_active = 1
                  AND  (o.expires_at IS NULL OR o.expires_at > NOW())
                """).setParameter("code", code).getResultList()) {
            Long userId = toLong(row[0]);
            if (userId == null) continue;
            if (toBool(row[1])) overrideGrant.add(userId);
            else overrideDeny.add(userId);
        }

        for (Map.Entry<Long, Set<Long>> e : rolesByUser.entrySet()) {
            Long userId = e.getKey();
            Set<Long> roles = e.getValue();
            boolean fromRoles = roles.stream().anyMatch(grantingRoles::contains)
                    && roles.stream().noneMatch(denyingRoles::contains);
            boolean holds = overrideDeny.contains(userId) ? false
                    : overrideGrant.contains(userId) || fromRoles;
            if (!holds) continue;
            Object[] m = membershipByUser.get(userId);
            result.put(userId, new Holder(userId,
                    m != null && m[1] != null ? m[1].toString() : null,
                    m != null ? toLong(m[2]) : null,
                    List.copyOf(roles)));
        }

        // Members with no role rows never reach rolesByUser, but an override
        // alone can still give them the permission.
        if (!overrideGrant.isEmpty()) {
            Set<Long> missing = new HashSet<>(overrideGrant);
            missing.removeAll(result.keySet());
            missing.removeAll(overrideDeny);
            if (onlyUserIds != null) missing.retainAll(onlyUserIds);
            if (!missing.isEmpty()) {
                for (Object[] row : (List<Object[]>) em.createNativeQuery("""
                        SELECT m.user_id, m.membership_type, m.firm_tenant_id
                        FROM   user_tenant_memberships m
                        JOIN   users u ON u.id = m.user_id
                        WHERE  m.tenant_id = :tenantId
                          AND  m.user_id IN (:userIds)
                          AND  m.status = 'ACTIVE'
                          AND  (m.access_expires_at IS NULL OR m.access_expires_at > NOW())
                          AND  u.is_deleted = 0
                        """).setParameter("tenantId", tenantId).setParameter("userIds", missing)
                        .getResultList()) {
                    Long userId = toLong(row[0]);
                    if (userId == null || result.containsKey(userId)) continue;
                    result.put(userId, new Holder(userId,
                            row[1] != null ? row[1].toString() : null, toLong(row[2]), List.of()));
                }
            }
        }

        log.debug("[PERM-HOLDERS] tenantId={} code={} holders={}", tenantId, code, result.size());
        return result;
    }

    private static Long toLong(Object o) {
        return o instanceof Number n ? n.longValue() : null;
    }

    private static boolean toBool(Object o) {
        if (o instanceof Boolean b) return b;
        if (o instanceof Number n) return n.intValue() != 0;
        if (o instanceof byte[] bytes) return bytes.length > 0 && bytes[0] != 0;
        return o != null && "true".equalsIgnoreCase(o.toString());
    }
}