package com.kashi.grc.audit.matcher;

import com.kashi.grc.audit.domain.AuditTestInstance;
import com.kashi.grc.audit.repository.AuditTestInstanceRepository;
import com.kashi.grc.evidence.service.EvidenceTagMatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AuditTestEvidenceMatcher — links uploaded evidence to AuditTestInstance rows.
 *
 * Implements EvidenceTagMatcher SPI — discovered by EvidenceReuseEngine automatically.
 *
 * ── MATCHING STRATEGY ──────────────────────────────────────────────────────────
 *
 * MANUAL / HYBRID tests (automationTypeSnapshot != "AUTOMATED"):
 *   Match by controlTagSnapshot = tag. This is correct — a human uploading evidence
 *   tagged "MFA_ADMIN" should link to all manual MFA_ADMIN test instances, since
 *   the same document may cover multiple controls in the same domain.
 *
 * AUTOMATED tests (automationTypeSnapshot = "AUTOMATED"):
 *   EXCLUDED from this matcher entirely. Automated test instances are fed results
 *   by EngagementIntegrationSnapshotService.recordResult() which uses the precise
 *   checkKey → testInstanceId mapping established at engagement snapshot time.
 *
 *   If automated tests were included here, every EvidenceRecord produced by an
 *   integration check would propagate to ALL automated tests sharing the same
 *   controlTagSnapshot across ALL engagements — e.g. an Okta MFA check result
 *   would incorrectly mark AWS MFA tests as PASS because they share the
 *   "MFA_ADMIN" tag.
 *
 * ── FIX FROM PREVIOUS VERSION ──────────────────────────────────────────────────
 * The original implementation called findAll() + in-memory filter (full table scan)
 * AND included automated tests in the results, causing both a performance bug and
 * a correctness bug. Both are fixed here.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditTestEvidenceMatcher implements EvidenceTagMatcher {

    private final AuditTestInstanceRepository testInstanceRepository;

    @Override
    public List<MatchResult> findMatches(String tag, Long tenantId) {
        if (tag == null || tag.isBlank()) return List.of();

        // Phase 3: membership match on the frozen expanded set, with legacy
        // exact-match fallback. AUTOMATED tests are excluded IN THE QUERY — they
        // route by checkKey via EngagementIntegrationSnapshotService, never by
        // tag, so a shared tag must never cross-contaminate them.
        List<AuditTestInstance> candidates =
                testInstanceRepository.findManualByTenantAndExpandedTag(tenantId, tag.toUpperCase());

        return candidates.stream()
                .map(t -> new MatchResult(
                        "AUDIT_TEST_INSTANCE",
                        t.getId(),
                        t.getRunByUserId()
                ))
                .toList();
    }
}