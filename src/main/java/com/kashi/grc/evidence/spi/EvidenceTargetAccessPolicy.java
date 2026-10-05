package com.kashi.grc.evidence.spi;

/**
 * Who may read, attach to, and review the evidence on one target entity.
 *
 * ── THE PROBLEM THIS SOLVES ───────────────────────────────────────────────
 *
 * Evidence and documents are shared infrastructure: the same endpoints serve
 * assessments, issues, vendors and audit instances. They checked tenant and
 * nothing else, so once the audit module started deciding WHO works on a
 * control (assignment, delegation), anyone in the tenant could still upload to,
 * accept, reject or remove evidence on any control or test — the UI hid the
 * buttons, the server did not refuse the call.
 *
 * ── WHY A POLICY AND NOT AN IF IN THE EVIDENCE MODULE ─────────────────────
 *
 * The evidence module does not know what an audit control is, and should not.
 * Same shape as NotificationRouteContributor: the shared component asks, and
 * the module that owns the entity type answers. An entity type nobody claims
 * keeps exactly today's behaviour (tenant check only), so modules adopt this
 * one at a time and nothing outside the claiming module changes.
 *
 * ── RULES FOR AN IMPLEMENTATION ───────────────────────────────────────────
 *
 *   • Throw a BusinessException (403) to refuse; return normally to allow.
 *   • Only ever called for types {@link #supports} returned true for.
 *   • Called on the request path of user actions only — never from the reuse
 *     engine or other system propagation, which is not user work.
 */
public interface EvidenceTargetAccessPolicy {

    /** Does this policy own that target entity type? */
    boolean supports(String entityType);

    /** Viewing the evidence / documents attached to the target. */
    void requireReadable(String entityType, Long entityId);

    /** Uploading, linking or removing evidence on the target. */
    void requireCanAttach(String entityType, Long entityId, Long userId);

    /** Accepting or rejecting an evidence link on the target. */
    void requireCanReview(String entityType, Long entityId, Long userId);
}