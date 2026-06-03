package com.kashi.grc.issue.workflow;

import com.kashi.grc.issue.domain.Issue;
import com.kashi.grc.issue.repository.IssueRepository;
import com.kashi.grc.workflow.automation.AutomatedActionContext;
import com.kashi.grc.workflow.automation.AutomatedActionHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * AutomatedActionHandler for key "CLOSE_ISSUE".
 *
 * Fires on workflow_step 210 (step_order=7, automated=1) of the
 * "Issue Remediation Lifecycle" workflow (id=15).
 *
 * WHAT IT DOES:
 *   Looks up the Issue linked to the WorkflowInstance via
 *   IssueRepository.findByTenantIdAndWorkflowInstanceId(), then
 *   calls the @Modifying closeIssue() query to set:
 *     status    = CLOSED
 *     closed_at = NOW()
 *     closed_by = initiatedBy (the user who triggered the last human step)
 *
 *   Returns true on success → WorkflowEngineService auto-approves and
 *   marks the workflow COMPLETED.
 *   Returns false if the issue is not found or already closed → step
 *   stays IN_PROGRESS and a WARN is logged. No exception is thrown.
 *
 * PLACEMENT:
 *   src/main/java/com/kashi/grc/issue/workflow/CloseIssueAction.java
 *
 *   Lives in the issue.workflow package alongside IssueEntityResolver —
 *   keeps all issue-workflow integration code in one place, separate from
 *   the assessment automation in com.kashi.grc.workflow.automation.
 *
 * REGISTRATION:
 *   @Component is sufficient. AutomatedActionRegistry picks this up
 *   automatically via Spring's constructor injection of all
 *   AutomatedActionHandler beans. No changes to the registry needed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CloseIssueAction implements AutomatedActionHandler {

    private final IssueRepository issueRepository;

    @Override
    public String actionKey() {
        return "CLOSE_ISSUE";
    }

    @Override
    @Transactional
    public boolean execute(AutomatedActionContext ctx) {
        Long workflowInstanceId = ctx.getWorkflowInstance().getId();
        Long tenantId           = ctx.getTenantId();
        Long closedBy           = ctx.getInitiatedBy();

        log.info("[CLOSE_ISSUE] Starting | workflowInstanceId={} | tenantId={}",
                workflowInstanceId, tenantId);

        // ── Find the issue linked to this workflow instance ───────────────────
        Issue issue = issueRepository
                .findByTenantIdAndWorkflowInstanceId(tenantId, workflowInstanceId)
                .orElse(null);

        if (issue == null) {
            log.warn("[CLOSE_ISSUE] No issue found for workflowInstanceId={} | tenantId={} " +
                    "— step will stay IN_PROGRESS", workflowInstanceId, tenantId);
            return false;
        }

        // ── Guard: already closed / accepted risk — idempotent, return true ───
        if (issue.getStatus() == Issue.Status.CLOSED ||
                issue.getStatus() == Issue.Status.ACCEPTED_RISK) {
            log.info("[CLOSE_ISSUE] Issue id={} already in terminal status={} — skipping, auto-approving",
                    issue.getId(), issue.getStatus());
            return true;
        }

        // ── Close via the @Modifying query (single UPDATE, no dirty-tracking) ─
        int updated = issueRepository.closeIssue(
                issue.getId(),
                tenantId,
                Issue.Status.CLOSED,
                LocalDateTime.now(),
                closedBy
        );

        if (updated == 0) {
            log.warn("[CLOSE_ISSUE] closeIssue() affected 0 rows | issueId={} | tenantId={}",
                    issue.getId(), tenantId);
            return false;
        }

        log.info("[CLOSE_ISSUE] Done | issueId={} | issueRef={} | closedBy={}",
                issue.getId(), issue.getIssueRef(), closedBy);

        return true;
    }
}