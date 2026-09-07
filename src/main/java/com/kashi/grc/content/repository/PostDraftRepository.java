package com.kashi.grc.content.repository;

import com.kashi.grc.content.domain.PostDraft;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PostDraftRepository extends JpaRepository<PostDraft, Long> {

    Optional<PostDraft> findByPostId(Long postId);

    /** Which of these posts have unreleased changes — one query for the list view. */
    @Query("select d.postId from PostDraft d where d.postId in :postIds")
    List<Long> findPostIdsWithDrafts(@Param("postIds") List<Long> postIds);

    @Modifying
    @Query("delete from PostDraft d where d.postId = :postId")
    void deleteByPostId(@Param("postId") Long postId);
}