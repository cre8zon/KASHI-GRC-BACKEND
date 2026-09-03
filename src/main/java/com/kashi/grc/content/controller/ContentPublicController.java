package com.kashi.grc.content.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.dto.PaginatedResponse;
import com.kashi.grc.content.dto.PublicDtos.*;
import com.kashi.grc.content.service.ContentQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The read surface for www.digiosec.com. No auth, no session, no tenant.
 *
 * ── WHO ACTUALLY CALLS THIS ──────────────────────────────────────────────────
 * Mostly a build script, not a browser. The public site prerenders every page,
 * so these endpoints are hit in a burst at deploy time and then barely at all.
 * That is why the cache headers are generous and why nothing here is optimised
 * for a hot path that does not exist.
 *
 * The exceptions are the two writes at the bottom — view and helpful — which
 * come from real browsers on every article read.
 *
 * ── CACHE HEADERS ARE THE PERFORMANCE STORY ──────────────────────────────────
 * A CDN in front of this makes the difference between sub-second TTFB and not.
 * Every read below is public and cacheable; the values are short enough that a
 * republish shows up within minutes even without a purge.
 */
@Slf4j
@RestController
@RequestMapping("/v1/content/public")
@RequiredArgsConstructor
public class ContentPublicController {

    private static final CacheControl LIST_CACHE =
            CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic();
    private static final CacheControl DETAIL_CACHE =
            CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic();
    /** Longer: the sitemap is read by the build, and a stale one costs one deploy. */
    private static final CacheControl BUILD_CACHE =
            CacheControl.maxAge(Duration.ofMinutes(1)).cachePublic();

    private final ContentQueryService queryService;

    @GetMapping("/posts")
    public ResponseEntity<ApiResponse<PaginatedResponse<PostCard>>> listPosts(
            @RequestParam Map<String, String> params) {
        return ResponseEntity.ok().cacheControl(LIST_CACHE)
                .body(ApiResponse.success(queryService.listPublished(params)));
    }

    /**
     * 404 unless PUBLISHED. A draft reachable by guessing its slug is a leak,
     * and slugs are guessable by design.
     */
    @GetMapping("/posts/{slug}")
    public ResponseEntity<ApiResponse<PostDetail>> getPost(@PathVariable String slug) {
        return ResponseEntity.ok().cacheControl(DETAIL_CACHE)
                .body(ApiResponse.success(queryService.getPublishedPost(slug)));
    }

    @GetMapping("/categories")
    public ResponseEntity<ApiResponse<List<CategoryDetail>>> listCategories() {
        return ResponseEntity.ok().cacheControl(LIST_CACHE)
                .body(ApiResponse.success(queryService.listCategories()));
    }

    @GetMapping("/categories/{slug}")
    public ResponseEntity<ApiResponse<CategoryDetail>> getCategory(@PathVariable String slug) {
        return ResponseEntity.ok().cacheControl(LIST_CACHE)
                .body(ApiResponse.success(queryService.getCategory(slug)));
    }

    @GetMapping("/tags")
    public ResponseEntity<ApiResponse<List<CategoryDetail>>> listTags() {
        return ResponseEntity.ok().cacheControl(LIST_CACHE)
                .body(ApiResponse.success(queryService.listTags()));
    }

    @GetMapping("/tags/{slug}")
    public ResponseEntity<ApiResponse<CategoryDetail>> getTag(@PathVariable String slug) {
        return ResponseEntity.ok().cacheControl(LIST_CACHE)
                .body(ApiResponse.success(queryService.getTag(slug)));
    }

    @GetMapping("/authors")
    public ResponseEntity<ApiResponse<List<AuthorRef>>> listAuthors() {
        return ResponseEntity.ok().cacheControl(LIST_CACHE)
                .body(ApiResponse.success(queryService.listAuthors()));
    }

    @GetMapping("/authors/{slug}")
    public ResponseEntity<ApiResponse<AuthorDetail>> getAuthor(@PathVariable String slug) {
        return ResponseEntity.ok().cacheControl(DETAIL_CACHE)
                .body(ApiResponse.success(queryService.getAuthor(slug)));
    }

    /**
     * Every URL the site should build, with lastmod and the post counts the
     * prerenderer needs for listing pagination. One call, because the build
     * needs the whole picture before it renders anything.
     */
    @GetMapping("/sitemap-data")
    public ResponseEntity<ApiResponse<SitemapData>> sitemapData() {
        return ResponseEntity.ok().cacheControl(BUILD_CACHE)
                .body(ApiResponse.success(queryService.sitemapData()));
    }

    /** Compiled into host redirect rules at build time. */
    @GetMapping("/redirects")
    public ResponseEntity<ApiResponse<List<RedirectRule>>> redirects() {
        return ResponseEntity.ok().cacheControl(BUILD_CACHE)
                .body(ApiResponse.success(queryService.activeRedirects()));
    }

    // ── the two writes ───────────────────────────────────────────────────────

    /**
     * Fire-and-forget view count. 204, no body, no read.
     *
     * The front end sends this with sendBeacon, which cannot read a response and
     * fires during page unload. Returning anything would be wasted bytes, and
     * doing the increment synchronously would put a database write on the
     * critical path of every article render.
     */
    @PostMapping("/posts/{slug}/view")
    public ResponseEntity<Void> recordView(@PathVariable String slug) {
        queryService.recordViewAsync(slug);
        return ResponseEntity.noContent().build();
    }

    /**
     * "Was this helpful?"
     *
     * Anonymous and unauthenticated, so it is trivially spammable — which is
     * fine, because it is a directional signal for the content team and not a
     * metric anyone should report on. Rate limiting belongs at the CDN, not
     * here, and pretending otherwise would add code that provides no real
     * protection.
     */
    @PostMapping("/posts/{slug}/helpful")
    public ResponseEntity<Void> recordHelpful(@PathVariable String slug,
                                              @RequestBody Map<String, Boolean> body) {
        queryService.recordHelpful(slug, Boolean.TRUE.equals(body.get("helpful")));
        return ResponseEntity.noContent().build();
    }

    /** Double opt-in. Nothing is sent until the confirmation link is followed. */
    @PostMapping("/newsletter/subscribe")
    public ResponseEntity<ApiResponse<Map<String, String>>> subscribe(
            @RequestBody Map<String, String> body) {
        queryService.requestSubscription(body.get("email"), body.get("name"), body.get("sourcePath"));
        // Same response whether or not the address was already on the list —
        // otherwise this endpoint tells anyone who asks who is subscribed.
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("message", "Check your inbox for a confirmation link.")));
    }

    @GetMapping("/newsletter/confirm")
    public ResponseEntity<ApiResponse<Map<String, String>>> confirm(@RequestParam String token) {
        queryService.confirmSubscription(token);
        return ResponseEntity.ok(ApiResponse.success(Map.of("message", "Confirmed.")));
    }
}
