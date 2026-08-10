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
        // entityId IS the issueId — no DB call needed
        log.debug("[ISSUE-RESOLVER] issueId={}", instance.getEntityId());
        return instance.getEntityId();
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

    @Override
    public String resolveEntityTitle(WorkflowInstance instance) {
        return issueRepository.findById(instance.getEntityId())
                .map(issue -> issue.getTitle())
                .orElse(null);
    }

    /**
     * One query for every instance instead of one findById each. The task inbox
     * resolves a title per workflow instance, so on a large inbox the single
     * version was N sequential round trips.
     */
    @Override
    public java.util.Map<Long, String> resolveEntityTitles(
            java.util.Collection<WorkflowInstance> instances) {

        java.util.Set<Long> issueIds = instances.stream()
                .map(WorkflowInstance::getEntityId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        if (issueIds.isEmpty()) return java.util.Map.of();

        java.util.Map<Long, String> titleByIssueId = new java.util.HashMap<>();
        issueRepository.findAllById(issueIds)
                .forEach(i -> titleByIssueId.put(i.getId(), i.getTitle()));

        java.util.Map<Long, String> out = new java.util.HashMap<>();
        for (WorkflowInstance wi : instances) {
            String title = titleByIssueId.get(wi.getEntityId());
            if (title != null) out.put(wi.getId(), title);
        }
        return out;
    }
}