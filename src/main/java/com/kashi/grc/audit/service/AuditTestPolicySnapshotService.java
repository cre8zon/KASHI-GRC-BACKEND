package com.kashi.grc.audit.service;

import com.kashi.grc.audit.domain.*;
import com.kashi.grc.audit.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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

    private final AuditTestRepository                         testRepository;
    private final AuditControlTestMappingRepository           controlTestMappingRepository;
    private final AuditTestInstanceRepository                  testInstanceRepository;
    private final AuditControlInstanceTestMappingRepository    controlInstanceTestMappingRepository;
    private final AuditControlInstanceRepository               controlInstanceRepository;

    private final AuditPolicyRepository                        policyRepository;
    private final AuditPolicyControlMappingRepository          policyControlMappingRepository;
    private final AuditPolicyInstanceRepository                policyInstanceRepository;
    private final AuditPolicyInstanceControlMappingRepository  policyInstanceControlMappingRepository;

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

        // Snapshot each unique test → one AuditTestInstance per test
        Map<Long, Long> testIdToInstanceId = new HashMap<>();
        for (Long testId : uniqueTestIds) {
            testRepository.findById(testId).ifPresent(test -> {
                AuditTestInstance instance = AuditTestInstance.builder()
                        .originalTestId(test.getId())
                        .engagementId(engagementId)
                        .tenantId(tenantId)
                        .testNameSnapshot(test.getName())
                        .testRefSnapshot(test.getTestRef())
                        .descriptionSnapshot(test.getDescription())
                        .testProcedureSnapshot(test.getTestProcedure())
                        .evidenceGuidanceSnapshot(test.getEvidenceGuidance())
                        .frameworkRefSnapshot(test.getFrameworkRef())
                        .controlTagSnapshot(test.getControlTag())
                        .automationTypeSnapshot(test.getAutomationType() != null
                                ? test.getAutomationType().name() : "MANUAL")
                        .automationKeySnapshot(test.getAutomationKey())
                        .frequencySnapshot(test.getFrequency() != null
                                ? test.getFrequency().name() : "ANNUAL")
                        .testResult(AuditTestInstance.TestResult.NOT_RUN)
                        .snapshottedAt(LocalDateTime.now())
                        .build();
                testInstanceRepository.save(instance);
                testIdToInstanceId.put(testId, instance.getId());
                log.debug("[AUDIT-SNAPSHOT] Snapshotted test | testId={} instanceId={}",
                        testId, instance.getId());
            });
        }

        // Create runtime mappings: controlInstance ↔ testInstance
        for (AuditControlTestMapping mapping : allMappings) {
            Long controlInstanceId = originalToInstanceId.get(mapping.getControlId());
            Long testInstanceId    = testIdToInstanceId.get(mapping.getTestId());

            if (controlInstanceId == null || testInstanceId == null) {
                log.warn("[AUDIT-SNAPSHOT] Skipping mapping — missing instance | " +
                        "controlId={} testId={}", mapping.getControlId(), mapping.getTestId());
                continue;
            }

            AuditControlInstanceTestMapping runtimeMapping = AuditControlInstanceTestMapping.builder()
                    .controlInstanceId(controlInstanceId)
                    .testInstanceId(testInstanceId)
                    .engagementId(engagementId)
                    .tenantId(tenantId)
                    .isRequired(mapping.isRequired())
                    .orderNo(mapping.getOrderNo())
                    .mappingNoteSnapshot(mapping.getMappingNote())
                    .originalControlId(mapping.getControlId())
                    .originalTestId(mapping.getTestId())
                    .build();
            controlInstanceTestMappingRepository.save(runtimeMapping);
        }

        log.info("[AUDIT-SNAPSHOT] Snapshotted {} test instances, {} mappings | engagementId={}",
                testIdToInstanceId.size(), allMappings.size(), engagementId);
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

        // Only snapshot APPROVED policies — draft policies are not yet in effect
        Map<Long, Long> policyIdToInstanceId = new HashMap<>();
        for (Long policyId : uniquePolicyIds) {
            policyRepository.findById(policyId).ifPresent(policy -> {
                // Skip non-approved policies — they shouldn't be evaluated in an audit
                if (policy.getStatus() != AuditPolicy.PolicyStatus.APPROVED &&
                        policy.getStatus() != AuditPolicy.PolicyStatus.UNDER_REVIEW) {
                    log.debug("[AUDIT-SNAPSHOT] Skipping policy {} — status={}",
                            policyId, policy.getStatus());
                    return;
                }

                AuditPolicyInstance instance = AuditPolicyInstance.builder()
                        .originalPolicyId(policy.getId())
                        .engagementId(engagementId)
                        .tenantId(tenantId)
                        .titleSnapshot(policy.getTitle())
                        .policyRefSnapshot(policy.getPolicyRef())
                        .versionSnapshot(policy.getVersion())
                        .descriptionSnapshot(policy.getDescription())
                        .contentTypeSnapshot(policy.getContentType() != null
                                ? policy.getContentType().name() : "RICH_TEXT")
                        .contentBodySnapshot(policy.getContentBody())
                        .evidenceRecordIdSnapshot(policy.getEvidenceRecordId())
                        .externalUrlSnapshot(policy.getExternalUrl())
                        .ownerIdSnapshot(policy.getOwnerId())
                        .approvedAtSnapshot(policy.getApprovedAt())
                        .effectiveDateSnapshot(policy.getEffectiveDate())
                        .nextReviewDateSnapshot(policy.getNextReviewDate())
                        .policyStatusSnapshot(policy.getStatus().name())
                        .controlTagsSnapshot(policy.getControlTags())
                        .frameworkRefsSnapshot(policy.getFrameworkRefs())
                        .reviewResult(AuditPolicyInstance.ReviewResult.NOT_REVIEWED)
                        .snapshottedAt(LocalDateTime.now())
                        .build();
                policyInstanceRepository.save(instance);
                policyIdToInstanceId.put(policyId, instance.getId());
                log.debug("[AUDIT-SNAPSHOT] Snapshotted policy | policyId={} instanceId={}",
                        policyId, instance.getId());
            });
        }

        // Create runtime mappings: policyInstance ↔ controlInstance
        for (AuditPolicyControlMapping mapping : allMappings) {
            Long controlInstanceId = originalToInstanceId.get(mapping.getControlId());
            Long policyInstanceId  = policyIdToInstanceId.get(mapping.getPolicyId());

            if (controlInstanceId == null || policyInstanceId == null) continue;

            AuditPolicyInstanceControlMapping runtimeMapping = AuditPolicyInstanceControlMapping.builder()
                    .policyInstanceId(policyInstanceId)
                    .controlInstanceId(controlInstanceId)
                    .engagementId(engagementId)
                    .tenantId(tenantId)
                    .mappingTypeSnapshot(mapping.getMappingType() != null
                            ? mapping.getMappingType().name() : "DIRECT")
                    .mappingNoteSnapshot(mapping.getMappingNote())
                    .originalPolicyId(mapping.getPolicyId())
                    .originalControlId(mapping.getControlId())
                    .build();
            policyInstanceControlMappingRepository.save(runtimeMapping);
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

        for (Long controlInstanceId : controlInstanceIds) {
            controlInstanceRepository.findById(controlInstanceId).ifPresent(control -> {
                AuditControlInstance.TestResult derived = deriveControlResult(controlInstanceId);
                if (derived != null) {
                    control.setTestResult(derived);
                    controlInstanceRepository.save(control);
                    log.debug("[AUDIT-DERIVE] Control {} → {}", controlInstanceId, derived);
                }
            });
        }
    }
}