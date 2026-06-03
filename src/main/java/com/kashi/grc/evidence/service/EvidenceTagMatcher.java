package com.kashi.grc.evidence.service;

import java.util.List;

/**
 * EvidenceTagMatcher — SPI for cross-module evidence propagation.
 *
 * Any module that wants to participate in evidence reuse implements this interface.
 * Spring discovers all implementations via @Component scanning.
 * The EvidenceReuseEngine calls all registered matchers — no engine changes needed
 * when a new module (Policy, Risk, Vendor) is added.
 *
 * ── EXISTING IMPLEMENTATIONS ──────────────────────────────────────────────────
 *   AuditControlEvidenceMatcher         — matches AuditControlInstance.controlTagSnapshot
 *   AssessmentQuestionEvidenceMatcher   — matches AssessmentQuestionInstance.questionTagSnapshot
 *
 * ── HOW TO ADD A NEW MODULE ──────────────────────────────────────────────────
 * 1. Create a class implementing EvidenceTagMatcher in your module's package
 * 2. Annotate it with @Component
 * 3. In findMatches(), query your module's instance table for matching tags
 * 4. Return MatchResult for each match with entityType, entityId, responsibleUserId
 * No other changes needed — the engine discovers it automatically.
 *
 * Example for a future Policy module:
 *
 * @Component
 * @RequiredArgsConstructor
 * public class PolicyControlEvidenceMatcher implements EvidenceTagMatcher {
 *     private final PolicyControlInstanceRepository repo;
 *
 *     @Override
 *     public List<MatchResult> findMatches(String tag, Long tenantId) {
 *         return repo.findByTenantIdAndControlTagSnapshot(tenantId, tag).stream()
 *             .map(c -> new MatchResult(
 *                 "POLICY_CONTROL_INSTANCE",
 *                 c.getId(),
 *                 c.getAssignedOwnerId()
 *             )).toList();
 *     }
 * }
 */
public interface EvidenceTagMatcher {

    /**
     * Find all entity instances in this module that match the given controlTag.
     *
     * @param tag      the controlTag from the EvidenceRecord (e.g. "ENCRYPTION_AT_REST")
     * @param tenantId the current tenant — implementations must filter by tenant
     * @return list of match results, one per entity instance that should be linked
     */
    List<MatchResult> findMatches(String tag, Long tenantId);

    /**
     * One match result — one EvidenceLink will be created.
     *
     * @param entityType         the polymorphic entity type string (e.g. "POLICY_CONTROL_INSTANCE")
     * @param entityId           the entity's ID
     * @param responsibleUserId  user to notify (auditor, reviewer, owner) — may be null
     */
    record MatchResult(
        String entityType,
        Long   entityId,
        Long   responsibleUserId
    ) {}
}
