package com.kashi.grc.audit.resolver;

import com.kashi.grc.audit.repository.AuditTestInstanceRepository;
import com.kashi.grc.evidence.service.EvidenceTagResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * KashiLink — resolves the control tag for an audit test instance.
 *
 * Work papers uploaded against a test become reusable evidence under the same
 * tag namespace as controls, so a sampling sheet filed on one test can satisfy
 * the equivalent test in another engagement.
 */
@Component
@RequiredArgsConstructor
public class AuditTestTagResolver implements EvidenceTagResolver {

    private final AuditTestInstanceRepository repo;

    @Override
    public String entityType() {
        return "AUDIT_TEST_INSTANCE";
    }

    @Override
    public String resolveTag(Long entityId, Long tenantId) {
        return repo.findById(entityId)
                .filter(t -> tenantId.equals(t.getTenantId()))
                .map(t -> t.getControlTagSnapshot())
                .orElse(null);
    }

    @Override
    public String resolveLabel(Long entityId, Long tenantId) {
        return repo.findById(entityId)
                .filter(t -> tenantId.equals(t.getTenantId()))
                .map(t -> t.getTestRefSnapshot())
                .orElse(null);
    }
}