package com.kashi.grc.content.service;

import com.kashi.grc.common.exception.ForbiddenException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.content.domain.ContentAuthor;
import com.kashi.grc.content.domain.ContentEnums.ContentRole;
import com.kashi.grc.content.domain.ContentEnums.PostStatus;
import com.kashi.grc.content.domain.Post;
import com.kashi.grc.content.repository.ContentAuthorRepository;
import com.kashi.grc.usermanagement.domain.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Who may do what to a post.
 *
 * ── WHY THIS EXISTS ──────────────────────────────────────────────────────────
 *
 * The security filter already keeps customer tenants out of /v1/content/admin
 * entirely — that is the outer gate and it is correct. But inside the platform
 * team it was wide open: any authenticated platform user could open, edit,
 * archive or revert any other person's draft. On a two-person team that is
 * invisible. On a team with a contractor writing one article it is a problem,
 * and it is the kind of problem that is embarrassing to discover rather than
 * expensive to prevent.
 *
 * ── THE RULE ─────────────────────────────────────────────────────────────────
 *
 *   CONTENT_ADMIN      everything
 *   CONTENT_EDITOR     everything — an editor's job is other people's drafts
 *   CONTENT_AUTHOR     their own posts, at any status
 *   CONTENT_CONTRIBUTOR their own posts, and only while unpublished
 *
 * Ownership is by ContentAuthor, not by User, because an author is not always a
 * platform login — an external contributor has a profile and a byline without an
 * account. Post.authorId points at ContentAuthor.id, and ContentAuthor.userId
 * links back to a User when one exists.
 *
 * ── PUBLISHED POSTS ──────────────────────────────────────────────────────────
 *
 * A CONTRIBUTOR loses edit rights once their post is live. That is deliberate:
 * publishing is an editorial decision, and someone who cannot make that decision
 * should not be able to silently rewrite what was decided. They can still read
 * it, and they can be given a new draft to revise.
 *
 * ── THE PLATFORM FLOOR, AND THE DEADLOCK IT FIXES ────────────────────────────
 *
 * The first cut of this class read the editorial role ONLY from a ContentAuthor
 * row. That deadlocks on an empty database: creating an author profile needs
 * canManageTaxonomy(), which needs a role, which needs a profile, and nobody can
 * create the first one. The module was unusable, and the error told you to ask
 * an administrator — who was the person reading it.
 *
 * So a platform user carrying `system:write` is treated as CONTENT_ADMIN. Not as
 * a special case bolted onto each check, but as a FLOOR under the explicit role:
 * the effective role is whichever of the two is stronger.
 *
 * Two consequences, both of which were bugs in the first cut:
 *
 *   - Giving a platform admin a ContentAuthor profile cannot take powers away
 *     from them. If the explicit role won outright, setting an admin's byline to
 *     CONTENT_AUTHOR would quietly demote them and the only way back would be
 *     SQL.
 *   - The floor is a PERMISSION, not a role name. A new platform role carrying
 *     system:write inherits this automatically; one that does not, does not.
 *     Naming PLATFORM_ADMIN here would mean editing Java every time the role
 *     model changes.
 *
 * SecurityConfig already restricts all of /v1/content/admin to SIDE_SYSTEM, so
 * this is a second, narrower question asked inside a boundary that has already
 * been enforced — not the boundary itself.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentAccessService {

    private final ContentAuthorRepository authorRepository;
    private final UtilityService utilityService;

    /* ── identity ─────────────────────────────────────────────────────────── */

    /**
     * The ContentAuthor profile for the signed-in user, if there is one.
     * Absent for a platform admin who has never been given a byline — which is
     * fine, because that person is authorised by role rather than ownership.
     */
    @Transactional(readOnly = true)
    public Optional<ContentAuthor> currentAuthor() {
        User user = utilityService.getLoggedInDataContext();
        if (user == null || user.getId() == null) return Optional.empty();
        return authorRepository.findByUserIdAndActiveTrue(user.getId());
    }

    /** Platform permission that grants the CONTENT_ADMIN floor. */
    private static final String PLATFORM_WRITE = "system:write";

    /**
     * Does the signed-in principal hold the platform write permission?
     *
     * Read from the SecurityContext rather than by reloading the User and
     * walking its roles: the authorities were already materialised by
     * CustomUserDetailsService when the JWT was validated, so this is a set
     * lookup instead of a lazy-loaded join on every access check — and access is
     * checked several times per request.
     */
    private boolean hasPlatformWrite() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return false;
        for (GrantedAuthority granted : auth.getAuthorities()) {
            if (PLATFORM_WRITE.equals(granted.getAuthority())) return true;
        }
        return false;
    }

    /**
     * The effective editorial role: the stronger of the explicit ContentAuthor
     * role and the platform floor. Null only for a platform user with neither —
     * someone inside /v1/content/admin with read-only standing.
     */
    @Transactional(readOnly = true)
    public ContentRole currentRole() {
        ContentRole explicit = currentAuthor().map(ContentAuthor::getContentRole).orElse(null);
        if (hasPlatformWrite()) {
            // Floor, not override. An explicit ADMIN stays ADMIN; an explicit
            // CONTRIBUTOR on an account with system:write is lifted, because the
            // platform permission is the broader grant of the two.
            return isPrivileged(explicit) ? explicit : ContentRole.CONTENT_ADMIN;
        }
        return explicit;
    }

    private boolean isPrivileged(ContentRole role) {
        return role == ContentRole.CONTENT_ADMIN || role == ContentRole.CONTENT_EDITOR;
    }

    /* ── decisions ────────────────────────────────────────────────────────── */

    /** Can the current user open this post in the editor at all? */
    @Transactional(readOnly = true)
    public boolean canView(Post post) {
        if (post == null) return false;
        // currentRole(), not me.getContentRole() — the floor has to apply here
        // too, or an admin with no byline cannot open the post they just made.
        if (isPrivileged(currentRole())) return true;
        ContentAuthor me = currentAuthor().orElse(null);
        return me != null && sameId(post.getAuthorId(), me.getId());
    }

    /** Can the current user change this post's content or metadata? */
    @Transactional(readOnly = true)
    public boolean canEdit(Post post) {
        if (post == null) return false;
        if (isPrivileged(currentRole())) return true;

        ContentAuthor me = currentAuthor().orElse(null);
        if (me == null) return false;
        if (!sameId(post.getAuthorId(), me.getId())) return false;

        // A contributor may not edit their own work once it is live.
        if (me.getContentRole() == ContentRole.CONTENT_CONTRIBUTOR) {
            return post.getStatus() != PostStatus.PUBLISHED;
        }
        return true;
    }

    /** Publishing, unpublishing and scheduling are an editorial act. */
    @Transactional(readOnly = true)
    public boolean canPublish() {
        ContentRole role = currentRole();
        return role != null && role.canPublish();
    }

    /** Categories, tags, author profiles and redirects are shared furniture. */
    @Transactional(readOnly = true)
    public boolean canManageTaxonomy() {
        return isPrivileged(currentRole());
    }

    /* ── assertions, for controllers ──────────────────────────────────────── */

    public void assertCanView(Post post) {
        if (!canView(post)) {
            throw new ForbiddenException("You do not have access to this post.");
        }
    }

    public void assertCanEdit(Post post) {
        if (canView(post) && !canEdit(post)) {
            // Distinguish "not yours" from "yours, but published" — the second
            // is actionable ("ask an editor") and the first is not.
            throw new ForbiddenException(
                    "This post is published. Ask an editor to unpublish it before editing.");
        }
        if (!canEdit(post)) {
            throw new ForbiddenException("You do not have access to this post.");
        }
    }

    public void assertCanPublish() {
        if (!canPublish()) {
            throw new ForbiddenException(
                    "Publishing requires an editor or administrator role.");
        }
    }

    public void assertCanManageTaxonomy() {
        if (!canManageTaxonomy()) {
            throw new ForbiddenException(
                    "Managing categories, tags and authors requires an editor or administrator role.");
        }
    }

    /**
     * The author id a new post should carry. A privileged user may write on
     * behalf of someone else by passing an explicit id; everyone else is pinned
     * to their own profile regardless of what the request body claims.
     */
    @Transactional(readOnly = true)
    public Long resolveAuthorIdForCreate(Long requestedAuthorId) {
        ContentAuthor me = currentAuthor().orElse(null);
        boolean privileged = isPrivileged(currentRole());

        if (requestedAuthorId != null) {
            if (me != null && sameId(requestedAuthorId, me.getId())) return requestedAuthorId;
            if (!privileged) {
                throw new ForbiddenException("You can only create posts under your own byline.");
            }
            if (!authorRepository.existsById(requestedAuthorId)) {
                throw new ResourceNotFoundException("ContentAuthor", requestedAuthorId);
            }
            return requestedAuthorId;
        }

        if (me != null) return me.getId();

        // No profile and no byline requested. For a privileged user this is a
        // draft with no author yet, which is a normal state — the byline is
        // chosen in Settings before publishing, and PublishService already
        // refuses to publish without one. Blocking creation here is what caused
        // the deadlock: the admin could not write the first post, and could not
        // reach the screen that creates author profiles either.
        if (privileged) return null;

        throw new ForbiddenException(
                "You need an author profile before you can write. Ask an administrator to create one.");
    }

    /**
     * Whether a change of byline on an existing post is allowed. Reassigning an
     * article to another author is an editorial act — it changes who is
     * accountable for it, and on a published post it changes the JSON-LD.
     */
    @Transactional(readOnly = true)
    public void assertCanReassign(Post post, Long newAuthorId) {
        if (newAuthorId == null || sameId(post.getAuthorId(), newAuthorId)) return;
        if (!isPrivileged(currentRole())) {
            throw new ForbiddenException("Only an editor can change a post's author.");
        }
        if (!authorRepository.existsById(newAuthorId)) {
            throw new ResourceNotFoundException("ContentAuthor", newAuthorId);
        }
    }

    /** Null-safe id equality. A null on either side is never a match. */
    private static boolean sameId(Long a, Long b) {
        return a != null && a.equals(b);
    }
}