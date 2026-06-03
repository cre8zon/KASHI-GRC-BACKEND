package com.kashi.grc.actionitem.repository;

import com.kashi.grc.actionitem.domain.ActionItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ActionItemRepository
        extends JpaRepository<ActionItem, Long>,
        JpaSpecificationExecutor<ActionItem> {

    /** Count open items assigned to a user — for inbox badge */
    @Query("SELECT COUNT(a) FROM ActionItem a WHERE a.assignedTo = :userId " +
            "AND a.status IN ('OPEN','IN_PROGRESS') AND a.tenantId = :tenantId")
    long countOpenForUser(@Param("userId") Long userId,
                          @Param("tenantId") Long tenantId);

    /** Check if an open action item exists for a specific source (avoids duplicates) */
    @Query("SELECT COUNT(a) > 0 FROM ActionItem a WHERE a.sourceType = :sourceType " +
            "AND a.sourceId = :sourceId AND a.status IN ('OPEN','IN_PROGRESS')")
    boolean existsOpenForSource(@Param("sourceType") ActionItem.SourceType sourceType,
                                @Param("sourceId") Long sourceId);

    /**
     * NEW — Check if an open action item exists for a specific entity (itemRefType + itemRefId).
     * Used by TaskSectionCompletionService.getProgress() to populate hasOpenActionItem
     * on each SectionItemResponse — drives the action item badge on item cards.
     *
     * @param entityTypeStr  String value of EntityType enum — e.g. "CONTROL", "FINDING"
     * @param entityId       The item's ref ID — e.g. controlId, findingId
     */
    @Query("SELECT COUNT(a) > 0 FROM ActionItem a " +
            "WHERE CAST(a.entityType AS string) = :entityTypeStr " +
            "AND a.entityId = :entityId " +
            "AND a.status IN ('OPEN', 'IN_PROGRESS', 'PENDING_REVIEW', 'PENDING_VALIDATION')")
    boolean existsOpenForEntity(@Param("entityTypeStr") String entityTypeStr,
                                @Param("entityId") Long entityId);
}