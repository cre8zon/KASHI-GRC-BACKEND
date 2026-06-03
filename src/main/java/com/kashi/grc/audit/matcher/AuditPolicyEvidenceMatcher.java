package com.kashi.grc.audit.matcher;
import com.kashi.grc.audit.domain.AuditPolicyInstance;
import com.kashi.grc.audit.repository.AuditPolicyInstanceRepository;
import com.kashi.grc.audit.domain.AuditTestInstance;
import com.kashi.grc.audit.repository.AuditTestInstanceRepository;
import com.kashi.grc.evidence.service.EvidenceTagMatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
/**
 * AuditPolicyEvidenceMatcher — links uploaded evidence to AuditPolicyInstance rows by tag.
 *
 * Implements EvidenceTagMatcher SPI.
 * Spring discovers this automatically — no EvidenceReuseEngine changes needed.
 *
 * When a policy PDF is uploaded with controlTag = 'ENCRYPTION_AT_REST':
 *   → This matcher finds all AuditPolicyInstance rows with controlTagsSnapshot containing
 *     'ENCRYPTION_AT_REST'
 *   → EvidenceReuseEngine creates EvidenceLink(AUDIT_POLICY_INSTANCE, id) for each
 *   → The policy document is linked to the policy instance in the engagement
 *
 * This is how Vanta links policy documents to controls automatically:
 *   Upload "Encryption Policy.pdf" → auto-links to all encryption-related controls.
 *
 * ── controlTagsSnapshot FORMAT ───────────────────────────────────────────────
 * AuditPolicy.controlTags is stored as comma-separated string:
 *   "ENCRYPTION_AT_REST,ENCRYPTION_IN_TRANSIT,KEY_MANAGEMENT"
 * This matcher uses LIKE search on the snapshot column.
 * For production: consider a separate audit_policy_tags join table for indexed lookups.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditPolicyEvidenceMatcher implements EvidenceTagMatcher {

    private final AuditPolicyInstanceRepository policyInstanceRepository;

    @Override
    public List<MatchResult> findMatches(String tag, Long tenantId) {
        if (tag == null || tag.isBlank()) return List.of();

        return policyInstanceRepository.findAll().stream()
                .filter(p -> tenantId.equals(p.getTenantId()))
                .filter(p -> containsTag(p.getControlTagsSnapshot(), tag))
                .map(p -> new MatchResult(
                        "AUDIT_POLICY_INSTANCE",
                        p.getId(),
                        p.getOwnerIdSnapshot()
                ))
                .toList();
    }

    /**
     * Check if a comma-separated tag list contains the given tag (case-insensitive).
     * e.g. containsTag("MFA,ENCRYPTION_AT_REST,ACCESS_MGMT", "MFA") → true
     */
    private boolean containsTag(String tagList, String searchTag) {
        if (tagList == null || tagList.isBlank()) return false;
        for (String t : tagList.split(",")) {
            if (searchTag.equalsIgnoreCase(t.trim())) return true;
        }
        return false;
    }
}
