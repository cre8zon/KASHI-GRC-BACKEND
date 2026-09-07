package com.kashi.grc.content.repository;

import com.kashi.grc.content.domain.ContentEnums.PostStatus;
import com.kashi.grc.content.domain.PostTag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PostTagRepository extends JpaRepository<PostTag, Long> {

    List<PostTag> findByPostId(Long postId);

    void deleteByPostId(Long postId);

    @Query("select pt.postId from PostTag pt where pt.tagId = :tagId")
    List<Long> findPostIdsByTagId(@Param("tagId") Long tagId);

    @Query("select pt.tagId from PostTag pt where pt.postId = :postId")
    List<Long> findTagIdsByPostId(@Param("postId") Long postId);

    /** Post counts per tag, for the tag listing and the indexable threshold. */
    @Query("""
           select pt.tagId, count(pt.postId) from PostTag pt
            where pt.postId in (select p.id from Post p where p.status = :status)
            group by pt.tagId
           """)
    List<Object[]> countPublishedPerTag(@Param("status") PostStatus status);
}