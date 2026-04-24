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

    public static Specification<ActionItem> assignedTo(Long userId) {
        return (root, query, cb) -> cb.equal(root.get("assignedTo"), userId);
    }

    public static Specification<ActionItem> assignedToUserOrRole(Long userId, List<String> roles) {
        return (root, query, cb) -> {
            var byUser = cb.equal(root.get("assignedTo"), userId);
            if (roles == null || roles.isEmpty()) return byUser;
            var byRole = root.get("assignedGroupRole").in(roles);
            return cb.or(byUser, byRole);
        };
    }

    public static Specification<ActionItem> withStatus(Set<ActionItem.Status> statuses) {
        return (root, query, cb) -> root.get("status").in(statuses);
    }

    public static Specification<ActionItem> withStatus(ActionItem.Status status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    /** Filter by entity type only — no entity id constraint */
    public static Specification<ActionItem> withEntityType(ActionItem.EntityType type) {
        return (root, query, cb) -> cb.equal(root.get("entityType"), type);
    }

    public static Specification<ActionItem> forEntity(ActionItem.EntityType type, Long entityId) {
        return (root, query, cb) -> cb.and(
                cb.equal(root.get("entityType"), type),
                cb.equal(root.get("entityId"), entityId)
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
        // PENDING_REVIEW is "open" — work submitted but not yet accepted by reviewer
        return withStatus(Set.of(
                ActionItem.Status.OPEN,
                ActionItem.Status.IN_PROGRESS,
                ActionItem.Status.PENDING_REVIEW
        ));
    }
}