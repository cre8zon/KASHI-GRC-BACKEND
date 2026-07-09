package com.kashi.grc.comment.repository;

import com.kashi.grc.comment.domain.EntityComment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Visibility-filtered and revision-thread queries live in the Custom fragment. */
public interface EntityCommentRepository
        extends JpaRepository<EntityComment, Long>, EntityCommentRepositoryCustom {

    List<EntityComment> findByEntityTypeAndEntityIdOrderByCreatedAtAsc(
            EntityComment.EntityType entityType, Long entityId);

    List<EntityComment> findByQuestionInstanceIdOrderByCreatedAtAsc(Long questionInstanceId);
}
