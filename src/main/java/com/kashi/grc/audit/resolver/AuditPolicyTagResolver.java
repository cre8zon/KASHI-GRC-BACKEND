package com.kashi.grc.audit.resolver;

import com.kashi.grc.audit.repository.AuditPolicyInstanceRepository;
import com.kashi.grc.evidence.service.EvidenceTagResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * KashiLink — resolves the control tag for an audit policy instance.
 *
 * Policy instances carry a CSV tag list (control_tags_snapshot), but an
 * EvidenceRecord holds a single tag. The FIRST tag becomes the record's
 * propagation key; the remaining tags still match on the way back in, because
 * AuditPolicyEvidenceMatcher splits the CSV when fanning out.
 *
 * If a policy genuinely needs to seed propagation on all of its tags, create one
 * evidence record per tag rather than widening EvidenceRecord.controlTag — the
 * single-tag shape is what keeps every matcher an indexed equality lookup.
 */
@Component
@RequiredArgsConstructor
public class AuditPolicyTagResolver implements EvidenceTagResolver {

    private final AuditPolicyInstanceRepository repo;

    @Override
    public String entityType() {
        return "AUDIT_POLICY_INSTANCE";
    }

    @Override
    public String resolveTag(Long entityId, Long tenantId) {
        return repo.findById(entityId)
                .filter(p -> tenantId.equals(p.getTenantId()))
                .map(p -> p.getControlTagsSnapshot())
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.split(",")[0].trim())
                .orElse(null);
    }
}