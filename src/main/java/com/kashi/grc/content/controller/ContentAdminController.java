package com.kashi.grc.content.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.dto.PaginatedResponse;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.content.domain.*;
import com.kashi.grc.content.dto.PostDtos.*;
import com.kashi.grc.content.repository.PostRepository;
import com.kashi.grc.content.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * The editor's surface.
 *
 * ── ON AUTHORISATION ─────────────────────────────────────────────────────────
 * SecurityConfig gates /v1/content/admin/** to SIDE_SYSTEM, matching the
 * existing platform-admin boundary: content is platform-owned, so no
 * customer-tenant user has any business here. That is the real gate, at the
 * filter, before any controller — a new endpoint added below is covered
 * automatically rather than depending on someone remembering an annotation.
 *
 * The @PreAuthorize on the publish endpoints is a second, narrower check:
 * within the platform team, not everyone who can write should be able to
 * publish. See ContentEnums.ContentRole for why that axis is separate from
 * platform RBAC.
 */
@Slf4j
@RestController
@RequestMapping("/v1/content/admin")
@RequiredArgsConstructor
public class ContentAdminController {

    private final PostService postService;
    private final PublishService publishService;
    private final ContentQueryService queryService;
    private final MediaService mediaService;
    private final RedirectService redirectService;
    private final ContentInsightsService insightsService;
    private final ContentTaxonomyService taxonomyService;
    private final ContentAccessService accessService;
    private final PostRepository postRepository;

    /**
     * Load a post or 404. Every endpoint below that takes an id goes through
     * here and then through an accessService assertion, so ownership is one
     * decision made in one place rather than a rule each handler remembers.
     */
    private Post requirePost(Long id) {
        return postRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Post", id));
    }

    // ── posts ────────────────────────────────────────────────────────────────

    @GetMapping("/posts")
    public ApiResponse<PaginatedResponse<PostListItem>> list(@RequestParam Map<String, String> params) {
        return ApiResponse.success(queryService.adminList(params));
    }

    @PostMapping("/posts")
    public ApiResponse<Post> create(@RequestBody PostUpsertRequest req) {
        // Never trust authorId from the body: a contributor is pinned to their
        // own byline, an editor may write on someone else's behalf.
        req.setAuthorId(accessService.resolveAuthorIdForCreate(req.getAuthorId()));
        return ApiResponse.success(postService.create(req));
    }

    @GetMapping("/posts/{id}")
    public ApiResponse<Post> get(@PathVariable Long id) {
        accessService.assertCanView(requirePost(id));
        return ApiResponse.success(postService.require(id));
    }

    /**
     * Autosave lands here every two seconds while someone types.
     *
     * It is safe to call that often because PostService gates everything
     * expensive — the revision, the contentUpdatedAt bump, the link reindex —
     * behind a block-hash comparison. An identical save does almost nothing.
     */
    @PutMapping("/posts/{id}")
    public ApiResponse<Post> update(@PathVariable Long id, @RequestBody PostUpsertRequest req) {
        Post existing = requirePost(id);
        accessService.assertCanEdit(existing);
        accessService.assertCanReassign(existing, req.getAuthorId());
        return ApiResponse.success(postService.update(id, req));
    }

    /** Soft — sets ARCHIVED and, for a published post, leaves a redirect behind. */
    @DeleteMapping("/posts/{id}")
    public ApiResponse<Void> archive(@PathVariable Long id,
                                     @RequestParam(required = false) String redirectTo) {
        accessService.assertCanEdit(requirePost(id));
        postService.archive(id, redirectTo);
        return ApiResponse.success();
    }

    /**
     * Dry run of publish validation, for the editor's checklist.
     *
     * Separate from POST /publish so the panel can show what is missing while
     * someone is still writing, without them having to click publish and be
     * refused. Same code path, so the two can never disagree.
     */
    @GetMapping("/posts/{id}/publish-check")
    public ApiResponse<List<PublishService.Problem>> publishCheck(@PathVariable Long id) {
        accessService.assertCanView(requirePost(id));
        return ApiResponse.success(publishService.validate(postService.require(id)));
    }

    @PostMapping("/posts/{id}/publish")
    @PreAuthorize("hasAuthority('SIDE_SYSTEM')")
    public ApiResponse<Post> publish(@PathVariable Long id) {
        // SIDE_SYSTEM is the platform role; this is the editorial one. A
        // platform admin holding a CONTRIBUTOR profile must not publish.
        accessService.assertCanPublish();
        return ApiResponse.success(publishService.publish(postService.require(id), null));
    }

    @PostMapping("/posts/{id}/unpublish")
    @PreAuthorize("hasAuthority('SIDE_SYSTEM')")
    public ApiResponse<Post> unpublish(@PathVariable Long id) {
        // SIDE_SYSTEM is the platform role; this is the editorial one. A
        // platform admin holding a CONTRIBUTOR profile must not publish.
        accessService.assertCanPublish();
        return ApiResponse.success(publishService.unpublish(postService.require(id), null));
    }

    @PostMapping("/posts/{id}/schedule")
    @PreAuthorize("hasAuthority('SIDE_SYSTEM')")
    public ApiResponse<Post> schedule(@PathVariable Long id, @RequestBody Map<String, String> body) {
        // SIDE_SYSTEM is the platform role; this is the editorial one. A
        // platform admin holding a CONTRIBUTOR profile must not publish.
        accessService.assertCanPublish();
        LocalDateTime when = LocalDateTime.parse(body.get("scheduledFor"));
        return ApiResponse.success(publishService.schedule(postService.require(id), when, null));
    }

