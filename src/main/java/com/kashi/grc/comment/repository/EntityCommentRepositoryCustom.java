package com.kashi.grc.comment.repository;

import com.kashi.grc.comment.domain.EntityComment;
import java.util.List;

/** Criteria API fragment for EntityCommentRepository. */
public interface EntityCommentRepositoryCustom {

    /** Comments on an entity filtered by visibility levels, oldest first. */
    List<EntityComment> findVisible(EntityComment.EntityType type, Long entityId,
                                    List<EntityComment.Visibility> visibilities);

    /**
     * REVISION_REQUEST comments on a question instance with no RESOLVED child
     * (NOT EXISTS self-subquery on parentCommentId).
     */
    long countOpenRevisionRequests(Long questionInstanceId);
}
