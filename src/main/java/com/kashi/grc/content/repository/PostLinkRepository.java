package com.kashi.grc.content.repository;

import com.kashi.grc.content.domain.ContentEnums.LinkHealth;
import com.kashi.grc.content.domain.ContentEnums.LinkKind;
import com.kashi.grc.content.domain.PostLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PostLinkRepository extends JpaRepository<PostLink, Long> {

    void deleteByFromPostId(Long fromPostId);

    List<PostLink> findByFromPostId(Long fromPostId);

    List<PostLink> findByToPostId(Long toPostId);

    List<PostLink> findByHealth(LinkHealth health);

    /**
     * Inbound counts for every post at once. The orphan report needs the whole
     * table, and asking per-post would be one query per published article.
     */
    @Query("select l.toPostId, count(l.id) from PostLink l where l.toPostId is not null group by l.toPostId")
    List<Object[]> countInboundByPost();

    @Query("select l from PostLink l where l.linkKind <> :resolvedInternal")
    List<PostLink> findCheckable(@Param("resolvedInternal") LinkKind resolvedInternal);
}