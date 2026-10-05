package com.kashi.grc.notification.spi;

/**
 * Where a notification about one entity opens.
 *
 * ── THE PROBLEM THIS SOLVES ───────────────────────────────────────────────
 *
 * notifications.action_url has existed as a VARCHAR(500) since the table was
 * created and nothing ever wrote to it. NotificationsPage ignored it entirely
 * and fell through to a hardcoded switch — the FOURTH copy of route resolution
 * in the product, after ui_navigation, action_items.nav_context and
 * lib/inboxRoute.js — and the least correct of them:
 *
 *     QUESTION_RESPONSE  →  /action-items?highlight…   a list, never the question
 *     ASSESSMENT         →  /assessments/{id}          the hardcoded page
 *     TASK               →  /workflow/inbox            a list, never the task
 *
 * So nothing arriving by notification opened the thing it was about.
 *
 * ── WHY A CONTRIBUTOR AND NOT A PARAMETER ─────────────────────────────────
 *
 * The obvious fix is an actionUrl argument on NotificationService.send. There
 * are 55 call sites across 19 files, in every module in the product, and each
 * would have to learn how to build a route — which is the same drift in a new
 * place. Worse, most of them do not have what the route needs: a notification
 * about a question instance carries the QUESTION's id, and the route needs the
 * ASSESSMENT's.
 *
 * So the notification module asks instead of being told, and each module
 * answers for the entity types it owns. Same shape as
 * AssessmentGuardFindingListener and ActionItemCreatedEvent: the shared
 * component publishes the question, the module that cares subscribes. No call
 * site changes, in any module, ever.
 *
 * ── ADDING A MODULE ───────────────────────────────────────────────────────
 *
 * Write one @Component implementing this. Nothing else. A module with no
 * contributor keeps the client-side fallback it has today, so this can be
 * adopted one module at a time and a half-adopted state is not a broken one.
 *
 * ── RULES FOR AN IMPLEMENTATION ───────────────────────────────────────────
 *
 *   • Depend on REPOSITORIES, never on services. NotificationService is
 *     injected into most services in the product, so a contributor that
 *     depends on one creates a cycle. It is injected here through an
 *     ObjectProvider for the same reason, but that is a safety net, not a
 *     licence.
 *   • Return null rather than guessing. A null leaves action_url unset and the
 *     client falls back, which is a worse route but never a WRONG record — and
 *     a URL that opens the wrong record is the failure nobody reports because
 *     it looks like it worked.
 *   • Never throw. routeFor runs inside the caller's transaction, on the path
 *     that saves the notification; an exception here would lose the
 *     notification over a cosmetic concern. NotificationService catches, but
 *     do not rely on it.
 */
public interface NotificationRouteContributor {

    /**
     * Does this contributor own that entity type? Called before routeFor, so a
     * contributor never has to defend against types it does not know.
     */
    boolean supports(String entityType);

    /**
     * The route, or null when it cannot be resolved with certainty.
     *
     * @param entityType what the notification is about
     * @param entityId   its id — note this is the entity's OWN id, which is
     *                   often not the id the route needs
     * @param type       the notification type ('REMEDIATION_REQUESTED',
     *                   'COMMENT_ADDED', …). Lets one entity type resolve to
     *                   different screens for different events.
     * @param userId     the RECIPIENT. Two people are notified about the same
     *                   question for opposite reasons — the vendor to answer
     *                   it, the organisation to evaluate it — and they do not
     *                   belong on the same tab.
     */
    String routeFor(String entityType, Long entityId, String type, Long userId);
}