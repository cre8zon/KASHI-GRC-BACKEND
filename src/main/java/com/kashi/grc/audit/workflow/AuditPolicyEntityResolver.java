package com.kashi.grc.audit.workflow;

import com.kashi.grc.workflow.domain.WorkflowInstance;
import com.kashi.grc.workflow.spi.WorkflowEntityResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * AuditPolicyEntityResolver — resolves artifactId for AUDIT_POLICY workflow instances.
 * entityId = policyId directly. Route: /module/audit_policy/{policyId}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditPolicyEntityResolver implements WorkflowEntityResolver {

    @Override
    public String entityType() {
        return "AUDIT_POLICY";
    }

    @Override
    public Long resolveArtifactId(WorkflowInstance instance) {
        return instance.getEntityId();
    }
}