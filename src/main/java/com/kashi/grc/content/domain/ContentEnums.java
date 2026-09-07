package com.kashi.grc.content.domain;

/**
 * Every enum the content platform uses, in one file, for the same reason
 * AiEnums exists: these values appear in the database, in the public API and in
 * the admin UI, and keeping them adjacent makes it obvious when one of them is
 * about to grow a member the other two do not know about.
 *
 * ── WHY THE FULL SET SHIPS ON DAY ONE ────────────────────────────────────────
 * Phase 1 renders blog posts and nothing else. But content_type and status are
 * columns, and adding a value to a column is a migration while adding a branch
 * to a switch is an afternoon. The enums carry every value the roadmap needs;
 * the behaviour arrives per phase. That costs nothing now and saves a schema
 * change later.
 */
public final class ContentEnums {

    private ContentEnums() {}

    /**
     * What kind of page this is. Drives the public template, the JSON-LD type
     * and which admin fields are shown — not the storage, which is identical
     * for all of them.
     */
    public enum ContentType {
        BLOG,        // long-form article
        COMPARISON,  // "KashiGRC vs X" — reads ComparisonData
        GLOSSARY,    // definition-first, FAQ-heavy, short
        CASE_STUDY,  // challenge -> solution -> results
        CHANGELOG,   // dated product note, tagged by feature area
        PILLAR       // hub page aggregating a topic cluster
    }

    /**
     * IN_REVIEW and SCHEDULED are inert until the workflow is wired, but a post
     * that reaches them must not be publicly visible in the meantime. Every
     * public query filters on PUBLISHED explicitly rather than excluding DRAFT,
     * so a status added later is invisible by default rather than accidentally
     * live.
     */
    public enum PostStatus {
        DRAFT,
        IN_REVIEW,
        SCHEDULED,
        PUBLISHED,
        ARCHIVED
    }

    /**
     * Per-page robots directive. Needed from the start: a thin glossary stub or
     * a near-duplicate comparison page is worth publishing for internal linking
     * and worth keeping out of the index until it earns its place.
     */
    public enum RobotsDirective {
        INDEX_FOLLOW,
        NOINDEX_FOLLOW,
        NOINDEX_NOFOLLOW;

        /** The header value: INDEX_FOLLOW -> "index,follow". */
        public String toDirective() {
            return name().toLowerCase().replace('_', ',');
        }
    }

    /**
     * schema.org type emitted as JSON-LD. Defaulted from ContentType and
     * overridable per post, because a blog post that happens to be a
     * step-by-step guide should emit HowTo and only the author knows that.
     */
    public enum SchemaType {
        BlogPosting,
        Article,
        HowTo,
        FAQPage,
        DefinedTerm,
        Review,
        Product
    }

    /**
     * Editorial roles, separate from the platform's RBAC roles.
     *
     * A CONTRIBUTOR writes and submits; only an EDITOR or ADMIN publishes. This
     * is deliberately its own axis rather than reusing platform permissions: the
     * person who may publish a marketing article is not the person who may
     * administer a tenant, and conflating them would either lock writers out or
     * hand them the platform.
     */
    public enum ContentRole {
        CONTENT_ADMIN,
        CONTENT_EDITOR,
        CONTENT_AUTHOR,
        CONTENT_CONTRIBUTOR;

        public boolean canPublish() {
            return this == CONTENT_ADMIN || this == CONTENT_EDITOR;
        }
    }

    /**
     * Where a link found in a post's blocks points. The orphan-page report and
     * the broken-link checker both read this and ask different questions of it —
     * "does anything link here" versus "does this resolve".
     */
    public enum LinkKind {
        INTERNAL_POST,   // /blog/<slug> that resolves to a post
        INTERNAL_PAGE,   // any other same-site path
        EXTERNAL
    }

    /** Result of the periodic link check. UNCHECKED is the initial state. */
    public enum LinkHealth {
        UNCHECKED,
        OK,
        REDIRECTED,
        BROKEN
    }
}
