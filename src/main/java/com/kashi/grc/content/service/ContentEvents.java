package com.kashi.grc.content.service;

/**
 * Domain events for the content module.
 *
 * ── WHY EVENTS AND NOT DIRECT CALLS ──────────────────────────────────────────
 * PublishService could call the build hook and the tag recount directly. It
 * would work, and it would be wrong in one specific way: the build hook is an
 * outbound HTTP call to a third party, and firing it inside the publish
 * transaction means a static rebuild can be triggered for a publish that then
 * rolls back. The site would rebuild from a database state that never existed.
 *
 * Listeners bind with AFTER_COMMIT, so nothing outbound happens until the row
 * is durably PUBLISHED. It also keeps PublishService free of a dependency on
 * MailService, an HttpClient and the taxonomy service, none of which have
 * anything to do with deciding whether a post may go live.
 */
public final class ContentEvents {

    private ContentEvents() {}

    public record PostPublished(Long postId, String slug, boolean firstPublication) {}

    public record PostUnpublished(Long postId, String slug) {}

    /** Slug changed on a live post — the redirect exists, the site needs rebuilding. */
    public record PostUrlChanged(Long postId, String oldSlug, String newSlug) {}

    public record SubscriberConfirmationRequested(String email, String name, String token) {}
}