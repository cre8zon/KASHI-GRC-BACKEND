package com.kashi.grc.audit.workflow;

import com.kashi.grc.workflow.domain.WorkflowInstance;
import com.kashi.grc.workflow.spi.WorkflowEntityResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * AuditEngagementEntityResolver — tells the workflow engine how to resolve the
 * artifactId for AUDIT_ENGAGEMENT workflow instances.
 *
 * The workflow engine calls resolveArtifactId() to build the task's navRoute:
 *   nav.route.replace(':id', artifactId)  →  /module/audit_engagement/42
 *
 * For audit engagements, entityId IS the engagementId directly (no indirection),
 * so this resolver simply returns wi.getEntityId().
 *
 * This is simpler than VendorAssessmentEntityResolver which traverses
 *   WorkflowInstance → VendorAssessmentCycle → VendorAssessment.
 *
 * Must be registered as a Spring @Component — WorkflowEntityResolverRegistry
 * discovers all implementations via @Autowired List<WorkflowEntityResolver>.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditEngagementEntityResolver implements WorkflowEntityResolver {

    @Override
    public String entityType() {
        return "AUDIT_ENGAGEMENT";
    }

    @Override
    public Long resolveArtifactId(WorkflowInstance instance) {
        // entityId is set to engagementId by AuditEngagementService.startWorkflowIfConfigured()
        Long artifactId = instance.getEntityId();
        log.debug("[AUDIT-ENG-RESOLVER] entityId={} → artifactId={}", instance.getEntityId(), artifactId);
        return artifactId;
    }
}