package com.kashi.grc.issue.workflow;

import com.kashi.grc.issue.repository.IssueRepository;
import com.kashi.grc.workflow.domain.WorkflowInstance;
import com.kashi.grc.workflow.spi.WorkflowEntityResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Resolves the artifact ID for ISSUE entity type workflows.
 *
 * WorkflowInstance.entityId = issue.getId() — set directly by IssueService.
 * The issue IS the artifact — direct resolution, no indirection needed.
 *
 * TaskInbox uses this to build the route:
 *   navKey "issue_detail" → /module/ISSUE/{artifactId}?stepInstanceId={stepInstanceId}
 *
 * This enables all three issue workflow types:
 *   ISSUE_MGMT_INTERNAL  — manual internal issues
 *   ISSUE_MGMT_EXTERNAL  — audit findings, pen-test, regulatory
 *   ISSUE_MGMT_AUTOMATED — scanner/SIEM/KRI automated alerts
 *
 * Zero changes to WorkflowEngineService or TaskInbox required.
 * Spring auto-discovers this via @Component + WorkflowEntityResolverRegistry.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IssueEntityResolver implements WorkflowEntityResolver {

    private final IssueRepository issueRepository;

    @Override
    public String entityType() { return "ISSUE"; }

    @Override
    public Long resolveArtifactId(WorkflowInstance instance) {
        boolean exists = issueRepository.existsById(instance.getEntityId());
        log.debug("[ISSUE-RESOLVER] issueId={} exists={}", instance.getEntityId(), exists);
        return exists ? instance.getEntityId() : null;
    }

    /**
     * Resolves the issue owner for ENTITY_OWNER actor resolution.
     * Used by workflow steps 2-6 of Issue Remediation Lifecycle:
     *   Step 2 (Owner Acknowledges & Plans)  → issue.ownerId
     *   Step 3 (Execute Remediation)         → issue.ownerId
     *   Step 4 (Submit for Review)            → issue.ownerId
     *
     * Returns null if no owner assigned yet — engine falls back to
     * PREVIOUS_ACTOR (whoever triaged in step 1).
     */
    @Override
    public Long resolveOwnerId(WorkflowInstance instance) {
        return issueRepository.findById(instance.getEntityId())
                .map(issue -> {
                    log.debug("[ISSUE-RESOLVER] resolveOwnerId issueId={} ownerId={}",
                            instance.getEntityId(), issue.getOwnerId());
                    return issue.getOwnerId();
                })
                .orElse(null);
    }
}