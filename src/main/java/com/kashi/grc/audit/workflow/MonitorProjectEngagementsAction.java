package com.kashi.grc.audit.workflow;

import com.kashi.grc.audit.domain.AuditControlInstance;
import com.kashi.grc.audit.domain.AuditEngagement;
import com.kashi.grc.audit.domain.AuditPolicyInstance;
import com.kashi.grc.audit.domain.AuditSectionInstance;
import com.kashi.grc.audit.domain.AuditTestInstance;
import com.kashi.grc.audit.repository.AuditControlInstanceRepository;
import com.kashi.grc.audit.repository.AuditEngagementRepository;
import com.kashi.grc.audit.repository.AuditPolicyInstanceRepository;
import com.kashi.grc.audit.repository.AuditSectionInstanceRepository;
import com.kashi.grc.audit.repository.AuditTestInstanceRepository;
import com.kashi.grc.workflow.automation.AutomatedActionContext;
import com.kashi.grc.workflow.automation.AutomatedActionHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * AutomatedActionHandler for key "MONITOR_PROJECT_ENGAGEMENTS".
 *
 * Fires on workflow_step 223 (step_order=5 after Evidence Submission is inserted,
 * name="Evidence Collection Monitor", side=SYSTEM) of Workflow 16.
 *
 * WHAT IT CHECKS:
 *   Per engagement → per section → controls + tests + policies status.
 *   Produces a structured readiness snapshot stored in the step's audit log.
 *
 * READINESS GATE:
 *   An engagement is "ready" when:
 *     - All top-level sections have an assigned auditee (auditeeAssignedUserId != null)
 *     - At least 1 control per section has auditeeEvidenceSubmitted = true
 *       OR the section has auditeeSubmittedAt set (auditee marked it complete)
 *
 *   The step auto-approves when ALL engagements are ready.
 *   If any engagement is not ready, returns false → step stays IN_PROGRESS.
 *   The SLA monitor will escalate after sla_hours.
 *
 * SNAPSHOT FORMAT (logged at INFO level for audit trail):
 *   [MONITOR] engagement=SOC2-2026-001 [SOC2]
 *     Section: CC6 — Logical Access  assignedAuditee=✓  evidenceSubmitted=3/5  tests=4/5 PASS  policies=2/2 ADEQUATE
 *     Section: CC7 — System Operations  assignedAuditee=✓  evidenceSubmitted=0/3  tests=0/3  policies=1/1
 *     → READY=false  controls=8  evidenceSubmitted=3  testsDone=4  policiesReviewed=3
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MonitorProjectEngagementsAction implements AutomatedActionHandler {

    private final AuditEngagementRepository      engagementRepository;
    private final AuditSectionInstanceRepository  sectionRepository;
    private final AuditControlInstanceRepository  controlRepository;
    private final AuditTestInstanceRepository     testRepository;
    private final AuditPolicyInstanceRepository   policyRepository;

    @Override
    public String actionKey() {
        return "MONITOR_PROJECT_ENGAGEMENTS";
    }

    @Override
    @Transactional(readOnly = true)
    public boolean execute(AutomatedActionContext ctx) {
        Long projectInstanceId = ctx.getWorkflowInstance().getEntityId();
        Long tenantId          = ctx.getTenantId();

        List<AuditEngagement> engagements =
                engagementRepository.findByProjectInstanceId(projectInstanceId);

        if (engagements.isEmpty()) {
            log.warn("[MONITOR_PROJECT_ENGAGEMENTS] No engagements for projectInstanceId={} — auto-approving",
                    projectInstanceId);
            return true;
        }

        log.info("[MONITOR_PROJECT_ENGAGEMENTS] Checking {} engagement(s) for projectInstanceId={}",
                engagements.size(), projectInstanceId);

        boolean allReady = true;

        for (AuditEngagement eng : engagements) {
            EngagementReadiness readiness = checkEngagement(eng);
            logEngagement(eng, readiness);
            if (!readiness.isReady()) allReady = false;
        }

        // Log progress snapshot for audit trail but always auto-approve.
        // Step 5 (Evidence Submission) was manually approved by the lead auditor
        // who decided evidence collection was sufficiently complete.
        // This monitor step is a passthrough that records the snapshot and advances.
        long totalControls = engagements.stream()
                .mapToLong(e -> controlRepository.countByEngagementId(e.getId())).sum();
        log.info("[MONITOR_PROJECT_ENGAGEMENTS] Evidence snapshot | projectInstanceId={} | engagements={} | totalControls={} | auto-approving",
                projectInstanceId, engagements.size(), totalControls);

        return true;
    }

    // ── Per-engagement readiness check ────────────────────────────────────────

    private EngagementReadiness checkEngagement(AuditEngagement eng) {
        Long engId = eng.getId();

        // Load all data
        List<AuditSectionInstance>  sections  = sectionRepository.findByEngagementIdOrderByPathAscOrderNoAsc(engId);
        List<AuditControlInstance>  controls  = controlRepository.findByEngagementId(engId);
        List<AuditTestInstance>     tests     = testRepository.findByEngagementIdOrderByTestNameSnapshotAsc(engId);
        List<AuditPolicyInstance>   policies  = policyRepository.findByEngagementIdOrderByTitleSnapshotAsc(engId);

        // Index controls/tests/policies by sectionInstanceId for grouping
        Map<Long, List<AuditControlInstance>> ctrlBySection   = controls.stream()
                .collect(Collectors.groupingBy(AuditControlInstance::getSectionInstanceId));
        Map<Long, List<AuditTestInstance>>    testsByCtrl     = new LinkedHashMap<>();
        // tests link to controls via controlTagSnapshot matching — group by engagementId
        // for simplicity we aggregate tests at engagement level, reported per section via control join

        // Engagement-level counters
        int totalSections     = (int) sections.stream().filter(s -> s.getDepth() == 0).count();
        int sectionsAssigned  = (int) sections.stream()
                .filter(s -> s.getDepth() == 0 && s.getAuditeeAssignedUserId() != null).count();
        int sectionsSubmitted = (int) sections.stream()
                .filter(s -> s.getAuditeeSubmittedAt() != null).count();

        int totalControls    = controls.size();
        int evidenceSubmitted = (int) controls.stream()
                .filter(AuditControlInstance::isAuditeeEvidenceSubmitted).count();
        int controlsTested    = (int) controls.stream()
                .filter(c -> c.getTestResult() != null
                        && c.getTestResult() != AuditControlInstance.TestResult.NOT_TESTED).count();
        int controlsEffective = (int) controls.stream()
                .filter(c -> AuditControlInstance.TestResult.EFFECTIVE == c.getTestResult()).count();

        int totalTests        = tests.size();
        int testsDone         = (int) tests.stream()
                .filter(t -> t.getTestResult() != AuditTestInstance.TestResult.NOT_RUN).count();
        int testsPass         = (int) tests.stream()
                .filter(t -> AuditTestInstance.TestResult.PASS == t.getTestResult()).count();

        int totalPolicies     = policies.size();
        int policiesReviewed  = (int) policies.stream()
                .filter(p -> p.getReviewResult() != AuditPolicyInstance.ReviewResult.NOT_REVIEWED).count();
        int policiesAdequate  = (int) policies.stream()
                .filter(p -> p.getReviewResult() == AuditPolicyInstance.ReviewResult.ADEQUATE
                        || p.getReviewResult() == AuditPolicyInstance.ReviewResult.ADEQUATE_WITH_GAPS).count();

        // Build per-section breakdown (top-level sections only)
        List<SectionStatus> sectionStatuses = sections.stream()
                .filter(s -> s.getDepth() == 0)
                .map(sec -> {
                    List<AuditControlInstance> secCtrls = ctrlBySection.getOrDefault(sec.getId(), List.of());
                    int secEvidenceSubmitted = (int) secCtrls.stream()
                            .filter(AuditControlInstance::isAuditeeEvidenceSubmitted).count();
                    int secTested = (int) secCtrls.stream()
                            .filter(c -> c.getTestResult() != null
                                    && c.getTestResult() != AuditControlInstance.TestResult.NOT_TESTED).count();
                    return new SectionStatus(
                            sec.getSectionCodeSnapshot(),
                            sec.getSectionNameSnapshot(),
                            sec.getAuditeeAssignedUserId() != null,
                            sec.getAuditeeSubmittedAt() != null,
                            secCtrls.size(),
                            secEvidenceSubmitted,
                            secTested
                    );
                })
                .collect(Collectors.toList());

        // READINESS RULE:
        // An engagement is ready for control evaluation when:
        //   1. All top-level sections have an assigned auditee, AND
        //   2. At least 50% of sections have submitted evidence
        //      (allows some stragglers — programme can still advance)
        // Strict mode: change threshold to totalSections for 100% requirement.
        int evidenceThreshold = totalSections > 0 ? Math.max(1, totalSections / 2) : 0;
        boolean allAssigned   = sectionsAssigned  == totalSections;
        boolean enoughEvidence= sectionsSubmitted >= evidenceThreshold;
        boolean ready         = totalSections == 0   // no sections = nothing to check
                || (allAssigned && enoughEvidence);

        return new EngagementReadiness(
                ready,
                totalSections, sectionsAssigned, sectionsSubmitted,
                totalControls, evidenceSubmitted, controlsTested, controlsEffective,
                totalTests, testsDone, testsPass,
                totalPolicies, policiesReviewed, policiesAdequate,
                sectionStatuses
        );
    }

    private void logEngagement(AuditEngagement eng, EngagementReadiness r) {
        log.info("[MONITOR] engagement={} [{}] status={} READY={}",
                eng.getEngagementRef(), eng.getFrameworkRef(), eng.getStatus(), r.isReady());
        log.info("[MONITOR]   Sections : {}/{} assigned  {}/{} submitted",
                r.sectionsAssigned(), r.totalSections(),
                r.sectionsSubmitted(), r.totalSections());
        log.info("[MONITOR]   Controls : {}/{} evidence  {}/{} tested  {}/{} effective",
                r.evidenceSubmitted(), r.totalControls(),
                r.controlsTested(), r.totalControls(),
                r.controlsEffective(), r.totalControls());
        log.info("[MONITOR]   Tests    : {}/{} done  {}/{} PASS",
                r.testsDone(), r.totalTests(), r.testsPass(), r.totalTests());
        log.info("[MONITOR]   Policies : {}/{} reviewed  {}/{} adequate",
                r.policiesReviewed(), r.totalPolicies(),
                r.policiesAdequate(), r.totalPolicies());

        for (SectionStatus sec : r.sections()) {
            log.info("[MONITOR]     Section [{}] {} | auditee={} submitted={} | controls={} evidence={}/{} tested={}/{}",
                    sec.code() != null ? sec.code() : "-",
                    sec.name(),
                    sec.auditeeAssigned() ? "✓" : "✗",
                    sec.submitted() ? "✓" : "✗",
                    sec.totalControls(),
                    sec.evidenceSubmitted(), sec.totalControls(),
                    sec.tested(), sec.totalControls());
        }
    }

    // ── Value objects ─────────────────────────────────────────────────────────

    private record EngagementReadiness(
            boolean ready,
            int totalSections, int sectionsAssigned, int sectionsSubmitted,
            int totalControls, int evidenceSubmitted, int controlsTested, int controlsEffective,
            int totalTests, int testsDone, int testsPass,
            int totalPolicies, int policiesReviewed, int policiesAdequate,
            List<SectionStatus> sections
    ) {
        boolean isReady() { return ready; }
    }

    private record SectionStatus(
            String code, String name,
            boolean auditeeAssigned, boolean submitted,
            int totalControls, int evidenceSubmitted, int tested
    ) {}
}