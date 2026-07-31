package com.kashi.grc.audit.resolver;

import com.kashi.grc.audit.repository.AuditControlInstanceRepository;
import com.kashi.grc.evidence.service.EvidenceTagResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * KashiLink — resolves the control tag for an audit control instance.
 *
 * Called when a document is attached to AUDIT_CONTROL_INSTANCE. The instance
 * already carries control_tag_snapshot (frozen at engagement instantiation), so
 * the tag never has to travel through the UI and can never be forgotten or
 * mistyped at upload time.
 */
@Component
@RequiredArgsConstructor
public class AuditControlTagResolver implements EvidenceTagResolver {

    private final AuditControlInstanceRepository repo;

    @Override
    public String entityType() {
        return "AUDIT_CONTROL_INSTANCE";
    }

    @Override
    public String resolveTag(Long entityId, Long tenantId) {
        return repo.findById(entityId)
                .filter(c -> tenantId.equals(c.getTenantId()))
                .map(c -> c.getControlTagSnapshot())
                .orElse(null);
    }

    @Override
    public String resolveLabel(Long entityId, Long tenantId) {
        return repo.findById(entityId)
                .filter(c -> tenantId.equals(c.getTenantId()))
                .map(c -> c.getControlCodeSnapshot())
                .orElse(null);
    }
}