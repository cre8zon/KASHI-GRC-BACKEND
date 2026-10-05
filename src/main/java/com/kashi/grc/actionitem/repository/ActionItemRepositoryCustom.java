package com.kashi.grc.actionitem.repository;

import com.kashi.grc.actionitem.domain.ActionItem;

/** Criteria API fragment for ActionItemRepository. */
public interface ActionItemRepositoryCustom {

    /** Open (OPEN/IN_PROGRESS) items assigned to a user in a tenant. */
    long countOpenForUser(Long userId, Long tenantId);

    /** Whether an open item already exists for a source (dedup guard). */
    boolean existsOpenForSource(ActionItem.SourceType sourceType, Long sourceId);

    /** Whether a live item exists for an entity (entityType passed as string). */
    boolean existsOpenForEntity(String entityTypeStr, Long entityId);

    /** Whether the user has any QUESTION_RESPONSE item under an assessment. */
    boolean existsByAssignedToAndAssessmentId(Long userId, Long assessmentId);

    /**
     * Ids, out of {@code entityIds}, on which {@code userId} holds a LIVE item
     * (OPEN, IN_PROGRESS, PENDING_REVIEW, PENDING_VALIDATION) whose
     * remediation_type is one of {@code remediationTypes}.
     *
     * One query for a whole list, so an access check over fifty controls is not
     * fifty queries. Tenant-scoped: an item in another tenant never counts.
     * An empty input returns an empty set without touching the database.
     */
    java.util.Set<Long> findEntityIdsWithLiveItemForAssignee(String entityTypeStr,
                                                            java.util.Collection<Long> entityIds,
                                                            Long userId,
                                                            Long tenantId,
                                                            java.util.Collection<String> remediationTypes);

    /**
     * Same as findEntityIdsWithLiveItemForAssignee, but for the person who
     * DELEGATED the live item (resolution_reserved_for) — the delegator keeps
     * standing on the work they handed out.
     */
    /** Live items (any assignee) on these instances of one entity type — one query for a list. */
    java.util.List<ActionItem> findLiveForEntities(String entityTypeStr,
                                                   java.util.Collection<Long> entityIds,
                                                   Long tenantId);

    /**
     * Live AND finished items on these instances — everything except DISMISSED
     * (reassigned away / withdrawn). For history: who a control was delegated to,
     * even after the work was submitted.
     */
    java.util.List<ActionItem> findNonDismissedForEntities(String entityTypeStr,
                                                           java.util.Collection<Long> entityIds,
                                                           Long tenantId);

    java.util.Set<Long> findEntityIdsWithLiveItemDelegatedBy(String entityTypeStr,
                                                            java.util.Collection<Long> entityIds,
                                                            Long userId,
                                                            Long tenantId,
                                                            java.util.Collection<String> remediationTypes);
}