    @GetMapping("/posts/{id}/revisions")
    public ApiResponse<List<PostRevision>> revisions(@PathVariable Long id) {
        accessService.assertCanView(requirePost(id));
        return ApiResponse.success(postService.revisions(id));
    }

    @PostMapping("/posts/{id}/revert/{revisionId}")
    public ApiResponse<Post> revert(@PathVariable Long id, @PathVariable Long revisionId) {
        accessService.assertCanEdit(requirePost(id));
        return ApiResponse.success(postService.revert(id, revisionId));
    }

    /** Live check as the slug field is typed. */
    @GetMapping("/posts/slug-available")
    public ApiResponse<SlugCheckResponse> slugAvailable(@RequestParam String slug,
                                                        @RequestParam(required = false) Long excludeId) {
        return ApiResponse.success(queryService.checkSlug(slug, excludeId));
    }

    /** Advisory on-page checks. Never blocking — see SeoChecklistItem. */
    @GetMapping("/posts/{id}/seo-checklist")
    public ApiResponse<List<SeoChecklistItem>> seoChecklist(@PathVariable Long id) {
        accessService.assertCanView(requirePost(id));
        return ApiResponse.success(queryService.seoChecklist(id));
    }

    // ── taxonomy ─────────────────────────────────────────────────────────────

    @GetMapping("/categories")
    public ApiResponse<List<ContentCategory>> categories() {
        return ApiResponse.success(taxonomyService.allCategories());
    }

    @PostMapping("/categories")
    public ApiResponse<ContentCategory> createCategory(@RequestBody ContentCategory c) {
        accessService.assertCanManageTaxonomy();
        return ApiResponse.success(taxonomyService.saveCategory(c));
    }

    @PutMapping("/categories/{id}")
    public ApiResponse<ContentCategory> updateCategory(@PathVariable Long id,
                                                       @RequestBody ContentCategory c) {
        accessService.assertCanManageTaxonomy();
        c.setId(id);
        return ApiResponse.success(taxonomyService.saveCategory(c));
    }

    @GetMapping("/tags")
    public ApiResponse<List<ContentTag>> tags() {
        return ApiResponse.success(taxonomyService.allTags());
    }

    @PostMapping("/tags")
    public ApiResponse<ContentTag> createTag(@RequestBody ContentTag t) {
        accessService.assertCanManageTaxonomy();
        return ApiResponse.success(taxonomyService.saveTag(t));
    }

    @GetMapping("/authors")
    public ApiResponse<List<ContentAuthor>> authors() {
        return ApiResponse.success(taxonomyService.allAuthors());
    }

    @PostMapping("/authors")
    public ApiResponse<ContentAuthor> createAuthor(@RequestBody ContentAuthor a) {
        accessService.assertCanManageTaxonomy();
        return ApiResponse.success(taxonomyService.saveAuthor(a));
    }

    @PutMapping("/authors/{id}")
    public ApiResponse<ContentAuthor> updateAuthor(@PathVariable Long id,
                                                   @RequestBody ContentAuthor a) {
        accessService.assertCanManageTaxonomy();
        a.setId(id);
        return ApiResponse.success(taxonomyService.saveAuthor(a));
    }

    // ── media ────────────────────────────────────────────────────────────────

    @GetMapping("/media")
    public ApiResponse<PaginatedResponse<ContentMedia>> media(@RequestParam Map<String, String> params) {
        return ApiResponse.success(mediaService.list(params));
    }

    /**
     * altText is a required part, not an optional field.
     *
     * Rejecting at upload rather than at publish is a deliberate choice about
     * when the friction lands: the person uploading knows what the image shows,
     * and asking them then costs five seconds. Asking at publish means asking
     * someone else, three weeks later, about an image they did not choose.
     */
    @PostMapping("/media/upload")
    public ApiResponse<ContentMedia> upload(@RequestPart("file") MultipartFile file,
                                            @RequestPart("altText") String altText,
                                            @RequestPart(value = "caption", required = false) String caption) {
        return ApiResponse.success(mediaService.upload(file, altText, caption));
    }

    @PutMapping("/media/{id}")
    public ApiResponse<ContentMedia> updateMedia(@PathVariable Long id,
                                                 @RequestBody Map<String, String> body) {
        return ApiResponse.success(mediaService.updateText(id, body.get("altText"), body.get("caption")));
    }

    // ── redirects ────────────────────────────────────────────────────────────

    @GetMapping("/redirects")
    public ApiResponse<List<ContentRedirect>> redirects() {
        return ApiResponse.success(redirectService.allActive());
    }

    @PostMapping("/redirects")
    public ApiResponse<ContentRedirect> createRedirect(@RequestBody Map<String, String> body) {
        accessService.assertCanManageTaxonomy();
        return ApiResponse.success(redirectService.create(
                body.get("fromPath"), body.get("toPath"),
                body.get("statusCode") == null ? 301 : Integer.parseInt(body.get("statusCode")),
                body.get("reason"), null));
    }

    // ── reports ──────────────────────────────────────────────────────────────

    /** Published, indexable, and nothing on the site links to it. */
    @GetMapping("/reports/orphans")
    public ApiResponse<List<Post>> orphans() {
        return ApiResponse.success(insightsService.orphanPages());
    }

    /** Past its review interval. Comparison pages first — they rot fastest. */
    @GetMapping("/reports/stale")
    public ApiResponse<List<Post>> stale() {
        return ApiResponse.success(insightsService.staleContent());
    }

    @GetMapping("/reports/broken-links")
    public ApiResponse<List<PostLink>> brokenLinks() {
        return ApiResponse.success(insightsService.brokenLinks());
    }
}