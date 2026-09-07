package com.kashi.grc.content.repository;

import com.kashi.grc.content.domain.ContentEnums.ContentType;
import com.kashi.grc.content.domain.ContentEnums.PostStatus;
import com.kashi.grc.content.domain.ContentEnums.RobotsDirective;
import com.kashi.grc.content.domain.Post;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PostRepository extends JpaRepository<Post, Long> {

    /**
     * How many posts reference this media asset, anywhere.
     *
     * Three places, and missing any one of them turns a delete into a broken
     * image somebody else finds months later:
     *
     *   hero_image_id   the article cover
     *   og_image_id     the social card
     *   content_blocks  every image and download block in the body
     *
     * The last one is why this is native SQL. mediaId lives inside a JSON
     * array of blocks, so there is no column to join on. `$**.mediaId` collects
     * every mediaId at any depth and JSON_CONTAINS asks whether ours is among
     * them — JSON_SEARCH would not do, because it only matches strings and
     * these are numbers.
     *
     * COALESCE covers a post whose blocks contain no media at all, where
     * JSON_EXTRACT returns NULL rather than an empty array.
     */
    @Query(value = """
            SELECT COUNT(*) FROM content_posts p
            WHERE p.hero_image_id = :mediaId
               OR p.og_image_id   = :mediaId
               OR JSON_CONTAINS(
                    COALESCE(JSON_EXTRACT(p.content_blocks, '$**.mediaId'), JSON_ARRAY()),
                    CAST(:mediaId AS JSON))
            """, nativeQuery = true)
    long countMediaUsages(@Param("mediaId") Long mediaId);

    Optional<Post> findBySlug(String slug);

    /**
     * The public read. Status is asserted, never excluded — see the note on
     * PostStatus about why a future status must be invisible by default.
     */
    Optional<Post> findBySlugAndStatus(String slug, PostStatus status);

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, Long id);

    List<Post> findByStatusOrderByPublishedAtDesc(PostStatus status);

    Page<Post> findByStatus(PostStatus status, Pageable pageable);

    Page<Post> findByStatusAndCategoryId(PostStatus status, Long categoryId, Pageable pageable);

    Page<Post> findByStatusAndContentType(PostStatus status, ContentType type, Pageable pageable);

    Page<Post> findByStatusAndAuthorId(PostStatus status, Long authorId, Pageable pageable);

    List<Post> findByPillarClusterIdAndStatusOrderByClusterOrderAsc(Long pillarId, PostStatus status);

    /** Due for automatic publication. Indexed on (status, scheduled_for). */
    List<Post> findByStatusAndScheduledForLessThanEqual(PostStatus status, LocalDateTime now);

    @Query("""
           select p from Post p
            where p.status = :status
              and (:categoryId is null or p.categoryId  = :categoryId)
              and (:type       is null or p.contentType = :type)
              and (:authorId   is null or p.authorId    = :authorId)
              and (:q is null or lower(p.title) like lower(concat('%', :q, '%'))
                              or lower(p.excerpt) like lower(concat('%', :q, '%')))
           """)
    Page<Post> search(@Param("status") PostStatus status,
                      @Param("categoryId") Long categoryId,
                      @Param("type") ContentType type,
                      @Param("authorId") Long authorId,
                      @Param("q") String q,
                      Pageable pageable);

    /** Admin list. Status is optional here — an editor needs to see drafts. */
    @Query("""
           select p from Post p
            where (:status is null or p.status = :status)
              and (:categoryId is null or p.categoryId  = :categoryId)
              and (:type       is null or p.contentType = :type)
              and (:authorId   is null or p.authorId    = :authorId)
              and (:q is null or lower(p.title) like lower(concat('%', :q, '%'))
                              or lower(p.slug)  like lower(concat('%', :q, '%')))
           """)
    Page<Post> adminSearch(@Param("status") PostStatus status,
                           @Param("categoryId") Long categoryId,
                           @Param("type") ContentType type,
                           @Param("authorId") Long authorId,
                           @Param("q") String q,
                           Pageable pageable);

    /**
     * Related posts: same category, same cluster preferred, excluding self.
     * Ordered so cluster members come first — "next in this series" is a
     * stronger signal to a reader than "also in SOC 2".
     */
    @Query("""
           select p from Post p
            where p.status = :status
              and p.id <> :postId
              and (p.categoryId = :categoryId or p.pillarClusterId = :pillarId)
            order by case when p.pillarClusterId = :pillarId then 0 else 1 end,
                     p.publishedAt desc
           """)
    List<Post> findRelated(@Param("status") PostStatus status,
                           @Param("postId") Long postId,
                           @Param("categoryId") Long categoryId,
                           @Param("pillarId") Long pillarId,
                           Pageable pageable);

    /**
     * Published, indexable, and nothing links to it. The orphan report.
     * NOINDEX pages are excluded because an orphan you deliberately kept out of
     * the index is not a problem.
     */
    @Query("""
           select p from Post p
            where p.status = :status
              and p.inboundLinkCount = 0
              and p.robotsDirective = :indexable
            order by p.publishedAt desc
           """)
    List<Post> findOrphans(@Param("status") PostStatus status,
                           @Param("indexable") RobotsDirective indexable);

    /** Past its review interval. Comparison pages rot first, so they sort first. */
    @Query("""
           select p from Post p
            where p.status = :status
              and p.reviewIntervalMonths is not null
              and (p.lastVerifiedAt is null or p.lastVerifiedAt < :cutoffBase)
            order by case when p.contentType = :rotsFirst then 0 else 1 end,
                     p.lastVerifiedAt asc
           """)
    List<Post> findPossiblyStale(@Param("status") PostStatus status,
                                 @Param("rotsFirst") ContentType rotsFirst,
                                 @Param("cutoffBase") LocalDateTime cutoffBase);

    /**
     * View counting is a bare UPDATE, not a read-modify-write.
     *
     * Two readers hitting the same article in the same second would otherwise
     * both read N and both write N+1. It also avoids loading a @Lob-bearing
     * entity to change one bigint, on the hottest endpoint the public site has.
     */
    @Modifying
    @Query("update Post p set p.viewCount = p.viewCount + 1 where p.slug = :slug")
    void incrementViewCount(@Param("slug") String slug);

    @Modifying
    @Query("update Post p set p.helpfulYes = p.helpfulYes + 1 where p.slug = :slug")
    void incrementHelpfulYes(@Param("slug") String slug);

    @Modifying
    @Query("update Post p set p.helpfulNo = p.helpfulNo + 1 where p.slug = :slug")
    void incrementHelpfulNo(@Param("slug") String slug);

    @Modifying
    @Query("update Post p set p.inboundLinkCount = :count where p.id = :postId")
    void setInboundLinkCount(@Param("postId") Long postId, @Param("count") int count);

    /** Candidate set for the internal-link AI task. Never let the model recall a slug. */
    @Query("select p.id, p.slug, p.title from Post p where p.status = :status")
    List<Object[]> findPublishedSlugCandidates(@Param("status") PostStatus status);
}