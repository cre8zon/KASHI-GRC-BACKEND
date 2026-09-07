package com.kashi.grc.content.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.content.domain.ContentEnums.PostStatus;
import com.kashi.grc.content.domain.ContentEnums.SchemaType;
import com.kashi.grc.content.domain.Post;
import com.kashi.grc.content.domain.PostDraft;
import com.kashi.grc.content.domain.PostRevision;
import com.kashi.grc.content.domain.PostTag;
import com.kashi.grc.content.dto.PostDtos.PostUpsertRequest;
import com.kashi.grc.content.repository.PostDraftRepository;
import com.kashi.grc.content.repository.PostRepository;
import com.kashi.grc.content.repository.PostRevisionRepository;
import com.kashi.grc.content.repository.PostTagRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Creating and saving posts. The rules here are the ones that quietly cost you
 * six months of traffic if they are wrong.
 *
 * ── THE THREE THAT MATTER ────────────────────────────────────────────────────
 *
 * 1. contentUpdatedAt moves only when the blocks change. It is the public
 *    "Last updated" stamp and dateModified in JSON-LD. Autosave fires every two
 *    seconds; if this moved on every save, every article would claim to have
 *    been updated moments ago, and a freshness signal that is always true
 *    carries no information.
 *
 * 2. A slug change on a published post writes a redirect first. Not after, not
 *    on a queue — before the new slug is persisted, in the same transaction.
 *
 * 3. A revision is written only when the blocks change, for the same reason as
 *    (1). Fifty useful revisions instead of four thousand identical ones.
 *
 * All three hang off one comparison: the SHA-256 of the normalised block array.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostService {

    /** Beyond this, older revisions are deleted. The value of a revision decays; a @Lob does not. */
    private static final int REVISION_CAP = 50;

    private final PostRepository postRepository;
    private final PostRevisionRepository revisionRepository;
    private final PostDraftRepository draftRepository;
    private final PostTagRepository postTagRepository;
    private final SlugService slugService;
    private final BlockService blockService;
    private final RedirectService redirectService;
    private final ContentInsightsService insightsService;
    private final UtilityService utilityService;
    private final ApplicationEventPublisher events;
    private final ObjectMapper mapper;

    // ── read ─────────────────────────────────────────────────────────────────

    public Post require(Long id) {
        Post post = postRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Post", id));
        return withTags(post);
    }

    /**
     * Attach the tag ids. Called on every path that hands a Post to the editor,
     * so a save round-trips the same shape it was given.
     */
    private Post withTags(Post post) {
        if (post != null && post.getId() != null) {
            post.setTagIds(postTagRepository.findTagIdsByPostId(post.getId()));
        }
        return post;
    }

    public boolean slugAvailable(String slug, Long excludeId) {
        String candidate = slugService.slugify(slug);
        if (candidate.isBlank()) return false;
        return excludeId == null
                ? !postRepository.existsBySlug(candidate)
                : !postRepository.existsBySlugAndIdNot(candidate, excludeId);
    }

    // ── write ────────────────────────────────────────────────────────────────

    @Transactional
    public Post create(PostUpsertRequest req) {
        Long actorId = currentUserId();

        Post post = new Post();
        post.setTitle(req.getTitle() == null ? "Untitled" : req.getTitle());
        post.setSlug(slugService.unique(
                slugService.slugify(req.getSlug() != null ? req.getSlug() : post.getTitle()),
                postRepository::existsBySlug));

        if (req.getContentType() != null) post.setContentType(req.getContentType());
        post.setStatus(PostStatus.DRAFT);
        post.setAuthorId(req.getAuthorId());
        post.setLastEditedById(actorId);
        post.setSchemaType(defaultSchemaType(post));

        applyEditableFields(post, req);
        recomputeDerived(post, /* isNew */ true);

        Post saved = postRepository.save(post);
        replaceTags(saved.getId(), req.getTagIds());
        insightsService.reindexLinks(saved);

        log.info("[CONTENT] created | id={} slug={}", saved.getId(), saved.getSlug());
        return withTags(saved);
    }

    /**
     * Reverting a live article rewrites it under its readers, and unlike an
     * edit there is no partial form of it worth banking. Blocked, with the two
     * routes out named.
     */
    private void assertEditable(Post post) {
        if (post.getStatus() == PostStatus.PUBLISHED) {
            throw new BusinessException("POST_IS_LIVE",
                    "This post is live. Unpublish it first, or make the change as a draft.");
        }
    }

    /**
     * The autosave path. Called every two seconds while someone is typing, so
     * everything expensive is guarded by the hash comparison.
     *
     * ── LIVE POSTS FORK HERE ─────────────────────────────────────────────────
     *
     * There is one row per post and the public API reads it directly, so while
     * a post is PUBLISHED its row IS the live article. Autosaving into it put a
     * half-typed sentence in front of readers, and the build hook then shipped
     * it about ninety seconds later.
     *
     * So a live post is not edited in place. The change is banked against it as
     * a working copy and released deliberately.
     */
    @Transactional
    public Post update(Long id, PostUpsertRequest req) {
        Post post = require(id);
        if (post.getStatus() == PostStatus.PUBLISHED) return stageDraft(post, req);
        return applyUpdate(post, req);
    }

    /**
     * Merge this change into the post's working copy.
     *
     * Merged, not replaced. Autosave sends only what changed — a title here, a
     * meta description there — so overwriting the payload each time would drop
     * every earlier edit in the session and leave a draft containing whichever
     * field happened to be touched last.
     */
    private Post stageDraft(Post post, PostUpsertRequest req) {
        Long actorId = currentUserId();
        PostDraft draft = draftRepository.findByPostId(post.getId())
                .orElseGet(() -> PostDraft.builder().postId(post.getId()).build());
        try {
            ObjectNode merged = draft.getPayloadJson() == null
                    ? mapper.createObjectNode()
                    : (ObjectNode) mapper.readTree(draft.getPayloadJson());

            // Null means "leave alone" everywhere else in PostUpsertRequest, so
            // it has to mean that here too.
            ObjectNode incoming = mapper.valueToTree(req);
            incoming.fields().forEachRemaining(e -> {
                if (!e.getValue().isNull()) merged.set(e.getKey(), e.getValue());
            });

            draft.setPayloadJson(mapper.writeValueAsString(merged));
            draft.setUpdatedById(actorId);
            draftRepository.save(draft);
            return withDraft(withTags(post), merged);
        } catch (Exception e) {
            throw new BusinessException("DRAFT_SAVE_FAILED",
                    "Could not save your changes: " + e.getMessage());
        }
    }

    /** Release the working copy: apply it, drop it, let the site rebuild. */
    @Transactional
    public Post publishDraft(Long id) {
        Post post = require(id);
        PostDraft draft = draftRepository.findByPostId(id)
                .orElseThrow(() -> new BusinessException("NO_DRAFT",
                        "There are no unpublished changes on this post"));
        try {
            PostUpsertRequest req = mapper.readValue(draft.getPayloadJson(), PostUpsertRequest.class);
            Post updated = applyUpdate(post, req);
            draftRepository.deleteByPostId(id);
            log.info("[CONTENT] draft released | post={}", id);
            return updated;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("DRAFT_PUBLISH_FAILED",
                    "Could not publish those changes: " + e.getMessage());
        }
    }

    /** Throw the working copy away. The live article was never touched. */
    @Transactional
    public void discardDraft(Long id) {
        draftRepository.deleteByPostId(id);
        log.info("[CONTENT] draft discarded | post={}", id);
    }

    /** The post as the EDITOR should see it: live values with the draft on top. */
    @Transactional(readOnly = true)
    public Post requireForEditing(Long id) {
        Post post = withTags(require(id));
        return draftRepository.findByPostId(id)
                .map(d -> {
                    try {
                        return withDraft(post, (ObjectNode) mapper.readTree(d.getPayloadJson()));
                    } catch (Exception e) {
                        // A corrupt draft must not make the post unopenable.
                        log.warn("[CONTENT] unreadable draft on post {} — ignoring", id);
                        return post;
                    }
                })
                .orElse(post);
    }

    /**
     * Overlay the draft onto the post, for the editor only.
     *
     * Round-tripped through Jackson rather than mutated in place. The Post
     * handed in is managed by the persistence context, and setting fields on it
     * would flush the draft onto the live row at commit — the exact bug this
     * whole table exists to prevent. A detached copy cannot do that.
     */
    private Post withDraft(Post post, ObjectNode payload) {
        try {
            ObjectNode asJson = mapper.valueToTree(post);
            payload.fields().forEachRemaining(e -> asJson.set(e.getKey(), e.getValue()));
            Post view = mapper.treeToValue(asJson, Post.class);
            view.setHasUnpublishedChanges(true);
            return view;
        } catch (Exception e) {
            log.warn("[CONTENT] could not overlay draft on post {}", post.getId(), e);
            return post;
        }
    }

    private Post applyUpdate(Post post, PostUpsertRequest req) {
        Long id = post.getId();
        Long actorId = currentUserId();

        String previousHash = post.getBlocksHash();
        String previousBlocks = post.getContentBlocks();

        // ── slug: follows the title, until it does not ───────────────────────
        //
        // Ordering matters. An explicit slug in the request means someone typed
        // one, which locks it; only when none was sent do we consider deriving
        // a new one from the title.
        if (req.getSlug() == null
                && !Boolean.TRUE.equals(post.getSlugLocked())
                && post.getStatus() != PostStatus.PUBLISHED
                && post.getPublishedAt() == null
                && req.getTitle() != null && !req.getTitle().isBlank()) {

            String derived = slugService.slugify(req.getTitle());
            if (!derived.isBlank() && !derived.equals(post.getSlug())) {
                post.setSlug(slugService.unique(derived,
                        candidate -> postRepository.existsBySlugAndIdNot(candidate, id)));
            }
        }

        // ── an explicit slug, and the redirect it may require ────────────────
        if (req.getSlug() != null) {
            String desired = slugService.slugify(req.getSlug());
            if (desired.isBlank()) {
                throw new BusinessException("SLUG_INVALID",
                        "That slug reduces to nothing once punctuation is removed");
            }
            if (!desired.equals(post.getSlug())) {
                if (postRepository.existsBySlugAndIdNot(desired, id)) {
                    throw new BusinessException("SLUG_TAKEN",
                            "Another post already uses /blog/" + desired);
                }
                // Only a live URL needs a redirect. Nothing points at a draft.
                if (post.getStatus() == PostStatus.PUBLISHED) {
                    redirectService.recordSlugChange(id, post.getSlug(), desired, actorId);
                    // The redirect exists in the database; the site still serves
                    // the old file until it rebuilds.
                    events.publishEvent(new ContentEvents.PostUrlChanged(id, post.getSlug(), desired));
                }
                post.setSlug(desired);
            }
            // Typed once and it stops following — including if the title
            // changes again tomorrow.
            post.setSlugLocked(true);
        }

        applyEditableFields(post, req);
        post.setLastEditedById(actorId);

        boolean blocksChanged = recomputeDerived(post, /* isNew */ false);

        if (blocksChanged) {
            // The snapshot is of the state BEFORE this save, so reverting to
            // revision N restores what the post looked like at revision N.
            writeRevision(post, previousBlocks, previousHash, actorId, null);
            post.setContentUpdatedAt(LocalDateTime.now());
        }

        Post saved = postRepository.save(post);
        if (req.getTagIds() != null) replaceTags(saved.getId(), req.getTagIds());
        if (blocksChanged) insightsService.reindexLinks(saved);

        return withTags(saved);
    }

    /**
     * Soft delete. A published article is never actually removed — its URL has
     * inbound links, and a hard delete turns all of them into 404s with no way
     * back. ARCHIVED plus a redirect to the category page is the recoverable
     * version of the same intent.
     */
    @Transactional
    public void archive(Long id, String redirectTo) {
        Post post = require(id);
        Long actorId = currentUserId();

        if (post.getStatus() == PostStatus.PUBLISHED) {
            String target = (redirectTo == null || redirectTo.isBlank()) ? "/blog" : redirectTo;
            redirectService.create("/blog/" + post.getSlug(), target, 301,
                    "post archived", actorId);
        }
        post.setStatus(PostStatus.ARCHIVED);
        post.setLastEditedById(actorId);
        postRepository.save(post);
        insightsService.clearLinks(post.getId());

        log.info("[CONTENT] archived | id={} slug={}", id, post.getSlug());
    }

    // ── revisions ────────────────────────────────────────────────────────────

    public List<PostRevision> revisions(Long postId) {
        return revisionRepository.findByPostIdOrderByRevisionNumberDesc(postId);
    }

    /**
     * Reverting is itself a save: it writes a revision of the current state
     * first, so an accidental revert is undoable. A revert you cannot undo is a
     * second way to lose work rather than a way to recover it.
     */
    @Transactional
    public Post revert(Long postId, Long revisionId) {
        Post post = require(postId);
        // Same rule. Reverting a live article rewrites it under its readers.
        assertEditable(post);
        PostRevision revision = revisionRepository.findById(revisionId)
                .orElseThrow(() -> new ResourceNotFoundException("PostRevision", revisionId));
        if (!Objects.equals(revision.getPostId(), postId)) {
            throw new BusinessException("REVISION_MISMATCH", "That revision belongs to a different post");
        }

        Long actorId = currentUserId();
        writeRevision(post, post.getContentBlocks(), post.getBlocksHash(), actorId,
                "auto-saved before reverting to revision " + revision.getRevisionNumber());

        try {
            Post snapshot = mapper.readValue(revision.getSnapshotJson(), Post.class);
            post.setTitle(snapshot.getTitle());
            post.setSubtitle(snapshot.getSubtitle());
            post.setExcerpt(snapshot.getExcerpt());
            post.setContentBlocks(snapshot.getContentBlocks());
            post.setMetaTitle(snapshot.getMetaTitle());
            post.setMetaDescription(snapshot.getMetaDescription());
            post.setDefinitionSummary(snapshot.getDefinitionSummary());
        } catch (Exception e) {
            throw new BusinessException("REVISION_UNREADABLE",
                    "That revision could not be read back: " + e.getMessage());
        }

        recomputeDerived(post, false);
        post.setContentUpdatedAt(LocalDateTime.now());
        post.setLastEditedById(actorId);

        Post saved = postRepository.save(post);
        insightsService.reindexLinks(saved);
        return saved;
    }

    // ── internals ────────────────────────────────────────────────────────────

    private void applyEditableFields(Post post, PostUpsertRequest req) {
        if (req.getTitle() != null)             post.setTitle(req.getTitle());
        if (req.getSubtitle() != null)          post.setSubtitle(req.getSubtitle());
        if (req.getExcerpt() != null)           post.setExcerpt(req.getExcerpt());
        if (req.getContentBlocks() != null)     post.setContentBlocks(req.getContentBlocks());
        if (req.getContentType() != null)       post.setContentType(req.getContentType());
        if (req.getCategoryId() != null)        post.setCategoryId(req.getCategoryId());
        if (req.getAuthorId() != null)          post.setAuthorId(req.getAuthorId());
        if (req.getReviewedById() != null)      post.setReviewedById(req.getReviewedById());
        if (req.getHeroImageId() != null)       post.setHeroImageId(req.getHeroImageId());
        if (req.getOgImageId() != null)         post.setOgImageId(req.getOgImageId());
        if (req.getMetaTitle() != null)         post.setMetaTitle(req.getMetaTitle());
        if (req.getMetaDescription() != null)   post.setMetaDescription(req.getMetaDescription());
        if (req.getCanonicalUrl() != null)      post.setCanonicalUrl(req.getCanonicalUrl());
        if (req.getRobotsDirective() != null)   post.setRobotsDirective(req.getRobotsDirective());
        if (req.getFocusKeyword() != null)      post.setFocusKeyword(req.getFocusKeyword());
        if (req.getSchemaType() != null)        post.setSchemaType(req.getSchemaType());
        if (req.getPillarClusterId() != null)   post.setPillarClusterId(req.getPillarClusterId());
        if (req.getClusterOrder() != null)      post.setClusterOrder(req.getClusterOrder());
        if (req.getDefinitionSummary() != null) post.setDefinitionSummary(req.getDefinitionSummary());
        if (req.getComparisonDataIds() != null) post.setComparisonDataIds(req.getComparisonDataIds());
        if (req.getReviewIntervalMonths() != null) post.setReviewIntervalMonths(req.getReviewIntervalMonths());
        if (Boolean.TRUE.equals(req.getMarkVerified())) post.setLastVerifiedAt(LocalDateTime.now());
    }

    /**
     * Normalise blocks, recompute read time and word count, and report whether
     * the content actually changed.
     *
     * @return true when the block hash differs from what was stored
     */
    private boolean recomputeDerived(Post post, boolean isNew) {
        ArrayNode blocks = blockService.normalise(blockService.parse(post.getContentBlocks()));
        post.setContentBlocks(blockService.write(blocks));

        String hash = blockService.hash(blocks);
        boolean changed = isNew || !hash.equals(post.getBlocksHash());

        post.setBlocksHash(hash);
        post.setWordCount(blockService.wordCount(blocks));
        post.setReadTimeMinutes(blockService.readTimeMinutes(blocks));
        if (post.getSchemaType() == null) post.setSchemaType(defaultSchemaType(post));

        return changed;
    }

    private void writeRevision(Post post, String blocks, String hash, Long actorId, String note) {
        if (post.getId() == null) return;

        int next = revisionRepository.maxRevisionNumber(post.getId()) + 1;
        String snapshot;
        try {
            Post copy = new Post();
            copy.setTitle(post.getTitle());
            copy.setSubtitle(post.getSubtitle());
            copy.setExcerpt(post.getExcerpt());
            copy.setContentBlocks(blocks);
            copy.setMetaTitle(post.getMetaTitle());
            copy.setMetaDescription(post.getMetaDescription());
            copy.setDefinitionSummary(post.getDefinitionSummary());
            copy.setBlocksHash(hash);
            snapshot = mapper.writeValueAsString(copy);
        } catch (Exception e) {
            log.warn("[CONTENT] could not snapshot revision for post {}: {}", post.getId(), e.getMessage());
            return;
        }

        revisionRepository.save(PostRevision.builder()
                .postId(post.getId())
                .revisionNumber(next)
                .snapshotJson(snapshot)
                .editedById(actorId)
                .note(note)
                .build());

        long count = revisionRepository.countByPostId(post.getId());
        if (count > REVISION_CAP) {
            List<PostRevision> oldest = revisionRepository
                    .findByPostIdOrderByRevisionNumberAsc(post.getId());
            revisionRepository.deleteAll(oldest.subList(0, (int) (count - REVISION_CAP)));
        }
    }

    private void replaceTags(Long postId, List<Long> tagIds) {
        postTagRepository.deleteByPostId(postId);
        if (tagIds == null || tagIds.isEmpty()) return;
        List<PostTag> rows = new ArrayList<>();
        tagIds.stream().distinct().forEach(tagId ->
                rows.add(PostTag.builder().postId(postId).tagId(tagId).build()));
        postTagRepository.saveAll(rows);
    }

    /** Sensible default per type; an author can override it in the SEO panel. */
    private SchemaType defaultSchemaType(Post post) {
        return switch (post.getContentType()) {
            case GLOSSARY   -> SchemaType.DefinedTerm;
            case COMPARISON -> SchemaType.Review;
            case CASE_STUDY -> SchemaType.Article;
            default         -> SchemaType.BlogPosting;
        };
    }

    private Long currentUserId() {
        try {
            var user = utilityService.getLoggedInDataContext();
            return user == null ? null : user.getId();
        } catch (Exception e) {
            // The scheduled publisher runs without a request context.
            return null;
        }
    }
}