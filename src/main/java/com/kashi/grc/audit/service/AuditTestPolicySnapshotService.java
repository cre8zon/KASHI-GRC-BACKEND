package com.kashi.grc.audit.service;

import com.kashi.grc.audit.domain.*;
import com.kashi.grc.audit.repository.*;
import com.kashi.grc.issue.domain.Issue;
import com.kashi.grc.issue.dto.IssueRequest;
import com.kashi.grc.issue.service.IssueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.Year;
import java.util.*;
import java.util.stream.Collectors;

/**
 * AuditTestPolicySnapshotService — snapshots tests and policies at engagement creation.
 *
 * ── CALLED FROM ────────────────────────────────────────────────────────────────
 * AuditEngagementService.snapshotTemplate() calls this service after creating
 * AuditControlInstance rows. It then creates:
 *   1. AuditTestInstance  — one per AuditTest linked to any control in the template
 *   2. AuditControlInstanceTestMapping — runtime many-to-many (control ↔ test)
 *   3. AuditPolicyInstance — one per AuditPolicy linked to any control in the template
 *   4. AuditPolicyInstanceControlMapping — runtime many-to-many (policy ↔ control)
 *
 * ── DEDUPLICATION ────────────────────────────────────────────────────────────
 * If "MFA enforced" test is linked to 5 controls, only ONE AuditTestInstance
 * is created for the engagement — not one per control. The runtime mapping table
 * links that single instance to all 5 control instances.
 *
 * This mirrors how Vanta works: one test run satisfies all linked controls simultaneously.
 *
 * ── 100% ISOLATION ───────────────────────────────────────────────────────────
 * After this method returns, changes to the library (AuditTest, AuditPolicy,
 * AuditControlTestMapping, AuditPolicyControlMapping) have ZERO effect on the
 * running engagement. All runtime data is self-contained in the *Instance tables.
 *
 * ── RESULT DERIVATION ────────────────────────────────────────────────────────
 * deriveControlResult() is called after any test result changes.
 * It queries AuditControlInstanceTestMapping for all required tests of a control,
 * reads their results, and sets AuditControlInstance.testResult accordingly.
 * This replaces manual testResult entry for controls that have tests.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditTestPolicySnapshotService {

    private final com.kashi.grc.ucf.service.TagExpansionService tagExpansionService;
    private final AuditTestRepository                         testRepository;
    private final AuditControlTestMappingRepository           controlTestMappingRepository;
    private final AuditTestInstanceRepository                  testInstanceRepository;
    private final AuditControlInstanceTestMappingRepository    controlInstanceTestMappingRepository;
    private final AuditControlInstanceRepository               controlInstanceRepository;

    private final AuditPolicyRepository                        policyRepository;
    private final AuditPolicyControlMappingRepository          policyControlMappingRepository;
    private final AuditPolicyInstanceRepository                policyInstanceRepository;
    private final AuditPolicyInstanceControlMappingRepository  policyInstanceControlMappingRepository;

    // ── ADDED: needed by syncEngagementScore() ────────────────────────────────
    private final AuditEngagementRepository                    engagementRepository;
    private final com.kashi.grc.usermanagement.repository.UserRepository userRepository;
    private final com.kashi.grc.workflow.repository.WorkflowRepository workflowRepository;
    private final AuditFindingRepository                       findingRepository;

    // ── ADDED: auto-escalate findings to Issue Management ─────────────────────
    // @Lazy breaks the circular dependency:
    // AuditTestPolicySnapshotService → IssueService → WorkflowEngineService
    //   → AutomatedActionRegistry → CloseIssueAction → AuditTestPolicySnapshotService
    private IssueService issueService;

    @Autowired
    public void setIssueService(@Lazy IssueService issueService) {
        this.issueService = issueService;
    }

    /**
     * Self-reference, so autoEscalateToIssue is invoked THROUGH the proxy.
     *
     * A direct this.autoEscalateToIssue(...) call bypasses the proxy entirely
     * and its REQUIRES_NEW silently does nothing — the exact failure the
     * annotation was added to prevent, with no sign it was ignored.
     *
     * Setter injection, not a constructor field: Lombok does not copy @Lazy onto
     * the generated constructor parameter (that needs lombok.config
     * copyableAnnotations), so a `@Lazy private final` self-reference is
     * injected eagerly and the context fails to start with a self-referencing
     * cycle. This mirrors the setIssueService pattern directly above, which
     * exists for the same reason.
     */
    private AuditTestPolicySnapshotService self;

    @Autowired
    public void setSelf(@Lazy AuditTestPolicySnapshotService self) {
        this.self = self;
    }

    // ── ADDED: creates engagement-scoped integration snapshots after test snapshotting ──
    private final com.kashi.grc.integration.service.EngagementIntegrationSnapshotService engagementIntegrationSnapshotService;

    // Raw-JDBC batch inserts — see class javadoc addition below for why plain
    // saveAll() here was the real bottleneck this whole method was known for.
    private final com.kashi.grc.common.jdbc.JdbcBatchInsertHelper jdbcBatchInsertHelper;

    /**
     * Called by AuditEngagementService.snapshotTemplate() after control instances are created.
     *
     * Finds all tests and policies linked to the controls in the engagement,
     * snapshots them, and creates the runtime mapping rows.
     *
     * @param engagementId  the engagement just created
     * @param tenantId      tenant for isolation
     * @param snapshotBy    user who triggered the snapshot (lead auditor or system)
     */
    @Transactional
    public void snapshotTestsAndPolicies(Long engagementId, Long tenantId, Long snapshotBy) {
        log.info("[AUDIT-SNAPSHOT] Snapshotting tests and policies | engagementId={} tenantId={}",
                engagementId, tenantId);

        // Load all control instances for this engagement
        List<AuditControlInstance> controlInstances =
                controlInstanceRepository.findByEngagementId(engagementId);

        if (controlInstances.isEmpty()) {
            log.debug("[AUDIT-SNAPSHOT] No control instances — skipping tests/policies");
            return;
        }

        // Collect all original control IDs
        Set<Long> originalControlIds = controlInstances.stream()
                .map(AuditControlInstance::getOriginalControlId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // Build a lookup: originalControlId → controlInstanceId
        Map<Long, Long> originalToInstanceId = controlInstances.stream()
                .filter(c -> c.getOriginalControlId() != null)
                .collect(Collectors.toMap(
                        AuditControlInstance::getOriginalControlId,
                        AuditControlInstance::getId,
                        (a, b) -> a  // if duplicate originals (shouldn't happen), keep first
                ));

        snapshotTests(engagementId, tenantId, originalControlIds, originalToInstanceId);
        snapshotPolicies(engagementId, tenantId, originalControlIds, originalToInstanceId);

        // ADDED: create engagement-scoped integration snapshots for all AUTOMATED test instances.
        // This maps each AUTOMATED AuditTestInstance to the precise IntegrationCheckConfig
        // identified by automationKeySnapshot, so IntegrationRunner can push results
        // to the correct test instance by checkKey rather than by controlTagSnapshot.
        engagementIntegrationSnapshotService.snapshotForEngagement(engagementId, tenantId);

        log.info("[AUDIT-SNAPSHOT] Tests and policies snapshotted | engagementId={}", engagementId);
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    private void snapshotTests(Long engagementId, Long tenantId,
                               Set<Long> originalControlIds,
                               Map<Long, Long> originalToInstanceId) {

        // Find all control→test library mappings for these controls
        List<AuditControlTestMapping> allMappings = originalControlIds.stream()
                .flatMap(cid -> controlTestMappingRepository.findByControlIdOrderByOrderNoAsc(cid).stream())
                .collect(Collectors.toList());

        if (allMappings.isEmpty()) {
            log.debug("[AUDIT-SNAPSHOT] No test mappings found for engagementId={}", engagementId);
            return;
        }

        // Collect unique test IDs — one test instance per test, even if mapped to many controls
        Set<Long> uniqueTestIds = allMappings.stream()
                .map(AuditControlTestMapping::getTestId)
                .collect(Collectors.toSet());

        // Batch-load all tests in one query
        Map<Long, AuditTest> testMap = testRepository.findAllById(uniqueTestIds)
                .stream().collect(Collectors.toMap(AuditTest::getId, t -> t));

        // Build all test instances in memory, batch-insert in one round-trip.
        // Was testInstanceRepository.saveAll(...) — looks batched, but every
        // entity in this app uses GenerationType.IDENTITY (see BaseEntity),
        // which forces Hibernate to execute one INSERT per row regardless of
        // batch_size config. For 120 rows at Aiven's ~100-200ms RTT, that's
        // the entire ~27s this step was costing. Raw JDBC batch insert
        // bypasses JPA's per-row IDENTITY flush entirely.
        Map<Long, Long> testIdToInstanceId = new HashMap<>();
        List<Long> testIdsInOrder = new ArrayList<>();
        List<Object[]> testRows = new ArrayList<>();
        LocalDateTime testSnapshotNow = LocalDateTime.now();
        for (Long testId : uniqueTestIds) {
            AuditTest test = testMap.get(testId);
            if (test == null) continue;
            testIdsInOrder.add(testId);
            testRows.add(new Object[]{
                    tenantId, test.getId(), engagementId,
                    test.getName(), test.getTestRef(), test.getDescription(),
                    test.getTestProcedure(), test.getEvidenceGuidance(), test.getFrameworkRef(),
                    test.getControlTag(), tagExpansionService.expand(test.getControlTag()),
                    test.getAutomationType() != null ? test.getAutomationType().name() : "MANUAL",
                    test.getAutomationKey(),
                    test.getFrequency() != null ? test.getFrequency().name() : "ANNUAL",
                    testSnapshotNow,
                    AuditTestInstance.TestResult.NOT_RUN.name(),
                    false, // run_by_system — nullable=false, no DB default, see comment above
                    testSnapshotNow, testSnapshotNow
            });
        }
        List<Long> generatedTestInstanceIds = jdbcBatchInsertHelper.batchInsertAndGetIds(
                "INSERT INTO audit_test_instances " +
                        "(tenant_id, original_test_id, engagement_id, " +
                        "test_name_snapshot, test_ref_snapshot, description_snapshot, " +
                        "test_procedure_snapshot, evidence_guidance_snapshot, framework_ref_snapshot, " +
                        "control_tag_snapshot, matched_tags_snapshot, automation_type_snapshot, " +
                        "automation_key_snapshot, frequency_snapshot, snapshotted_at, test_result, " +
                        "run_by_system, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                testRows);
        for (int i = 0; i < testIdsInOrder.size(); i++) {
            testIdToInstanceId.put(testIdsInOrder.get(i), generatedTestInstanceIds.get(i));
        }

        // Create runtime mappings in batch
        List<Object[]> mappingRows = new ArrayList<>();
        int mappingsSkipped = 0;
        for (AuditControlTestMapping mapping : allMappings) {
            Long controlInstanceId = originalToInstanceId.get(mapping.getControlId());
            Long testInstanceId    = testIdToInstanceId.get(mapping.getTestId());

            if (controlInstanceId == null || testInstanceId == null) {
                log.warn("[AUDIT-SNAPSHOT] Skipping mapping — missing instance | " +
                        "controlId={} testId={}", mapping.getControlId(), mapping.getTestId());
                mappingsSkipped++;
                continue;
            }
            mappingRows.add(new Object[]{
                    controlInstanceId, testInstanceId, engagementId, tenantId,
                    mapping.isRequired(), mapping.getOrderNo(), mapping.getMappingNote(),
                    mapping.getControlId(), mapping.getTestId(),
                    testSnapshotNow, testSnapshotNow
            });
        }
        if (!mappingRows.isEmpty()) {
            jdbcBatchInsertHelper.batchInsertAndGetIds(
                    "INSERT INTO audit_control_instance_test_mappings " +
                            "(control_instance_id, test_instance_id, engagement_id, tenant_id, " +
                            "is_required, order_no, mapping_note_snapshot, " +
                            "original_control_id, original_test_id, created_at, updated_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    mappingRows);
        }

        log.info("[AUDIT-SNAPSHOT] Snapshotted {} test instances, {} mappings | engagementId={}",
                testIdToInstanceId.size(), allMappings.size() - mappingsSkipped, engagementId);
    }

    // ── Policies ──────────────────────────────────────────────────────────────

    private void snapshotPolicies(Long engagementId, Long tenantId,
                                  Set<Long> originalControlIds,
                                  Map<Long, Long> originalToInstanceId) {

        // Find all policy→control library mappings for these controls
        List<AuditPolicyControlMapping> allMappings = originalControlIds.stream()
                .flatMap(cid -> policyControlMappingRepository.findByControlId(cid).stream())
                .collect(Collectors.toList());

        if (allMappings.isEmpty()) {
            log.debug("[AUDIT-SNAPSHOT] No policy mappings found for engagementId={}", engagementId);
            return;
        }

        // Unique policy IDs — one policy instance per policy
        Set<Long> uniquePolicyIds = allMappings.stream()
                .map(AuditPolicyControlMapping::getPolicyId)
                .collect(Collectors.toSet());

        // Batch-load all policies in one query
        Map<Long, AuditPolicy> policyMap = policyRepository.findAllById(uniquePolicyIds)
                .stream().collect(Collectors.toMap(AuditPolicy::getId, p -> p));

        // Build approved policy instances in memory, batch-insert in one round-trip.
        // Same IDENTITY-batching issue as snapshotTests() above — was the other
        // ~19s of the ~46s this whole method used to cost.
        Map<Long, Long> policyIdToInstanceId = new HashMap<>();
        List<Long> policyIdsToSave = new ArrayList<>();
        List<Object[]> policyRows = new ArrayList<>();
        LocalDateTime policySnapshotNow = LocalDateTime.now();
        for (Long policyId : uniquePolicyIds) {
            AuditPolicy policy = policyMap.get(policyId);
            if (policy == null) continue;
            if (policy.getStatus() != AuditPolicy.PolicyStatus.APPROVED &&
                    policy.getStatus() != AuditPolicy.PolicyStatus.UNDER_REVIEW) {
                log.debug("[AUDIT-SNAPSHOT] Skipping policy {} — status={}", policyId, policy.getStatus());
                continue;
            }
            policyIdsToSave.add(policyId);
            policyRows.add(new Object[]{
                    tenantId, policy.getId(), engagementId,
                    policy.getTitle(), policy.getPolicyRef(), policy.getVersion(),
                    policy.getDescription(),
                    policy.getContentType() != null ? policy.getContentType().name() : "RICH_TEXT",
                    policy.getContentBody(), policy.getEvidenceRecordId(), policy.getExternalUrl(),
                    policy.getOwnerId(), policy.getApprovedAt(), policy.getEffectiveDate(),
                    policy.getNextReviewDate(), policy.getStatus().name(),
                    policy.getControlTags(), tagExpansionService.expandCsv(policy.getControlTags()),
                    policy.getFrameworkRefs(),
                    AuditPolicyInstance.ReviewResult.NOT_REVIEWED.name(),
                    policySnapshotNow, policySnapshotNow, policySnapshotNow
            });
        }
        List<Long> generatedPolicyInstanceIds = jdbcBatchInsertHelper.batchInsertAndGetIds(
                "INSERT INTO audit_policy_instances " +
                        "(tenant_id, original_policy_id, engagement_id, " +
                        "title_snapshot, policy_ref_snapshot, version_snapshot, description_snapshot, " +
                        "content_type_snapshot, content_body_snapshot, evidence_record_id_snapshot, " +
                        "external_url_snapshot, owner_id_snapshot, approved_at_snapshot, " +
                        "effective_date_snapshot, next_review_date_snapshot, policy_status_snapshot, " +
                        "control_tags_snapshot, matched_tags_snapshot, framework_refs_snapshot, " +
                        "review_result, snapshotted_at, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                policyRows);
        for (int i = 0; i < policyIdsToSave.size(); i++) {
            policyIdToInstanceId.put(policyIdsToSave.get(i), generatedPolicyInstanceIds.get(i));
        }

        // Create runtime mappings in batch
        List<Object[]> policyMappingRows = new ArrayList<>();
        for (AuditPolicyControlMapping mapping : allMappings) {
            Long controlInstanceId = originalToInstanceId.get(mapping.getControlId());
            Long policyInstanceId  = policyIdToInstanceId.get(mapping.getPolicyId());

            if (controlInstanceId == null || policyInstanceId == null) continue;

            policyMappingRows.add(new Object[]{
                    policyInstanceId, controlInstanceId, engagementId, tenantId,
                    mapping.getMappingType() != null ? mapping.getMappingType().name() : "DIRECT",
                    mapping.getMappingNote(), mapping.getPolicyId(), mapping.getControlId(),
                    policySnapshotNow, policySnapshotNow
            });
        }
        if (!policyMappingRows.isEmpty()) {
            jdbcBatchInsertHelper.batchInsertAndGetIds(
                    "INSERT INTO audit_policy_instance_control_mappings " +
                            "(policy_instance_id, control_instance_id, engagement_id, tenant_id, " +
                            "mapping_type_snapshot, mapping_note_snapshot, " +
                            "original_policy_id, original_control_id, created_at, updated_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    policyMappingRows);
        }

        log.info("[AUDIT-SNAPSHOT] Snapshotted {} policy instances, {} mappings | engagementId={}",
                policyIdToInstanceId.size(), allMappings.size(), engagementId);
    }

    // ── Result derivation ─────────────────────────────────────────────────────

    /**
     * Derives testResult for one AuditControlInstance based on its linked test results.
     *
     * Called whenever a test result changes.
     * If no tests are linked, the control's testResult is left as-is (manual entry).
     *
     * @param controlInstanceId the control instance to re-evaluate
     * @return the derived TestResult, or null if no tests are linked (manual override)
     */
    @Transactional
    public AuditControlInstance.TestResult deriveControlResult(Long controlInstanceId) {
        List<Long> requiredTestIds =
                controlInstanceTestMappingRepository
                        .findRequiredTestInstanceIdsByControlInstanceId(controlInstanceId);

        if (requiredTestIds.isEmpty()) {
            // No required tests — control result is set manually
            return null;
        }

        List<AuditTestInstance> requiredTests = testInstanceRepository.findAllById(requiredTestIds);

        boolean anyFail    = requiredTests.stream().anyMatch(t -> t.getTestResult() == AuditTestInstance.TestResult.FAIL);
        boolean anyNotRun  = requiredTests.stream().anyMatch(t -> t.getTestResult() == AuditTestInstance.TestResult.NOT_RUN);
        boolean allPass    = requiredTests.stream().allMatch(t -> t.getTestResult() == AuditTestInstance.TestResult.PASS);

        if (anyFail)           return AuditControlInstance.TestResult.INEFFECTIVE;
        if (allPass)           return AuditControlInstance.TestResult.EFFECTIVE;
        if (anyNotRun)         return AuditControlInstance.TestResult.NOT_TESTED;
        return                        AuditControlInstance.TestResult.PARTIALLY_EFFECTIVE;
    }

    /**
     * Bulk re-derives control results for all controls linked to a test.
     * Called after a test result changes to cascade the derived result.
     *
     * @param testInstanceId the test instance whose result changed
     * @param tenantId       for isolation
     */
    @Transactional
    public void cascadeDeriveControlResults(Long testInstanceId, Long tenantId) {
        List<Long> controlInstanceIds =
                controlInstanceTestMappingRepository
                        .findControlInstanceIdsByTestInstanceId(testInstanceId);

        log.info("[AUDIT-DERIVE] Cascading result derivation | testInstanceId={} affects {} controls",
                testInstanceId, controlInstanceIds.size());

        // ADDED: track which engagements are touched to sync their score counters once
        Set<Long> touchedEngagementIds = new HashSet<>();

        for (Long controlInstanceId : controlInstanceIds) {
            controlInstanceRepository.findById(controlInstanceId).ifPresent(control -> {
                AuditControlInstance.TestResult derived = deriveControlResult(controlInstanceId);
                if (derived != null) {
                    control.setTestResult(derived);
                    controlInstanceRepository.save(control);
                    log.debug("[AUDIT-DERIVE] Control {} → {}", controlInstanceId, derived);

                    // Auto-raise a finding when a control becomes INEFFECTIVE due to test FAIL.
                    // Only raise if no open finding already exists for this control.
                    if (derived == AuditControlInstance.TestResult.INEFFECTIVE) {
                        boolean alreadyOpen = findingRepository
                                .findByControlInstanceIdAndTenantId(controlInstanceId, tenantId)
                                .stream()
                                // WITHDRAWN joins the terminal statuses: a finding
                                // withdrawn as recorded-in-error must not be
                                // re-raised by the next derive, or withdrawing it
                                // achieves nothing.
                                .anyMatch(f -> f.getStatus() != AuditFinding.Status.CLOSED
                                        && f.getStatus() != AuditFinding.Status.ACCEPTED_RISK
                                        && f.getStatus() != AuditFinding.Status.WITHDRAWN);
                        if (!alreadyOpen) {
                            String ref = generateFindingRef(tenantId);
                            AuditFinding finding = AuditFinding.builder()
                                    .tenantId(tenantId)
                                    .findingRef(ref)
                                    .engagementId(control.getEngagementId())
                                    .controlInstanceId(controlInstanceId)
                                    .title("Control failed: " + control.getControlCodeSnapshot()
                                            + " — " + control.getControlNameSnapshot())
                                    .description("Test result cascaded to INEFFECTIVE for control "
                                            + control.getControlCodeSnapshot() + ".")
                                    .severity(AuditFinding.Severity.MEDIUM)
                                    .findingType(AuditFinding.FindingType.CONTROL_DEFICIENCY)
                                    // Raised by the runner, not a person.
                                    .source(AuditFinding.Source.AUTOMATED)
                                    .status(AuditFinding.Status.OPEN)
                                    .frameworkRef(control.getFrameworkRefSnapshot())
                                    // Owner is who must remediate. Auditee first, then
                                    // the control's auditor, then the engagement lead —
                                    // never null, because an ownerless issue lands in
                                    // nobody's queue and is silently never worked.
                                    .ownerId(resolveOwner(control))
                                    // WAS: fell back to `tenantId` when no auditor and no
                                    // tester existed — a TENANT id written into a USER
                                    // column, so the finding was attributed to whichever
                                    // user happened to share that id, typically in another
                                    // tenant entirely. Falls back to the engagement lead
                                    // auditor instead, and to the owner as a last resort.
                                    .raisedBy(resolveRaiser(control))
                                    .raisedAt(LocalDateTime.now())
                                    .build();
                            findingRepository.save(finding);
                            log.info("[AUDIT-DERIVE] Auto-finding raised | controlInstanceId={} findingRef={}",
                                    controlInstanceId, ref);
                            self.autoEscalateToIssue(finding, tenantId);
                        }
                    }
                }
                touchedEngagementIds.add(control.getEngagementId());
            });
        }

        // ADDED: sync score counters for every engagement touched in this cascade
        for (Long engagementId : touchedEngagementIds) {
            syncEngagementScore(engagementId, tenantId);
        }
    }

    // ── ADDED: syncEngagementScore ────────────────────────────────────────────

    /**
     * Syncs all denormalized score counters on AuditEngagement after any test result change
     * or finding status change.
     *
     * Writes to: totalControls, testedControls, passedControls, failedControls,
     *            notApplicableControls, openFindingCount.
     *
     * These fields power the overview tab KPI strip (ui_layouts id=38):
     *   ui_form_fields 118 (passedControls), 119 (failedControls), 120 (openFindingCount),
     *                  223 (totalControls), 224 (testedControls).
     *
     * Also called from AuditFindingController after escalate/close/accept-risk,
     * and from AuditInstanceController.reviewPolicy() after INADEQUATE result.
     */
    @Transactional
    public void syncEngagementScore(Long engagementId, Long tenantId) {
        engagementRepository.findById(engagementId).ifPresent(engagement -> {
            long total         = controlInstanceRepository.countByEngagementId(engagementId);
            long tested        = controlInstanceRepository.countTestedByEngagementId(engagementId);
            long passed        = controlInstanceRepository.countByEngagementIdAndTestResult(
                    engagementId, AuditControlInstance.TestResult.EFFECTIVE);
            long failed        = controlInstanceRepository.countByEngagementIdAndTestResult(
                    engagementId, AuditControlInstance.TestResult.INEFFECTIVE);
            long notApplicable = controlInstanceRepository.countByEngagementIdAndTestResult(
                    engagementId, AuditControlInstance.TestResult.NOT_APPLICABLE);
            long openFindings  = findingRepository.countByEngagementIdAndStatusAndTenantId(
                    engagementId, AuditFinding.Status.OPEN, tenantId)
                    + findingRepository.countByEngagementIdAndStatusAndTenantId(
                    engagementId, AuditFinding.Status.IN_REMEDIATION, tenantId);

            engagement.setTotalControls((int) total);
            engagement.setTestedControls((int) tested);
            engagement.setPassedControls((int) passed);
            engagement.setFailedControls((int) failed);
            engagement.setNotApplicableControls((int) notApplicable);
            engagement.setOpenFindingCount((int) openFindings);
            engagementRepository.save(engagement);

            log.info("[AUDIT-SCORE] Synced | engagementId={} total={} tested={} passed={} failed={} openFindings={}",
                    engagementId, total, tested, passed, failed, openFindings);
        });
    }

    // ── Helper — who must remediate this finding ─────────────────────────────

    /**
     * Never returns the tenant id, and never silently returns null without the
     * caller noticing: an issue with no owner sits in nobody's queue.
     * Order: the auditee who owns the control, then the auditor who tested it,
     * then the engagement's lead auditor.
     */
    private Long resolveOwner(AuditControlInstance control) {
        if (control.getAuditeeAssignedUserId() != null) return control.getAuditeeAssignedUserId();
        if (control.getAssignedAuditorId()     != null) return control.getAssignedAuditorId();
        return engagementRepository.findById(control.getEngagementId())
                .map(e -> e.getLeadAuditorId()).orElse(null);
    }

    /** Who raised it — the auditor who tested, else the engagement lead. */
    private Long resolveRaiser(AuditControlInstance control) {
        if (control.getAssignedAuditorId() != null) return control.getAssignedAuditorId();
        if (control.getTestedBy()          != null) return control.getTestedBy();
        return engagementRepository.findById(control.getEngagementId())
                .map(e -> e.getLeadAuditorId()).orElse(null);
    }

    // ── Helper — auto-escalate a finding to Issue Management ─────────────────
    /**
     * REQUIRES_NEW is load-bearing, not tidiness.
     *
     * IssueService.create runs @Transactional. When it throws inside the
     * caller's transaction, catching the exception does NOT clear the
     * rollback-only mark Spring has already set — the outer commit then dies
     * with UnexpectedRollbackException and the whole check run 500s, discarding
     * a perfectly good test result and finding along with it.
     *
     * A separate transaction keeps escalation failures where they belong: the
     * finding is still saved, the run still completes, and the failure is a log
     * line rather than a lost result.
     */
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void autoEscalateToIssue(AuditFinding finding, Long tenantId) {
        // An issue with no owner and no creator cannot be worked and cannot be
        // routed by the workflow engine. Better to leave the finding standing
        // and log loudly than to create an orphan nobody sees.
        if (finding.getOwnerId() == null || finding.getRaisedBy() == null) {
            log.warn("[AUDIT-DERIVE] Not escalating finding {} — ownerId={} raisedBy={}. "
                            + "Assign an auditee or a lead auditor to this engagement, then escalate manually.",
                    finding.getFindingRef(), finding.getOwnerId(), finding.getRaisedBy());
            return;
        }

        // Validate the actors HERE, before entering IssueService.create.
        //
        // create() is @Transactional and joins this transaction, so a guard that
        // throws inside it marks the shared transaction rollback-only. The catch
        // below then swallows the exception and the method carries on — but the
        // transaction is already poisoned, and the outer commit dies with
        // UnexpectedRollbackException. The visible symptom was a 500 on every
        // FAILING check while passing ones succeeded, because only a failure
        // reaches escalation at all.
        //
        // Checking first means a stale actor id skips escalation cleanly and the
        // check result, the evidence and the finding all still commit.
        if (!isActiveUserOfTenant(finding.getRaisedBy(), tenantId)
                || !isActiveUserOfTenant(finding.getOwnerId(), tenantId)) {
            log.warn("[AUDIT-DERIVE] Not escalating finding {} — ownerId={} or raisedBy={} is not an "
                            + "active user of tenant {}. The finding stands; escalate it manually once "
                            + "the engagement has a valid lead auditor and control owner.",
                    finding.getFindingRef(), finding.getOwnerId(), finding.getRaisedBy(), tenantId);
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
            req.setWorkflowId(findingWorkflowId(tenantId));
            var issueResp = issueService.create(req, finding.getRaisedBy(), tenantId);
            finding.setLinkedIssueId(issueResp.getId());
            findingRepository.save(finding);
            log.info("[AUDIT-DERIVE] Auto-escalated to issue | findingId={} issueId={}",
                    finding.getId(), issueResp.getId());
        } catch (Exception ex) {
            log.warn("[AUDIT-DERIVE] Auto-escalate failed for finding {} — {}",
                    finding.getId(), ex.getMessage());
        }
    }

    /**
     * Workflow used for issues escalated from an audit finding.
     *
     * Resolved by name, not by a hardcoded id. Workflow 15 "Issue Remediation
     * Lifecycle" opens with a triage step resolved ENTITY_CREATOR, which is
     * meaningless for an issue nobody filed — and it was that step that turned a
     * bad createdBy into a task in a stranger's inbox. The finding workflow
     * starts at the owner instead and has the auditor validate the fix.
     *
     * Falls back to the generic issue workflow if the finding one is absent, so
     * escalation still happens on an environment where seed 17 has not run.
     */
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

    // ── Helper — collision-safe finding ref ───────────────────────────────────
    private String generateFindingRef(Long tenantId) {
        long count = findingRepository.countByTenantId(tenantId) + 1;
        String candidate = String.format("FND-%d-%04d", Year.now().getValue(), count);
        while (findingRepository.existsByFindingRefAndTenantId(candidate, tenantId)) {
            candidate = String.format("FND-%d-%04d", Year.now().getValue(), ++count);
        }
        return candidate;
    }
    /**
     * True when the id is a live user of this tenant.
     *
     * Deliberately read-only and called BEFORE any @Transactional service, so a
     * stale actor id cannot mark the shared transaction rollback-only.
     */
    private boolean isActiveUserOfTenant(Long userId, Long tenantId) {
        if (userId == null || tenantId == null) return false;
        return userRepository.findById(userId)
                .filter(u -> !u.isDeleted())
                .map(u -> tenantId.equals(u.getTenantId()))
                .orElse(false);
    }

}