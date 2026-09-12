package com.kashi.grc.audit.service;

import com.kashi.grc.audit.domain.AuditFinding;
import com.kashi.grc.audit.repository.AuditFindingRepository;
import com.kashi.grc.issue.dto.IssueRequest;
import com.kashi.grc.issue.service.IssueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Escalates a finding to an issue in its OWN transaction.
 *
 * Why this exists:
 *
 * issueService.create() is @Transactional with the default REQUIRED propagation,
 * so called directly it JOINS the caller's transaction. When it failed — an
 * optimistic lock on the finding, most often — Spring marked that shared
 * transaction rollback-only. The caller caught the exception and carried on,
 * believing it had handled a non-fatal problem, and then the commit blew up:
 *
 *   [AUDIT-DERIVE] Auto-escalate failed for finding 132 — Row was updated or
 *                  deleted by another transaction
 *   [INTEGRATION]  Failed to record engagement snapshot result:
 *                  Transaction silently rolled back because it has been marked
 *                  as rollback-only
 *
 * A whole integration run was lost to a failure that had already been logged and
 * dismissed. Catching an exception does not un-mark a rollback-only transaction;
 * only a separate transaction keeps the damage local.
 *
 * REQUIRES_NEW gives the escalation its own connection and its own commit. If it
 * fails, the caller's work — the check result, the evidence, the finding itself —
 * still commits, which is what the original catch was trying to express.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditFindingEscalationWriter {

    private final AuditFindingRepository findingRepository;

    // @Lazy SETTER injection, not constructor injection — the same break
    // AuditTestPolicySnapshotService already uses, for the same cycle:
    //
    //   ...EscalationWriter -> IssueService -> WorkflowEngineService
    //     -> AutomatedActionRegistry -> CloseIssueAction
    //     -> AuditTestPolicySnapshotService -> ...EscalationWriter
    //
    // Taking IssueService in the constructor put this new bean inside that ring
    // and the context refused to start. @Lazy injects a proxy, so IssueService is
    // not resolved until the first escalate() call.
    private IssueService issueService;

    @Autowired
    public void setIssueService(@Lazy IssueService issueService) {
        this.issueService = issueService;
    }

    /**
     * @return the new issue id, or null when escalation failed. Never throws —
     *         the caller has already decided this is best-effort.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long escalate(AuditFinding finding, IssueRequest req, Long raisedBy, Long tenantId) {
        try {
            var issueResp = issueService.create(req, raisedBy, tenantId);

            // Re-read inside THIS transaction. The finding passed in belongs to
            // the caller's persistence context; saving it here would write a
            // stale version number and reproduce the optimistic-lock failure this
            // class exists to contain.
            AuditFinding fresh = findingRepository.findById(finding.getId()).orElse(null);
            if (fresh != null) {
                fresh.setLinkedIssueId(issueResp.getId());
                findingRepository.save(fresh);
            }

            log.info("[AUDIT-DERIVE] Auto-escalated to issue | findingId={} issueId={}",
                    finding.getId(), issueResp.getId());
            return issueResp.getId();

        } catch (Exception ex) {
            // Contained: this transaction rolls back, the caller's does not.
            log.warn("[AUDIT-DERIVE] Auto-escalate failed for finding {} — {}",
                    finding.getId(), ex.getMessage());
            return null;
        }
    }
}