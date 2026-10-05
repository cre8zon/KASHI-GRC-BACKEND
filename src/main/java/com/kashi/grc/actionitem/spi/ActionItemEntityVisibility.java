package com.kashi.grc.actionitem.spi;

import com.kashi.grc.actionitem.domain.ActionItem;

/**
 * Who may see the action items sitting on one entity.
 *
 * The generic reads (GET /v1/action-items?entityType=&entityId=, /by-entities,
 * /{id}) checked tenant only — the "oversight view". That is right for a
 * TPRM coordinator, but for an entity whose items are ACCESS GRANTS (audit
 * instances: a live item lets its assignee work the control) it showed every
 * delegation on any control to anyone in the tenant, guests included.
 *
 * Same shape as NotificationRouteContributor: the action-item module asks, the
 * module owning the entity type answers. Types nobody claims keep the tenant
 * oversight view unchanged.
 *
 * The caller always sees items they are PARTY to (assignee, creator, or the
 * person the resolution is reserved for), provided they may read the entity.
 * {@link #seesAll} decides whether they also see everyone else's.
 */
public interface ActionItemEntityVisibility {

    boolean supports(ActionItem.EntityType entityType);

    /** Throw (403) when the caller may not read the entity at all. */
    void requireReadable(ActionItem.EntityType entityType, Long entityId);

    /** True when the caller oversees the entity and sees every item on it. */
    boolean seesAll(ActionItem.EntityType entityType, Long entityId, Long userId);
}