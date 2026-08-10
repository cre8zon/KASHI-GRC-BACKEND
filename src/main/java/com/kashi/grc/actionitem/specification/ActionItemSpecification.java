package com.kashi.grc.actionitem.specification;

import com.kashi.grc.actionitem.domain.ActionItem;
import org.springframework.data.jpa.domain.Specification;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

public class ActionItemSpecification {

    private ActionItemSpecification() {}

    public static Specification<ActionItem> forTenant(Long tenantId) {
        return (root, query, cb) -> cb.equal(root.get("tenantId"), tenantId);
    }

    public static Specification<ActionItem> forVendor(Long vendorId) {
        return (root, query, cb) -> vendorId == null
                ? cb.isNull(root.get("vendorId"))
                : cb.equal(root.get("vendorId"), vendorId);
    }

    public static Specification<ActionItem> assignedTo(Long userId) {
        return (root, query, cb) -> cb.equal(root.get("assignedTo"), userId);
    }

    /**
     * Assigned to this specific user OR to a role — but role match is ONLY applied
     * when a vendorId is provided, scoping it to that vendor's items.
     *
     * Without vendorId scoping, VENDOR_RESPONDER would match all responder items
     * across every vendor in the tenant — a cross-vendor data leak.
     *
     * Logic:
     *   - Always match direct user assignment (assignedTo = userId)
     *   - Match role assignment ONLY when:
     *       a) The item's vendorId matches the user's vendorId, AND
     *       b) The item's assignedGroupRole is in the user's roles
     *   - If userVendorId is null (org-side user), role match has no vendor scope
     *     (org roles like ORG_REVIEWER are tenant-scoped, not vendor-scoped)
     */
    public static Specification<ActionItem> assignedToUserOrRole(
            Long userId, List<String> roles, Long userVendorId) {

        return (root, query, cb) -> {
            // Direct user assignment — always included
            var byUser = cb.equal(root.get("assignedTo"), userId);

            if (roles == null || roles.isEmpty()) return byUser;

            var byRole = root.get("assignedGroupRole").in(roles);

            if (userVendorId != null) {
                // Vendor-side user: scope role match to their specific vendor
                // Prevents VENDOR_RESPONDER from seeing items of other vendors
                var sameVendor = cb.equal(root.get("vendorId"), userVendorId);
                return cb.or(byUser, cb.and(byRole, sameVendor));
            } else {
                // Org-side user: no vendor scope needed (org roles are tenant-wide)
                return cb.or(byUser, byRole);
            }
        };
    }

    /**
     * @deprecated Use assignedToUserOrRole(userId, roles, userVendorId) instead.
     * Kept for backward compatibility with non-vendor contexts.
     */
    @Deprecated
    public static Specification<ActionItem> assignedToUserOrRole(Long userId, List<String> roles) {
        return assignedToUserOrRole(userId, roles, null);
    }

    public static Specification<ActionItem> withStatus(Set<ActionItem.Status> statuses) {
        return (root, query, cb) -> root.get("status").in(statuses);
    }

    public static Specification<ActionItem> withStatus(ActionItem.Status status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<ActionItem> withEntityType(ActionItem.EntityType type) {
        return (root, query, cb) -> cb.equal(root.get("entityType"), type);
    }

    public static Specification<ActionItem> forEntity(ActionItem.EntityType type, Long entityId) {
        return (root, query, cb) -> cb.and(
                cb.equal(root.get("entityType"), type),
                cb.equal(root.get("entityId"), entityId)
        );
    }

    /**
     * Same as forEntity but for many ids at once — backs the bulk endpoint that
     * replaced one request per question on the assessment pages.
     */
    public static Specification<ActionItem> forEntities(ActionItem.EntityType type,
                                                        java.util.Collection<Long> entityIds) {
        return (root, query, cb) -> cb.and(
                cb.equal(root.get("entityType"), type),
                root.get("entityId").in(entityIds)
        );
    }

    public static Specification<ActionItem> forSource(ActionItem.SourceType type, Long sourceId) {
        return (root, query, cb) -> cb.and(
                cb.equal(root.get("sourceType"), type),
                cb.equal(root.get("sourceId"), sourceId)
        );
    }

    public static Specification<ActionItem> resolvableBy(Long userId, List<String> userRoles) {
        return (root, query, cb) -> {
            var reservedForUser = cb.equal(root.get("resolutionReservedFor"), userId);
            var noReservation   = cb.isNull(root.get("resolutionReservedFor"));
            if (userRoles == null || userRoles.isEmpty()) return reservedForUser;
            var hasRole = root.get("resolutionRole").in(userRoles);
            return cb.or(reservedForUser, cb.and(noReservation, hasRole));
        };
    }

    public static Specification<ActionItem> overdue() {
        return (root, query, cb) -> cb.and(
                cb.isNotNull(root.get("dueAt")),
                cb.lessThan(root.get("dueAt"), LocalDateTime.now()),
                root.get("status").in(Set.of(ActionItem.Status.OPEN, ActionItem.Status.IN_PROGRESS))
        );
    }

    public static Specification<ActionItem> withSourceType(ActionItem.SourceType t) {
        return (root, query, cb) -> cb.equal(root.get("sourceType"), t);
    }

    public static Specification<ActionItem> withPriority(ActionItem.Priority p) {
        return (root, query, cb) -> cb.equal(root.get("priority"), p);
    }

    public static Specification<ActionItem> createdBy(Long userId) {
        return (root, query, cb) -> cb.equal(root.get("createdBy"), userId);
    }

    public static Specification<ActionItem> open() {
        return withStatus(Set.of(
                ActionItem.Status.OPEN,
                ActionItem.Status.IN_PROGRESS,
                ActionItem.Status.PENDING_REVIEW
        ));
    }
}