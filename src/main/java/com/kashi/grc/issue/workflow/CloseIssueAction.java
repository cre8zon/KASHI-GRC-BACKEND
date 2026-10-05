package com.kashi.grc.issue.workflow;

import com.kashi.grc.actionitem.domain.ActionItem;
import com.kashi.grc.actionitem.repository.ActionItemRepository;
import com.kashi.grc.assessment.service.AssessmentScoreSyncService;
import com.kashi.grc.audit.domain.AuditFinding;
import com.kashi.grc.audit.repository.AuditFindingRepository;
import com.kashi.grc.audit.service.AuditTestPolicySnapshotService;
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

    private final IssueRepository                issueRepository;
    private final AuditFindingRepository         findingRepository;
    private final AuditTestPolicySnapshotService snapshotService;
    private final ActionItemRepository           actionItemRepository;
    /**
     * Replaces the VendorAssessmentRepository this handler used to hold.
     *
     * The repository was here only to read an assessment, subtract one from
     * openRemediationCount and save it. That arithmetic is gone — see the
     * ASSESSMENT branch below — and with it the reason to reach into another
     * module's table from a workflow handler. The service owns the derivation
     * and this handler asks for it, which is the same relationship the
     * AUDIT_FINDING branch already has with AuditTestPolicySnapshotService.
     */
    private final AssessmentScoreSyncService     assessmentScoreSyncService;

    /**
     * What AssessmentIssueEscalationService stamps on an issue raised from a
     * vendor-assessment remediation. Duplicated as a literal rather than
     * imported from the assessment package: this handler already pulls in
     * audit, and adding a second module dependency to reach one string is how
     * bean cycles start. If it ever changes, it changes in two places, and the
     * cascade below silently stops firing if they drift — which is why the
     * escalation service declares it as a public constant and this comment
     * names the file.
     */
    private static final String ASSESSMENT_SOURCE_TYPE = "ASSESSMENT_REMEDIATION";

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

        // Close linked AuditFinding if this issue was escalated from one
        if ("AUDIT_FINDING".equals(issue.getSourceEntityType())
                && issue.getSourceEntityId() != null) {
            findingRepository.findById(issue.getSourceEntityId()).ifPresent(finding -> {
                if (finding.getStatus() != AuditFinding.Status.CLOSED
                        && finding.getStatus() != AuditFinding.Status.ACCEPTED_RISK) {
                    finding.setStatus(AuditFinding.Status.CLOSED);
                    finding.setClosedAt(LocalDateTime.now());
                    finding.setClosedBy(closedBy);
                    findingRepository.save(finding);
                    log.info("[CLOSE_ISSUE] Linked finding closed | findingId={} findingRef={}",
                            finding.getId(), finding.getFindingRef());
                    try {
                        snapshotService.syncEngagementScore(finding.getEngagementId(), tenantId);
                    } catch (Exception ex) {
                        log.warn("[CLOSE_ISSUE] Score sync failed for engagementId={} — {}",
                                finding.getEngagementId(), ex.getMessage());
                    }
                }
            });
        }

        // ── Resolve the linked assessment finding, if it came from one ───────
        //
        // The mirror of the AUDIT_FINDING branch above, and now mirrored the
        // whole way: close the finding record, then re-derive the parent's
        // scores. Audit calls syncEngagementScore for that; this calls
        // syncAssessmentScore, which exists for the same reason and is the same
        // shape.
        //
        // What this used to do instead was subtract one from
        // openRemediationCount inline. Three problems, all of them now the
        // service's job and none of them a branch here:
        //
        //   • A decrement-only counter drifts, and Math.max(0, cur - 1) hid the
        //     drift at the bottom instead of reporting it.
        //   • It skipped entirely when parentEntityId was null — the rows
        //     created before that field was stamped on the remediation builder —
        //     logging a WARN and leaving those assessments counting findings
        //     that had closed. The service resolves those rows properly.
        //   • It could not tell the two closure routes apart. A finding closed
        //     because the vendor fixed the answer and the organisation validated
        //     it should move totalEarnedScore; one closed by accepting the risk
        //     should move only the count, because no answer changed. Re-deriving
        //     from source is right for both without asking which happened.
        //
        // Report regeneration is still NOT triggered, and that remains
        // deliberate: lifting ReviewController.decrementAndMaybeReport and
        // generateReportInternal (plus their six repositories) into a service is
        // a real refactor of a working path. The sync logs when a version is due
        // and the Review screen's "Re-generate" is the same call.
        if (ASSESSMENT_SOURCE_TYPE.equals(issue.getSourceEntityType())
                && issue.getSourceEntityId() != null) {
            actionItemRepository.findById(issue.getSourceEntityId()).ifPresent(item -> {
                if (item.getStatus() == ActionItem.Status.RESOLVED
                        || item.getStatus() == ActionItem.Status.DISMISSED) {
                    return;
                }
                item.setStatus(ActionItem.Status.RESOLVED);
                item.setResolvedAt(LocalDateTime.now());
                item.setResolvedBy(closedBy);
                item.setResolutionNote("Resolved by Issue " + issue.getIssueRef() + " closure.");
                actionItemRepository.save(item);
                log.info("[CLOSE_ISSUE] Linked finding resolved | itemId={} | issueRef={}",
                        item.getId(), issue.getIssueRef());

                // parentEntityId is the assessment, stamped on the finding by
                // ReviewController.requestRemediation and by
                // AssessmentGuardFindingListener. Issue.vendorId is not a
                // substitute — a vendor can have several assessments — so when
                // this really is absent there is nothing to sync and saying so
                // beats syncing the wrong row.
                Long assessmentId = item.getParentEntityId();
                if (assessmentId == null) {
                    log.warn("[CLOSE_ISSUE] Finding itemId={} has no parentEntityId — scores not "
                            + "synced. Pre-dates the remediation builder change; the finding "
                            + "itself is resolved, and the next sync on that assessment from any "
                            + "other closure will pick it up.", item.getId());
                    return;
                }
                assessmentScoreSyncService.syncAssessmentScore(assessmentId, tenantId);
            });
        }

        return true;
    }
}