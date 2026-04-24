package com.kashi.grc.comment.repository;

import com.kashi.grc.comment.domain.EntityComment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface EntityCommentRepository extends JpaRepository<EntityComment, Long> {

    /** All comments for an entity, newest last */
    List<EntityComment> findByEntityTypeAndEntityIdOrderByCreatedAtAsc(
            EntityComment.EntityType entityType, Long entityId);

    /** Comments visible to a given visibility level */
    @Query("SELECT c FROM EntityComment c WHERE c.entityType = :type AND c.entityId = :id " +
            "AND c.visibility IN :visibilities ORDER BY c.createdAt ASC")
    List<EntityComment> findVisible(
            @Param("type")         EntityComment.EntityType type,
            @Param("id")           Long entityId,
            @Param("visibilities") List<EntityComment.Visibility> visibilities);

    /** For question cards — all comments on a question instance */
    List<EntityComment> findByQuestionInstanceIdOrderByCreatedAtAsc(Long questionInstanceId);

    /** Count open revision requests on a question */
    @Query("SELECT COUNT(c) FROM EntityComment c WHERE c.questionInstanceId = :qid " +
            "AND c.commentType = 'REVISION_REQUEST' " +
            "AND NOT EXISTS (SELECT 1 FROM EntityComment r WHERE r.parentCommentId = c.id " +
            "                AND r.commentType = 'RESOLVED')")
    long countOpenRevisionRequests(@Param("qid") Long questionInstanceId);
}