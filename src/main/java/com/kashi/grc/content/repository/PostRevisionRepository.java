package com.kashi.grc.content.repository;

import com.kashi.grc.content.domain.PostRevision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PostRevisionRepository extends JpaRepository<PostRevision, Long> {

    List<PostRevision> findByPostIdOrderByRevisionNumberDesc(Long postId);

    Optional<PostRevision> findByPostIdAndRevisionNumber(Long postId, Integer revisionNumber);

    @Query("select coalesce(max(r.revisionNumber), 0) from PostRevision r where r.postId = :postId")
    Integer maxRevisionNumber(@Param("postId") Long postId);

    long countByPostId(Long postId);

    /** Oldest first — what the cap deletes. */
    List<PostRevision> findByPostIdOrderByRevisionNumberAsc(Long postId);
}
