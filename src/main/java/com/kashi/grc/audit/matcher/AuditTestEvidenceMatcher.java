package com.kashi.grc.audit.matcher;
import com.kashi.grc.audit.domain.AuditTestInstance;
import com.kashi.grc.audit.repository.AuditTestInstanceRepository;
import com.kashi.grc.evidence.service.EvidenceTagMatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AuditTestEvidenceMatcher — links uploaded evidence to AuditTestInstance rows by tag.
 *
 * Implements EvidenceTagMatcher SPI.
 * Spring discovers this automatically — no EvidenceReuseEngine changes needed.
 *
 * When evidence is uploaded with controlTag = 'MFA':
 *   → This matcher queries AuditTestInstance.controlTagSnapshot = 'MFA'
 *   → Returns all matching test instances
 *   → EvidenceReuseEngine creates EvidenceLink(AUDIT_TEST_INSTANCE, id) for each
 *   → Status = PENDING_REVIEW → auditor must accept or reject
 *
 * After the auditor accepts the evidence for the test:
 *   → AuditTestInstance.testResult is set to PASS (by the auditor or reviewer)
 *   → AuditTestPolicySnapshotService.cascadeDeriveControlResults() is called
 *   → All linked controls are re-evaluated automatically
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditTestEvidenceMatcher implements EvidenceTagMatcher {

    private final AuditTestInstanceRepository testInstanceRepository;

    @Override
    public List<MatchResult> findMatches(String tag, Long tenantId) {
        if (tag == null || tag.isBlank()) return List.of();

        List<AuditTestInstance> matches =
                testInstanceRepository.findByEngagementIdAndControlTagSnapshot(null, tag);
        // Note: we search all test instances for this tenant by controlTagSnapshot
        // The repository method above filters by tag only — tenantId is enforced below

        return testInstanceRepository.findAll().stream()
                .filter(t -> tag.equalsIgnoreCase(t.getControlTagSnapshot()))
                .filter(t -> tenantId.equals(t.getTenantId()))
                .map(t -> new MatchResult(
                        "AUDIT_TEST_INSTANCE",
                        t.getId(),
                        t.getRunByUserId()  // responsible user (may be null for automated tests)
                ))
                .toList();
    }
}
