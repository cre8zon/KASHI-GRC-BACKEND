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
}
