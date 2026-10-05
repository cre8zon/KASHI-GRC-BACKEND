package com.kashi.grc.audit.service;

import com.kashi.grc.audit.domain.AuditFinding;
import com.kashi.grc.audit.domain.AuditPolicyInstance;
import com.kashi.grc.audit.domain.AuditTestInstance;
import com.kashi.grc.audit.repository.AuditControlInstanceTestMappingRepository;
import com.kashi.grc.audit.repository.AuditFindingRepository;
import com.kashi.grc.audit.repository.AuditPolicyInstanceRepository;
import com.kashi.grc.audit.repository.AuditTestInstanceRepository;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.evidence.repository.EvidenceLinkRepository;
import com.kashi.grc.issue.domain.Issue;
import com.kashi.grc.issue.dto.IssueRequest;
import com.kashi.grc.issue.service.IssueService;
import com.kashi.grc.workflow.repository.WorkflowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.Year;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * THE one implementation of the two auditor fieldwork writes on a TEST and on a
 * POLICY instance:
 *
 *   recordTestResult  result + tester notes + failure detail + exception reason
 *   reviewPolicy      review result + auditor notes (+ the policy-gap finding)
 *
 * Every screen that records them calls the same method, so a result typed in
 * the control's Fieldwork tab is exactly what the test / policy detail shows,
 * and vice versa:
 *
 *   PUT /v1/audit/test-instances/{id}/result                      (Fieldwork, test detail)
 *   PUT /v1/audit/engagements/{eid}/tests/{tid}/result            (admin Tests tab)
 *   PUT /v1/audit/policy-instances/{id}/review                    (Fieldwork, policy detail)
 *   PUT /v1/audit/engagements/{eid}/policies/{pid}/review         (Policy content tab, admin)
 *
 * They were separate copies and had drifted: the engagement test path wiped
 * the exception reason and failure detail when a caller didn't send them,
 * used a different evidence gate and never refreshed the affected-control
 * count; the engagement policy path never raised the INADEQUATE finding — so
 * the same review raised a finding from Fieldwork and none from the policy's
 * own screen.
 *
 * Callers must have run ControlAccessGuard (requireCanRecordTestResult /
 * requireCanReviewPolicy) first.
 *
 * FIELD SEMANTICS: a field the caller does not send is left as it is. So the
 * one-click result picker on the control's Tests tab (result only) never
 * erases notes written in Fieldwork.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditFieldworkService {

    private final AuditTestInstanceRepository               testRepo;
    private final AuditPolicyInstanceRepository             policyRepo;
    private final AuditControlInstanceTestMappingRepository ctrlTestMappingRepo;
    private final EvidenceLinkRepository                    evidenceLinkRepo;
    private final AuditTestPolicySnapshotService            snapshotService;
    private final AuditObligationService                    obligationService;
    private final AuditFindingRepository                    findingRepo;
    private final WorkflowRepository                        workflowRepository;

    // @Lazy setter, the same cycle-break AuditFindingEscalationWriter uses:
    // IssueService → WorkflowEngineService → … → AuditTestPolicySnapshotService.
    private IssueService issueService;

    @Autowired
    public void setIssueService(@Lazy IssueService issueService) {
        this.issueService = issueService;
    }

    // ══════════════════════════════════════════════════════════════════════
    // TEST RESULT
    // ══════════════════════════════════════════════════════════════════════

    /**
     * @param fields testResult (required), testerNotes, failureDetail,
     *               exceptionReason, evidenceOverrideReason — absent = unchanged
     * @return testInstanceId, testResult, affectedControls
     */
    @Transactional
    public Map<String, Object> recordTestResult(AuditTestInstance test, Map<String, String> fields,
                                                Long userId, Long tenantId) {
        String raw = fields.get("testResult");
        if (raw == null || raw.isBlank()) {
            throw new BusinessException("TEST_RESULT_REQUIRED", "testResult is required");
        }
        AuditTestInstance.TestResult newResult = AuditTestInstance.TestResult.valueOf(raw);
        Long id = test.getId();

        // Evidence gate. Only PASS is blocked: FAIL and EXCEPTION carry their own
        // record via failureDetail / exceptionReason, and NOT_RUN is not a
        // result. A work paper on the test, or evidence on any control it
        // covers, satisfies it. Runs BEFORE the write so a refused call cannot
        // cascade.
        String overrideReason = fields.get("evidenceOverrideReason");
        boolean overridden = overrideReason != null && !overrideReason.isBlank();
        if (newResult == AuditTestInstance.TestResult.PASS && !overridden) {
            List<Long> mappedControlIds = ctrlTestMappingRepo.findControlInstanceIdsByTestInstanceId(id);
            boolean hasEvidence =
                    !evidenceLinkRepo.entityIdsWithAnyLink("AUDIT_TEST_INSTANCE", List.of(id)).isEmpty()
                            || !evidenceLinkRepo.entityIdsWithAnyLink("AUDIT_CONTROL_INSTANCE", mappedControlIds).isEmpty();
            if (!hasEvidence) {
                throw new BusinessException("EVIDENCE_REQUIRED",
                        "Attach a work paper or evidence before passing this test, "
                                + "or record an override reason");
            }
        }
        if (overridden) {
            log.warn("[TEST-INSTANCE] Evidence gate overridden | id={} by={} reason={}", id, userId, overrideReason);
        }

        test.setTestResult(newResult);
        test.setRunAt(LocalDateTime.now());
        test.setRunByUserId(userId);
        test.setRunBySystem(false);
        if (fields.containsKey("testerNotes"))     test.setTesterNotes(fields.get("testerNotes"));
        if (fields.containsKey("failureDetail"))   test.setFailureDetail(fields.get("failureDetail"));
        if (fields.containsKey("exceptionReason")) test.setExceptionReason(fields.get("exceptionReason"));
        testRepo.save(test);

        snapshotService.cascadeDeriveControlResults(id, tenantId);

        int affectedCount = ctrlTestMappingRepo.findControlInstanceIdsByTestInstanceId(id).size();
        test.setAffectedControlCount(affectedCount);
        testRepo.save(test);

        // The recorder's delegation on this test, and on any mapped control whose
        // required tests are now all recorded.
        obligationService.onTestResultRecorded(id, userId, tenantId);

        log.info("[TEST-INSTANCE] Result set | id={} | result={} | affectedControls={}", id, newResult, affectedCount);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("testInstanceId",   id);
        out.put("testResult",       newResult);
        out.put("affectedControls", affectedCount);
        return out;
    }

    // ══════════════════════════════════════════════════════════════════════
    // POLICY REVIEW
    // ══════════════════════════════════════════════════════════════════════

    /**
     * @param fields reviewResult (required), auditorNotes — absent = unchanged
     */
    @Transactional
    public AuditPolicyInstance reviewPolicy(AuditPolicyInstance policy, Map<String, String> fields,
                                            Long userId, Long tenantId) {
        String raw = fields.get("reviewResult");
        if (raw == null || raw.isBlank()) {
            throw new BusinessException("REVIEW_RESULT_REQUIRED", "reviewResult is required");
        }
        Long id = policy.getId();
        policy.setReviewResult(AuditPolicyInstance.ReviewResult.valueOf(raw));
        policy.setReviewedById(userId);
        policy.setReviewedAt(LocalDateTime.now());
        if (fields.containsKey("auditorNotes")) policy.setAuditorNotes(fields.get("auditorNotes"));
        policyRepo.save(policy);

        // One finding per policy (not per control) when INADEQUATE. The policy
        // owner remediates — not the individual control auditees.
        if (policy.getReviewResult() == AuditPolicyInstance.ReviewResult.INADEQUATE) {
            String autoTitle = "Policy gap: " + policy.getTitleSnapshot();
            boolean alreadyExists = findingRepo
                    .findByEngagementIdAndTenantId(policy.getEngagementId(), tenantId)
                    .stream()
                    .anyMatch(f -> autoTitle.equals(f.getTitle())
                            && f.getStatus() != AuditFinding.Status.CLOSED
                            && f.getStatus() != AuditFinding.Status.ACCEPTED_RISK
                            && f.getStatus() != AuditFinding.Status.WITHDRAWN);

            if (!alreadyExists) {
                AuditFinding autoFinding = AuditFinding.builder()
                        .tenantId(tenantId)
                        .findingRef(generateFindingRef(tenantId))
                        .engagementId(policy.getEngagementId())
                        .controlInstanceId(null)  // policy-level finding, not tied to one control
                        .title(autoTitle)
                        .description("Policy '" + policy.getTitleSnapshot() + "' v"
                                + policy.getVersionSnapshot() + " reviewed as INADEQUATE.")
                        .severity(AuditFinding.Severity.MEDIUM)
                        .findingType(AuditFinding.FindingType.CONTROL_DEFICIENCY)
                        // Raised by the policy-review derivation, not typed by a person.
                        .source(AuditFinding.Source.AUTOMATED)
                        .status(AuditFinding.Status.OPEN)
                        .frameworkRef(policy.getFrameworkRefsSnapshot())
                        .ownerId(policy.getOwnerIdSnapshot())
                        .raisedBy(userId)
                        .raisedAt(LocalDateTime.now())
                        .build();
                findingRepo.save(autoFinding);
                log.info("[POLICY-INSTANCE] Auto-finding raised | policyInstanceId={} findingRef={}",
                        id, autoFinding.getFindingRef());

                autoEscalateToIssue(autoFinding, userId, tenantId);
            }

            snapshotService.syncEngagementScore(policy.getEngagementId(), tenantId);
        }

        obligationService.onPolicyReviewed(id, userId, tenantId);

        log.info("[POLICY-INSTANCE] Reviewed | id={} | result={}", id, policy.getReviewResult());
        return policy;
    }

    // ══════════════════════════════════════════════════════════════════════
    // HELPERS (moved verbatim from AuditInstanceController)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Auto-escalate a finding to Issue Management. Wrapped in try-catch so a
     * workflow config issue never breaks the review that raised it.
     */
    private void autoEscalateToIssue(AuditFinding finding, Long createdBy, Long tenantId) {
        if (finding.getOwnerId() == null || createdBy == null) {
            log.warn("[AUDIT-FINDING] Not escalating {} — ownerId={} createdBy={}. "
                            + "Set a policy owner, then escalate manually.",
                    finding.getFindingRef(), finding.getOwnerId(), createdBy);
            return;
        }
        try {
            IssueRequest req = new IssueRequest();
            req.setTitle(finding.getTitle());
            req.setDescription(finding.getDescription());
            req.setIssueType(Issue.IssueType.INTERNAL);
            req.setSeverity(Issue.Severity.MEDIUM);
            req.setSourceModule("AUDIT");
            req.setSourceEntityType("AUDIT_FINDING");
            req.setSourceEntityId(finding.getId());
            req.setFrameworkRef(finding.getFrameworkRef());
            req.setOwnerId(finding.getOwnerId());
            req.setStartTriaged(true);   // finding workflow starts at the owner — see IssueRequest.startTriaged
            req.setWorkflowId(findingWorkflowId(tenantId));
            var issueResp = issueService.create(req, createdBy, tenantId);
            finding.setLinkedIssueId(issueResp.getId());
            findingRepo.save(finding);
            log.info("[AUDIT-FINDING] Auto-escalated to issue | findingId={} issueId={} issueRef={}",
                    finding.getId(), issueResp.getId(), issueResp.getIssueRef());
        } catch (Exception ex) {
            log.warn("[AUDIT-FINDING] Auto-escalate failed for finding {} — {}",
                    finding.getId(), ex.getMessage());
        }
    }

    /** Workflow for issues escalated from an audit finding, resolved by name. */
    private Long findingWorkflowId(Long tenantId) {
        return workflowRepository.findAll().stream()
                .filter(w -> w.isActive())
                .filter(w -> "ISSUE".equalsIgnoreCase(w.getEntityType()))
                .filter(w -> w.getTenantId() == null || w.getTenantId().equals(tenantId))
                .filter(w -> "Audit Finding Remediation".equalsIgnoreCase(w.getName()))
                .map(w -> w.getId())
                .findFirst()
                .orElse(15L);
    }

    /** Collision-safe finding ref generator */
    private String generateFindingRef(Long tenantId) {
        long count = findingRepo.countByTenantId(tenantId) + 1;
        String candidate = String.format("FND-%d-%04d", Year.now().getValue(), count);
        while (findingRepo.existsByFindingRefAndTenantId(candidate, tenantId)) {
            candidate = String.format("FND-%d-%04d", Year.now().getValue(), ++count);
        }
        return candidate;
    }
}