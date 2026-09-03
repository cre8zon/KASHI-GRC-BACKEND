package com.kashi.grc.content.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kashi.grc.common.dto.PaginatedResponse;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.content.domain.*;
import com.kashi.grc.content.domain.ContentEnums.ContentType;
import com.kashi.grc.content.domain.ContentEnums.PostStatus;
import com.kashi.grc.content.dto.PostDtos.*;
import com.kashi.grc.content.dto.PublicDtos.*;
import com.kashi.grc.content.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The read side, for both audiences.
 *
 * ── WHY ONE SERVICE FOR PUBLIC AND ADMIN READS ───────────────────────────────
 * They project the same rows into different shapes. Splitting them would mean
 * two places that both decide what "published" means, and the second one
 * eventually forgets a status. The public methods assert PUBLISHED explicitly;
 * the admin methods do not filter at all. That difference is visible on one
 * screen here rather than across two files.
 *
 * ── ON N+1 ───────────────────────────────────────────────────────────────────
 * Zero-FK means no JPA associations, so nothing lazy-loads behind your back —
 * but it also means the joins are manual. Every list method below batch-loads
 * its authors, categories and media in one query each and maps in memory. A
 * listing of twelve posts is four queries, not thirty-seven.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentQueryService {

    private static final int RELATED_COUNT = 4;

    private final PostRepository postRepository;
    private final ContentAccessService accessService;
    private final ContentAuthorRepository authorRepository;
    private final ContentCategoryRepository categoryRepository;
    private final ContentTagRepository tagRepository;
    private final PostTagRepository postTagRepository;
    private final ContentMediaRepository mediaRepository;
    private final ContentRedirectRepository redirectRepository;
    private final ComparisonDataRepository comparisonRepository;
    private final NewsletterSubscriberRepository subscriberRepository;
    private final ContentTaxonomyService taxonomyService;
    private final NewsletterService newsletterService;
    private final BlockService blockService;
    private final SlugService slugService;
    private final ObjectMapper mapper;

    // ── public reads ─────────────────────────────────────────────────────────

    public PaginatedResponse<PostCard> listPublished(Map<String, String> params) {
        int page = intParam(params, "page", 1);
        int size = Math.min(intParam(params, "size", 12), 50);
        PageRequest pr = PageRequest.of(Math.max(0, page - 1), size,
                Sort.by(Sort.Direction.DESC, "publishedAt"));

        Long categoryId = params.containsKey("category")
                ? categoryRepository.findBySlug(params.get("category")).map(ContentCategory::getId).orElse(-1L)
                : null;
        Long authorId = params.containsKey("author")
                ? authorRepository.findBySlug(params.get("author")).map(ContentAuthor::getId).orElse(-1L)
                : null;
        ContentType type = params.containsKey("type")
                ? ContentType.valueOf(params.get("type").toUpperCase()) : null;

        Page<Post> result;
        if (params.containsKey("tag")) {
            // Tag filtering goes through the join table, so it cannot ride the
            // same query. Two steps, both indexed.
            Long tagId = tagRepository.findBySlug(params.get("tag")).map(ContentTag::getId).orElse(-1L);
            List<Long> postIds = postTagRepository.findPostIdsByTagId(tagId);
            List<Post> all = postIds.isEmpty() ? List.of()
                    : postRepository.findAllById(postIds).stream()
                      .filter(p -> p.getStatus() == PostStatus.PUBLISHED)
                      .sorted(Comparator.comparing(Post::getPublishedAt,
                              Comparator.nullsLast(Comparator.reverseOrder())))
                      .toList();
            int from = Math.min((page - 1) * size, all.size());
            int to   = Math.min(from + size, all.size());
            result = new org.springframework.data.domain.PageImpl<>(
                    all.subList(from, to), pr, all.size());
        } else {
            result = postRepository.search(PostStatus.PUBLISHED, categoryId, type, authorId,
                    params.get("q"), pr);
        }

        return new PaginatedResponse<>(toCards(result.getContent()), result);
    }

    public PostDetail getPublishedPost(String slug) {
        Post post = postRepository.findBySlugAndStatus(slug, PostStatus.PUBLISHED)
                .orElseThrow(() -> new ResourceNotFoundException("Post", "slug", slug));

        PostDetail d = new PostDetail();
        d.setSlug(post.getSlug());
        d.setTitle(post.getTitle());
        d.setSubtitle(post.getSubtitle());
        d.setExcerpt(post.getExcerpt());
        d.setDefinitionSummary(post.getDefinitionSummary());
        d.setContentType(post.getContentType().name());
        d.setPublishedAt(post.getPublishedAt());
        d.setContentUpdatedAt(post.getContentUpdatedAt());
        d.setLastVerifiedAt(post.getLastVerifiedAt());
        d.setReadTimeMinutes(post.getReadTimeMinutes());
        d.setWordCount(post.getWordCount());
        d.setMetaTitle(post.getMetaTitle());
        d.setMetaDescription(post.getMetaDescription());
        d.setCanonicalUrl(post.getCanonicalUrl());
        d.setRobotsDirective(post.getRobotsDirective().toDirective());
        d.setSchemaType(post.getSchemaType() == null ? null : post.getSchemaType().name());

        // Blocks go out parsed. The renderer walks an array; making it parse a
        // string that the server already parsed to compute the read time is
        // work done twice for no reason.
        ArrayNode blocks = blockService.parse(post.getContentBlocks());
        hydratePublicBlocks(blocks);
        d.setContentBlocks(blocks);

        if (post.getCategoryId() != null) {
            categoryRepository.findById(post.getCategoryId()).ifPresent(c -> {
                d.setCategoryName(c.getName());
                d.setCategorySlug(c.getSlug());
            });
        }

        List<Long> tagIds = postTagRepository.findTagIdsByPostId(post.getId());
        if (!tagIds.isEmpty()) {
            d.setTags(tagRepository.findByIdIn(tagIds).stream().map(t -> {
                TagRef r = new TagRef();
                r.setSlug(t.getSlug());
                r.setName(t.getName());
                return r;
            }).toList());
        }

        Map<Long, ContentAuthor> authors = authorsByIds(
                Arrays.asList(post.getAuthorId(), post.getReviewedById()));
        d.setAuthor(authorRef(authors.get(post.getAuthorId())));
        d.setReviewedBy(authorRef(authors.get(post.getReviewedById())));

        Map<Long, ContentMedia> media = mediaByIds(
                Arrays.asList(post.getHeroImageId(), post.getOgImageId()));
        ContentMedia hero = media.get(post.getHeroImageId());
        if (hero != null) {
            d.setHeroImageUrl(hero.getUrl());
            d.setHeroImageAlt(hero.getAltText());
            d.setHeroImageWidth(hero.getWidth());
            d.setHeroImageHeight(hero.getHeight());
        }
        ContentMedia og = media.get(post.getOgImageId());
        d.setOgImageUrl(og != null ? og.getUrl() : (hero != null ? hero.getUrl() : null));

        d.setRelatedPosts(toCards(postRepository.findRelated(
                PostStatus.PUBLISHED,
                post.getId(),
                post.getCategoryId() == null ? -1L : post.getCategoryId(),
                post.getPillarClusterId() == null ? -1L : post.getPillarClusterId(),
                PageRequest.of(0, RELATED_COUNT))));

        // Prev/next within the pillar cluster. Ordered, so "next in this series"
        // means something rather than being the most recent thing nearby.
        if (post.getPillarClusterId() != null) {
            List<Post> series = postRepository.findByPillarClusterIdAndStatusOrderByClusterOrderAsc(
                    post.getPillarClusterId(), PostStatus.PUBLISHED);
            int idx = -1;
            for (int i = 0; i < series.size(); i++) {
                if (series.get(i).getId().equals(post.getId())) { idx = i; break; }
            }
            if (idx > 0) d.setPreviousInSeries(toCards(List.of(series.get(idx - 1))).get(0));
            if (idx >= 0 && idx < series.size() - 1) {
                d.setNextInSeries(toCards(List.of(series.get(idx + 1))).get(0));
            }
        }

        if (post.getContentType() == ContentType.COMPARISON
                && post.getComparisonDataIds() != null && !post.getComparisonDataIds().isBlank()) {
            List<Long> ids = Arrays.stream(post.getComparisonDataIds().split(","))
                    .map(String::trim).filter(s -> !s.isEmpty())
                    .map(Long::valueOf).toList();
            d.setComparisons(comparisonRepository.findByIdIn(ids).stream()
                    .map(this::comparisonRow).toList());
        }

        return d;
    }

    /**
     * Resolve the ids inside blocks into the values the public renderer reads.
     *
     * ── WHY THE STORED BLOCK KEEPS THE ID ────────────────────────────────────
     * An `image` block stores mediaId, not a URL. That is right: the CDN base
     * can change, alt text is edited in the media library and has to update
     * everywhere at once, and a URL frozen into a hundred posts is a hundred
     * rows to migrate. The same argument holds for `comparison`, which stores
     * competitor ids so that changing Vanta's pricing once changes every page
     * that quotes it.
     *
     * But the renderer cannot resolve an id — the public site has no auth and no
     * media endpoint. So the wire format is hydrated here, on the way out. The
     * stored document stays canonical; the served one is renderable.
     *
     * This was missing, and the symptom was silent: ImageBlock and
     * DownloadBlock both open with `if (!block.url) return null`, so every
     * image and every download in a published post rendered as nothing at all,
     * with no error anywhere. The comparison renderer's own comment says
     * "resolved server-side into block.columns" — the contract was written down
     * and never implemented on this side of it.
     */
    private void hydratePublicBlocks(ArrayNode blocks) {
        List<Long> mediaIds = new ArrayList<>();
        List<Long> comparisonIds = new ArrayList<>();

        for (JsonNode node : blocks) {
            String type = node.path("type").asText("");
            if (("image".equals(type) || "download".equals(type)) && node.hasNonNull("mediaId")) {
                mediaIds.add(node.path("mediaId").asLong());
            }
            if ("comparison".equals(type)) {
                node.path("comparisonDataIds").forEach(id -> comparisonIds.add(id.asLong()));
            }
        }
        if (mediaIds.isEmpty() && comparisonIds.isEmpty()) return;

        // One query each, not one per block.
        Map<Long, ContentMedia> media = mediaByIds(mediaIds);
        Map<Long, ComparisonData> comparisons = new HashMap<>();
        if (!comparisonIds.isEmpty()) {
            comparisonRepository.findByIdIn(comparisonIds.stream().distinct().toList())
                    .forEach(c -> comparisons.put(c.getId(), c));
        }

        for (JsonNode node : blocks) {
            if (!(node instanceof ObjectNode b)) continue;
            String type = b.path("type").asText("");

            if ("image".equals(type) || "download".equals(type)) {
                ContentMedia m = media.get(b.path("mediaId").asLong());
                if (m == null) continue;   // deleted asset: the block renders as nothing, which is correct
                b.put("url", m.getUrl());
                if ("image".equals(type)) {
                    b.put("alt", m.getAltText());
                    // Width and height go on the img tag so the browser reserves
                    // space before the image loads. Without them every article
                    // reflows as it renders, and CLS is one of the three Core
                    // Web Vitals this whole architecture is measured on.
                    if (m.getWidth() != null) b.put("width", m.getWidth());
                    if (m.getHeight() != null) b.put("height", m.getHeight());
                    if (b.path("caption").asText("").isBlank() && m.getCaption() != null) {
                        b.put("caption", m.getCaption());
                    }
                }
            }

            if ("comparison".equals(type)) {
                ArrayNode columns = b.putArray("columns");
                LocalDateTime oldestVerified = null;
                int index = 0;

                for (JsonNode idNode : b.path("comparisonDataIds")) {
                    ComparisonData c = comparisons.get(idNode.asLong());
                    if (c == null) continue;

                    ObjectNode col = columns.addObject();
                    col.put("name", c.getCompetitorName());
                    // The first attached record is the one being compared
                    // against — by convention, and the editor controls the
                    // order. Add KashiGRC as its own ComparisonData row and put
                    // it first to get the highlighted column.
                    col.put("highlight", index == 0);
                    col.put("positioning", c.getPositioning());
                    if (c.getG2Rating() != null) col.put("g2Rating", c.getG2Rating());

                    // values are keyed by the attribute labels on the block, so
                    // those labels must match the keys in featureFlagsJson. A
                    // missing key renders as an empty cell rather than a crash.
                    ObjectNode values = col.putObject("values");
                    JsonNode flags = readJsonNode(c.getFeatureFlagsJson());
                    for (JsonNode attr : b.path("attributes")) {
                        String key = attr.asText();
                        values.put(key, flags != null && flags.hasNonNull(key)
                                ? flags.path(key).asText() : "");
                    }

                    if (c.getLastVerifiedAt() != null
                            && (oldestVerified == null || c.getLastVerifiedAt().isBefore(oldestVerified))) {
                        // The OLDEST, not the newest: the table is only as
                        // current as its stalest column, and claiming otherwise
                        // is the exact trust problem the stamp exists to solve.
                        oldestVerified = c.getLastVerifiedAt();
                    }
                }
                if (oldestVerified != null) b.put("lastVerified", oldestVerified.toString());
            }
        }
    }

    private JsonNode readJsonNode(String json) {
        if (json == null || json.isBlank()) return null;
        try { return mapper.readTree(json); } catch (Exception e) { return null; }
    }

    public List<CategoryDetail> listCategories() {
        Map<Long, Integer> counts = publishedCountPerCategory();
        return categoryRepository.findAllByOrderBySortOrderAscNameAsc().stream()
                .map(c -> categoryDetail(c, counts.getOrDefault(c.getId(), 0)))
                .toList();
    }

    public CategoryDetail getCategory(String slug) {
        ContentCategory c = categoryRepository.findBySlug(slug)
                .orElseThrow(() -> new ResourceNotFoundException("ContentCategory", "slug", slug));
        return categoryDetail(c, publishedCountPerCategory().getOrDefault(c.getId(), 0));
    }

    public List<CategoryDetail> listTags() {
        Map<Long, Long> counts = taxonomyService.publishedCountPerTag();
        return tagRepository.findAllByOrderByNameAsc().stream().map(t -> {
            CategoryDetail d = new CategoryDetail();
            d.setSlug(t.getSlug());
            d.setName(t.getName());
            d.setDescription(t.getDescription());
            d.setPostCount(counts.getOrDefault(t.getId(), 0L).intValue());
            return d;
        }).toList();
    }

    public CategoryDetail getTag(String slug) {
        ContentTag t = tagRepository.findBySlug(slug)
                .orElseThrow(() -> new ResourceNotFoundException("ContentTag", "slug", slug));
        CategoryDetail d = new CategoryDetail();
        d.setSlug(t.getSlug());
        d.setName(t.getName());
        d.setDescription(t.getDescription());
        d.setPostCount(taxonomyService.publishedCountPerTag()
                .getOrDefault(t.getId(), 0L).intValue());
        return d;
    }

    public List<AuthorRef> listAuthors() {
        return authorRepository.findByActiveTrueOrderByDisplayNameAsc().stream()
                .map(this::authorRef).toList();
    }

    public AuthorDetail getAuthor(String slug) {
        ContentAuthor a = authorRepository.findBySlug(slug)
                .orElseThrow(() -> new ResourceNotFoundException("ContentAuthor", "slug", slug));

        AuthorDetail d = new AuthorDetail();
        d.setSlug(a.getSlug());
        d.setDisplayName(a.getDisplayName());
        d.setRole(a.getRole());
        d.setBio(a.getBio());
        d.setCredentials(a.getCredentials());
        d.setHeadshotUrl(mediaUrl(a.getHeadshotMediaId()));
        d.setLinkedinUrl(a.getLinkedinUrl());
        d.setXUrl(a.getXUrl());
        d.setWebsiteUrl(a.getWebsiteUrl());
        d.setPosts(toCards(postRepository.findByStatusAndAuthorId(
                PostStatus.PUBLISHED, a.getId(),
                PageRequest.of(0, 24, Sort.by(Sort.Direction.DESC, "publishedAt"))).getContent()));
        return d;
    }

    /**
     * Everything the prerenderer needs, in one call.
     *
     * postCount on categories and tags is load-bearing: without it the build
     * writes only page one of every listing, and pages two onward silently
     * never exist. Tags below the indexable threshold are omitted entirely
     * rather than shipped as noindex, because a URL in the sitemap that says
     * "do not index me" is a mixed signal for no benefit.
     */
    public SitemapData sitemapData() {
        SitemapData data = new SitemapData();

        List<Post> published = postRepository.findByStatusOrderByPublishedAtDesc(PostStatus.PUBLISHED);

        data.setPosts(published.stream()
                .filter(p -> p.getContentType() != ContentType.PILLAR)
                .filter(p -> p.getRobotsDirective() == ContentEnums.RobotsDirective.INDEX_FOLLOW)
                .map(p -> entry("post", p.getSlug(), "/blog/" + p.getSlug(),
                        p.getContentUpdatedAt() == null ? p.getPublishedAt() : p.getContentUpdatedAt(), null))
                .toList());

        data.setPillars(published.stream()
                .filter(p -> p.getContentType() == ContentType.PILLAR)
                .map(p -> entry("pillar", p.getSlug(), "/resources/" + p.getSlug(),
                        p.getContentUpdatedAt(), null))
                .toList());

        Map<Long, Integer> catCounts = publishedCountPerCategory();
        data.setCategories(categoryRepository.findAllByOrderBySortOrderAscNameAsc().stream()
                .filter(c -> catCounts.getOrDefault(c.getId(), 0) > 0)
                .map(c -> entry("category", c.getSlug(), "/blog/category/" + c.getSlug(),
                        c.getUpdatedAt(), catCounts.get(c.getId())))
                .toList());

        Map<Long, Long> tagCounts = taxonomyService.publishedCountPerTag();
        data.setTags(tagRepository.findAllByOrderByNameAsc().stream()
                .filter(t -> Boolean.TRUE.equals(t.getIndexable()))
                .filter(t -> tagCounts.getOrDefault(t.getId(), 0L) > 0)
                .map(t -> entry("tag", t.getSlug(), "/blog/tag/" + t.getSlug(),
                        t.getUpdatedAt(), tagCounts.get(t.getId()).intValue()))
                .toList());

        Set<Long> authorsWithPosts = published.stream()
                .map(Post::getAuthorId).filter(Objects::nonNull).collect(Collectors.toSet());
        data.setAuthors(authorRepository.findByActiveTrueOrderByDisplayNameAsc().stream()
                .filter(a -> authorsWithPosts.contains(a.getId()))
                .map(a -> entry("author", a.getSlug(), "/authors/" + a.getSlug(),
                        a.getUpdatedAt(), null))
                .toList());

        return data;
    }

    public List<RedirectRule> activeRedirects() {
        return redirectRepository.findByActiveTrue().stream().map(r -> {
            RedirectRule rule = new RedirectRule();
            rule.setFromPath(r.getFromPath());
            rule.setToPath(r.getToPath());
            rule.setStatusCode(r.getStatusCode());
            return rule;
        }).toList();
    }

    // ── writes from the public site ──────────────────────────────────────────

    /**
     * @Async so the increment never sits on the request. The endpoint returns
     * 204 immediately; sendBeacon on the other end cannot read a response
     * anyway, and an article render must not wait on a counter.
     */
    @Async
    @Transactional
    public void recordViewAsync(String slug) {
        try {
            postRepository.incrementViewCount(slug);
        } catch (Exception e) {
            log.debug("[CONTENT] view not recorded for {}: {}", slug, e.getMessage());
        }
    }

    @Transactional
    public void recordHelpful(String slug, boolean helpful) {
        if (helpful) postRepository.incrementHelpfulYes(slug);
        else         postRepository.incrementHelpfulNo(slug);
    }

    /**
     * Newsletter signup and confirmation are delegated, not reimplemented here.
     * Consent handling is the whole substance of that feature and it belongs in
     * one place — see NewsletterService.
     */
    @Transactional
    public void requestSubscription(String email, String name, String sourcePath) {
        newsletterService.subscribe(email, name, sourcePath);
    }

    @Transactional
    public void confirmSubscription(String token) {
        newsletterService.confirm(token);
    }

    // ── admin reads ──────────────────────────────────────────────────────────

    public PaginatedResponse<PostListItem> adminList(Map<String, String> params) {
        int page = intParam(params, "page", 1);
        int size = Math.min(intParam(params, "size", 25), 100);
        PageRequest pr = PageRequest.of(Math.max(0, page - 1), size,
                Sort.by(Sort.Direction.DESC, "updatedAt"));

        PostStatus status = params.containsKey("status") && !params.get("status").isBlank()
                ? PostStatus.valueOf(params.get("status").toUpperCase()) : null;
        ContentType type = params.containsKey("type") && !params.get("type").isBlank()
                ? ContentType.valueOf(params.get("type").toUpperCase()) : null;
        Long categoryId = params.containsKey("categoryId") ? Long.valueOf(params.get("categoryId")) : null;
        // An author sees only their own posts; an editor or admin sees every
        // post and may still filter by ?authorId=. Scoping here rather than in
        // the controller means a future caller of adminList cannot forget it.
        // -1L rather than null for a user with no author profile: null means
        // "no filter", which would return everything. A sentinel fails closed.
        Long authorId;
        if (accessService.canManageTaxonomy()) {
            authorId = params.containsKey("authorId") ? Long.valueOf(params.get("authorId")) : null;
        } else {
            authorId = accessService.currentAuthor().map(ContentAuthor::getId).orElse(-1L);
        }

        Page<Post> result = postRepository.adminSearch(status, categoryId, type, authorId,
                params.get("q"), pr);

        Map<Long, ContentAuthor> authors = authorsByIds(
                result.getContent().stream().map(Post::getAuthorId).toList());
        Map<Long, ContentCategory> categories = categoriesByIds(
                result.getContent().stream().map(Post::getCategoryId).toList());

        List<PostListItem> items = result.getContent().stream().map(p -> {
            PostListItem i = new PostListItem();
            i.setId(p.getId());
            i.setSlug(p.getSlug());
            i.setTitle(p.getTitle());
            i.setContentType(p.getContentType());
            i.setStatus(p.getStatus());
            i.setPublishedAt(p.getPublishedAt());
            i.setContentUpdatedAt(p.getContentUpdatedAt());
            i.setLastVerifiedAt(p.getLastVerifiedAt());
            i.setReadTimeMinutes(p.getReadTimeMinutes());
            i.setViewCount(p.getViewCount());
            i.setHelpfulYes(p.getHelpfulYes());
            i.setHelpfulNo(p.getHelpfulNo());
            i.setInboundLinkCount(p.getInboundLinkCount());
            i.setRobotsDirective(p.getRobotsDirective());
            ContentAuthor a = authors.get(p.getAuthorId());
            i.setAuthorName(a == null ? null : a.getDisplayName());
            ContentCategory c = categories.get(p.getCategoryId());
            i.setCategoryName(c == null ? null : c.getName());
            i.setStale(isStale(p));
            return i;
        }).toList();

        return new PaginatedResponse<>(items, result);
    }

    public SlugCheckResponse checkSlug(String slug, Long excludeId) {
        String candidate = slugService.slugify(slug);
        SlugCheckResponse r = new SlugCheckResponse();
        r.setSlug(candidate);
        r.setAvailable(!candidate.isBlank() && (excludeId == null
                ? !postRepository.existsBySlug(candidate)
                : !postRepository.existsBySlugAndIdNot(candidate, excludeId)));

        // Free, but it used to belong to something else — publishing it would
        // create a URL that redirects away from itself.
        redirectRepository.findByFromPath("/blog/" + candidate)
                .filter(ContentRedirect::getActive)
                .ifPresent(rd -> r.setWarning(
                        "/blog/" + candidate + " currently redirects to " + rd.getToPath()
                                + ". Deactivate that redirect first."));
        return r;
    }

    /**
     * Advisory on-page checks.
     *
     * None of these block publication, and that is deliberate — see
     * SeoChecklistItem. An editor who learns that the checklist sometimes stops
     * them stops reading the checklist.
     */
    public List<SeoChecklistItem> seoChecklist(Long postId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Post", postId));
        ArrayNode blocks = blockService.parse(post.getContentBlocks());
        String body = blockService.textOf(blocks);
        String keyword = post.getFocusKeyword() == null ? "" : post.getFocusKeyword().toLowerCase();

        List<SeoChecklistItem> items = new ArrayList<>();

        items.add(check("title-length", "Title is 50–60 characters",
                post.getTitle() != null && post.getTitle().length() >= 40 && post.getTitle().length() <= 65,
                post.getTitle() == null ? "No title" : post.getTitle().length() + " characters", false));

        items.add(check("meta-description", "Meta description is 50–160 characters",
                post.getMetaDescription() != null
                        && post.getMetaDescription().length() >= 50
                        && post.getMetaDescription().length() <= 160,
                post.getMetaDescription() == null ? "Not set"
                        : post.getMetaDescription().length() + " characters", true));

        if (!keyword.isBlank()) {
            items.add(check("keyword-title", "Focus keyword appears in the title",
                    post.getTitle() != null && post.getTitle().toLowerCase().contains(keyword),
                    null, false));
            String opening = body.length() > 700 ? body.substring(0, 700).toLowerCase() : body.toLowerCase();
            items.add(check("keyword-opening", "Focus keyword appears in the first 100 words",
                    opening.contains(keyword), null, false));
            boolean inHeading = blockService.headings(blocks).stream()
                    .anyMatch(h -> h.text().toLowerCase().contains(keyword));
            items.add(check("keyword-heading", "Focus keyword appears in at least one heading",
                    inHeading, null, false));
        }

        items.add(check("tldr", "Has a key-takeaways block",
                blockService.hasBlockOfType(blocks, "tldr"),
                "This is what gets pulled into AI search summaries", false));

        items.add(check("faq", "Has an FAQ block",
                blockService.hasBlockOfType(blocks, "faq"),
                "Feeds FAQPage structured data", false));

        int h2Count = (int) blockService.headings(blocks).stream().filter(h -> h.level() == 2).count();
        items.add(check("structure", "At least three H2 sections",
                h2Count >= 3, h2Count + " found", false));

        items.add(check("hero", "Hero image set", post.getHeroImageId() != null,
                "Also the social card fallback", true));

        items.add(check("internal-links", "Links to at least two other posts",
                blockService.links(blocks).stream()
                        .filter(l -> l.href().startsWith("/blog/")).count() >= 2,
                "Internal links are how topic authority compounds", false));

        items.add(check("reviewer", "Reviewed by a named person",
                post.getReviewedById() != null,
                "A real E-E-A-T signal for compliance content", false));

        return items;
    }

    // ── mapping helpers ──────────────────────────────────────────────────────

    private List<PostCard> toCards(List<Post> posts) {
        if (posts.isEmpty()) return List.of();

        Map<Long, ContentAuthor> authors = authorsByIds(posts.stream().map(Post::getAuthorId).toList());
        Map<Long, ContentCategory> categories = categoriesByIds(posts.stream().map(Post::getCategoryId).toList());
        Map<Long, ContentMedia> media = mediaByIds(posts.stream().map(Post::getHeroImageId).toList());

        return posts.stream().map(p -> {
            PostCard c = new PostCard();
            c.setSlug(p.getSlug());
            c.setTitle(p.getTitle());
            c.setSubtitle(p.getSubtitle());
            c.setExcerpt(p.getExcerpt());
            c.setPublishedAt(p.getPublishedAt());
            c.setContentUpdatedAt(p.getContentUpdatedAt());
            c.setReadTimeMinutes(p.getReadTimeMinutes());
            ContentCategory cat = categories.get(p.getCategoryId());
            if (cat != null) { c.setCategoryName(cat.getName()); c.setCategorySlug(cat.getSlug()); }
            ContentMedia hero = media.get(p.getHeroImageId());
            if (hero != null) { c.setHeroImageUrl(hero.getUrl()); c.setHeroImageAlt(hero.getAltText()); }
            c.setAuthor(authorRef(authors.get(p.getAuthorId())));
            return c;
        }).toList();
    }

    private AuthorRef authorRef(ContentAuthor a) {
        if (a == null) return null;
        AuthorRef r = new AuthorRef();
        r.setSlug(a.getSlug());
        r.setDisplayName(a.getDisplayName());
        r.setRole(a.getRole());
        r.setCredentials(a.getCredentials());
        r.setHeadshotUrl(mediaUrl(a.getHeadshotMediaId()));
        return r;
    }

    private ComparisonRow comparisonRow(ComparisonData c) {
        ComparisonRow r = new ComparisonRow();
        r.setCompetitorName(c.getCompetitorName());
        r.setSlug(c.getSlug());
        r.setWebsiteUrl(c.getWebsiteUrl());
        r.setLogoUrl(mediaUrl(c.getLogoMediaId()));
        r.setPositioning(c.getPositioning());
        r.setPricingTiers(readJson(c.getPricingTiersJson()));
        r.setFeatureFlags(readJson(c.getFeatureFlagsJson()));
        r.setG2Rating(c.getG2Rating());
        r.setG2ReviewCount(c.getG2ReviewCount());
        r.setLastVerifiedAt(c.getLastVerifiedAt());
        r.setMethodologyNote(c.getMethodologyNote());
        return r;
    }

    private CategoryDetail categoryDetail(ContentCategory c, int count) {
        CategoryDetail d = new CategoryDetail();
        d.setSlug(c.getSlug());
        d.setName(c.getName());
        d.setDescription(c.getDescription());
        d.setSeoIntroCopy(c.getSeoIntroCopy());
        d.setMetaTitle(c.getMetaTitle());
        d.setMetaDescription(c.getMetaDescription());
        d.setPostCount(count);
        return d;
    }

    private SitemapEntry entry(String type, String slug, String path,
                               LocalDateTime lastmod, Integer postCount) {
        SitemapEntry e = new SitemapEntry();
        e.setType(type);
        e.setSlug(slug);
        e.setPath(path);
        e.setLastmod(lastmod);
        e.setPostCount(postCount);
        return e;
    }

    /**
     * Note the HashMap, and note that it is returned even when there is nothing
     * to look up.
     *
     * Map.of() is immutable, and immutable maps throw NullPointerException on
     * get(null) instead of returning null. Every caller here passes an optional
     * id — a draft has no author, an uncategorised post has no category — so a
     * null lookup is normal and must answer null, not throw.
     */
    private Map<Long, ContentAuthor> authorsByIds(List<Long> ids) {
        Map<Long, ContentAuthor> out = new HashMap<>();
        List<Long> clean = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (clean.isEmpty()) return out;
        authorRepository.findAllById(clean).forEach(a -> out.put(a.getId(), a));
        return out;
    }

    private Map<Long, ContentCategory> categoriesByIds(List<Long> ids) {
        Map<Long, ContentCategory> out = new HashMap<>();
        List<Long> clean = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (clean.isEmpty()) return out;
        categoryRepository.findAllById(clean).forEach(c -> out.put(c.getId(), c));
        return out;
    }

    private Map<Long, ContentMedia> mediaByIds(List<Long> ids) {
        Map<Long, ContentMedia> out = new HashMap<>();
        List<Long> clean = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (clean.isEmpty()) return out;
        mediaRepository.findByIdIn(clean).forEach(m -> out.put(m.getId(), m));
        return out;
    }

    private String mediaUrl(Long mediaId) {
        if (mediaId == null) return null;
        return mediaRepository.findById(mediaId).map(ContentMedia::getUrl).orElse(null);
    }

    private Map<Long, Integer> publishedCountPerCategory() {
        Map<Long, Integer> counts = new HashMap<>();
        for (Post p : postRepository.findByStatusOrderByPublishedAtDesc(PostStatus.PUBLISHED)) {
            if (p.getCategoryId() != null) counts.merge(p.getCategoryId(), 1, Integer::sum);
        }
        return counts;
    }

    private boolean isStale(Post p) {
        if (p.getReviewIntervalMonths() == null || p.getStatus() != PostStatus.PUBLISHED) return false;
        LocalDateTime base = p.getLastVerifiedAt() == null ? p.getPublishedAt() : p.getLastVerifiedAt();
        return base != null && base.plusMonths(p.getReviewIntervalMonths()).isBefore(LocalDateTime.now());
    }

    private SeoChecklistItem check(String key, String label, boolean passed,
                                   String detail, boolean blocking) {
        SeoChecklistItem i = new SeoChecklistItem();
        i.setKey(key);
        i.setLabel(label);
        i.setPassed(passed);
        i.setDetail(detail);
        i.setBlocking(blocking);
        return i;
    }

    private Object readJson(String json) {
        if (json == null || json.isBlank()) return null;
        try { return mapper.readTree(json); } catch (Exception e) { return null; }
    }

    private int intParam(Map<String, String> params, String key, int fallback) {
        try {
            String v = params.get(key);
            return v == null || v.isBlank() ? fallback : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}