package com.kashi.grc.audit.controller;

import com.kashi.grc.audit.domain.*;
import com.kashi.grc.audit.dto.request.AuditControlTestRequest;
import com.kashi.grc.audit.repository.*;
import com.kashi.grc.audit.service.AuditTestPolicySnapshotService;
import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.issue.domain.Issue;
import com.kashi.grc.issue.dto.IssueRequest;
import com.kashi.grc.issue.service.IssueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.Year;
import java.util.*;
import java.util.stream.Collectors;

/**
 * AuditInstanceController — direct-access endpoints for audit instance detail pages.
 *
 * These power the UMP full-page detail views at:
 *   /module/audit_control_instance/:id    → UniversalModulePage → audit_control_instance_detail
 *   /module/audit_test_instance/:id       → UniversalModulePage → audit_test_instance_detail
 *   /module/audit_policy_instance/:id     → UniversalModulePage → audit_policy_instance_detail
 *
 * All endpoints use direct entity ID lookup — no engagementId required.
 * Tenant-scoped via tenantId from the logged-in user context.
 */
@Slf4j
@RestController
@Tag(name = "Audit Instances", description = "Direct-access endpoints for audit instance detail pages")
@RequiredArgsConstructor
public class AuditInstanceController {

    private final AuditControlInstanceRepository            controlRepo;
    private final com.kashi.grc.audit.service.ControlAccessGuard controlAccessGuard;
    private final AuditTestInstanceRepository               testRepo;
    private final AuditPolicyInstanceRepository             policyRepo;
    private final AuditControlInstanceTestMappingRepository ctrlTestMappingRepo;
    private final AuditPolicyInstanceControlMappingRepository policyCtrlMappingRepo;
    private final AuditTestPolicySnapshotService             snapshotService;
    private final UtilityService                            utilityService;
    private final AuditFindingRepository                    findingRepo;
    private final com.kashi.grc.workflow.repository.WorkflowRepository workflowRepository;
    private final IssueService                              issueService;
    private final com.kashi.grc.audit.repository.AuditEngagementRepository engagementRepo;

    // ══════════════════════════════════════════════════════════════════════════
    // CONTROL INSTANCES — /v1/audit/control-instances/{id}
    // ══════════════════════════════════════════════════════════════════════════

    @GetMapping("/v1/audit/control-instances/{id}")
    @Operation(summary = "Get control instance by ID — flat response for UMP overview tab")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getControlInstance(@PathVariable Long id) {
        var ctx  = utilityService.getLoggedInDataContext();
        var ctrl = controlRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AuditControlInstance", id));

        Map<String, Object> result = buildControlMap(ctrl);

        // AuditControlInstance has no evidence-guidance column of its own — the
        // guidance lives on each mapped AuditTestInstance. Roll them up here so
        // the Overview tab's "Evidence required" field stops rendering empty.
        // Kept out of buildControlMap so recordControlTestResult stays query-free.
        List<Long> guidanceTestIds = ctrlTestMappingRepo
                .findByControlInstanceIdOrderByOrderNoAsc(id).stream()
                .map(AuditControlInstanceTestMapping::getTestInstanceId)
                .distinct()
                .collect(Collectors.toList());
        if (!guidanceTestIds.isEmpty()) {
            String rolledUpGuidance = testRepo.findAllById(guidanceTestIds).stream()
                    .map(AuditTestInstance::getEvidenceGuidanceSnapshot)
                    .filter(g -> g != null && !g.isBlank())
                    .distinct()
                    .collect(Collectors.joining("\n\n"));
            if (!rolledUpGuidance.isBlank()) {
                result.put("evidenceGuidanceSnapshot", rolledUpGuidance);
            }
        }

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/v1/audit/control-instances/{id}/tests")
    @Operation(summary = "List all test instances mapped to this control")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getControlTests(
            @PathVariable Long id) {

        List<AuditControlInstanceTestMapping> mappings =
                ctrlTestMappingRepo.findByControlInstanceIdOrderByOrderNoAsc(id);

        // Batch-load every mapped test in one query. The previous findById inside
        // the stream was an N+1 — one extra query per mapped test on every open.
        List<Long> testInstanceIds = mappings.stream()
                .map(AuditControlInstanceTestMapping::getTestInstanceId)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, AuditTestInstance> testsById = testInstanceIds.isEmpty()
                ? Map.of()
                : testRepo.findAllById(testInstanceIds).stream()
                  .collect(Collectors.toMap(AuditTestInstance::getId, t -> t));

        List<Map<String, Object>> result = mappings.stream().map(m -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("mappingId",          m.getId());
            row.put("testInstanceId",     m.getTestInstanceId());
            row.put("isRequired",         m.isRequired());
            row.put("orderNo",            m.getOrderNo());
            row.put("mappingNoteSnapshot",m.getMappingNoteSnapshot());
            AuditTestInstance t = testsById.get(m.getTestInstanceId());
            if (t != null) {
                row.put("testNameSnapshot",        t.getTestNameSnapshot());
                row.put("testRefSnapshot",         t.getTestRefSnapshot());
                row.put("testResult",              t.getTestResult());
                row.put("automationTypeSnapshot",  t.getAutomationTypeSnapshot());
                row.put("controlTagSnapshot",      t.getControlTagSnapshot());
                row.put("runAt",                   t.getRunAt());
                row.put("runBySystem",             t.isRunBySystem());
                row.put("automationResult",        t.getAutomationRawResult());
                // Added so the Fieldwork accordion opens without a second call,
                // and so the Evidence tab can list what the auditee must upload.
                // NOTE: testerNotes / failureDetail / exceptionReason are auditor
                // commentary — the Evidence tab must not render them for auditees.
                row.put("runByUserId",             t.getRunByUserId());
                row.put("descriptionSnapshot",     t.getDescriptionSnapshot());
                row.put("testProcedureSnapshot",   t.getTestProcedureSnapshot());
                row.put("evidenceGuidanceSnapshot",t.getEvidenceGuidanceSnapshot());
                row.put("frequencySnapshot",       t.getFrequencySnapshot());
                row.put("testerNotes",             t.getTesterNotes());
                row.put("failureDetail",           t.getFailureDetail());
                row.put("exceptionReason",         t.getExceptionReason());
                row.put("affectedControlCount",    t.getAffectedControlCount());
            }
            return row;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/v1/audit/control-instances/{id}/policies")
    @Operation(summary = "List all policy instances covering this control")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getControlPolicies(
            @PathVariable Long id) {

        List<AuditPolicyInstanceControlMapping> mappings =
                policyCtrlMappingRepo.findByControlInstanceId(id);

        // Batch-load — same N+1 fix as getControlTests above.
        List<Long> policyInstanceIds = mappings.stream()
                .map(AuditPolicyInstanceControlMapping::getPolicyInstanceId)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, AuditPolicyInstance> policiesById = policyInstanceIds.isEmpty()
                ? Map.of()
                : policyRepo.findAllById(policyInstanceIds).stream()
                  .collect(Collectors.toMap(AuditPolicyInstance::getId, p -> p));

        List<Map<String, Object>> result = mappings.stream().map(m -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("mappingId",            m.getId());
            row.put("policyInstanceId",     m.getPolicyInstanceId());
            row.put("reviewContribution",   m.getReviewContribution());
            row.put("mappingTypeSnapshot",  m.getMappingTypeSnapshot());
            row.put("mappingNoteSnapshot",  m.getMappingNoteSnapshot());
            AuditPolicyInstance p = policiesById.get(m.getPolicyInstanceId());
            if (p != null) {
                row.put("titleSnapshot",        p.getTitleSnapshot());
                row.put("policyRefSnapshot",    p.getPolicyRefSnapshot());
                row.put("versionSnapshot",      p.getVersionSnapshot());
                row.put("reviewResult",         p.getReviewResult());
                row.put("contentTypeSnapshot",  p.getContentTypeSnapshot());
                row.put("policyStatusSnapshot", p.getPolicyStatusSnapshot());
                // Added for the Fieldwork policy rows.
                row.put("auditorNotes",         p.getAuditorNotes());
                row.put("reviewedAt",           p.getReviewedAt());
                row.put("externalUrlSnapshot",  p.getExternalUrlSnapshot());
                row.put("nextReviewDateSnapshot", p.getNextReviewDateSnapshot());
                row.put("contentBodySnapshot",  p.getContentBodySnapshot());
            }
            return row;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PutMapping("/v1/audit/control-instances/{id}/test-result")
    @Operation(summary = "Record manual test result on a control instance")
    public ResponseEntity<ApiResponse<Map<String, Object>>> recordControlTestResult(
            @PathVariable Long id,
            @RequestBody AuditControlTestRequest req) {

        var ctx  = utilityService.getLoggedInDataContext();
        var ctrl = controlRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AuditControlInstance", id));

        // Same hole as the auditee path: an unassigned control was testable by
        // anyone with the permission. Assignee, section auditor or lead auditor.
        controlAccessGuard.requireCanRecordResult(ctrl, ctx.getId());

        ctrl.setTestResult(req.getTestResult());
        if (req.getTestNotes() != null) ctrl.setTestNotes(req.getTestNotes());
        ctrl.setTestedAt(LocalDateTime.now());
        ctrl.setTestedBy(ctx.getId());
        controlRepo.save(ctrl);
        log.info("[CTRL-INST] Test result set | id={} result={} by={}", id, req.getTestResult(), ctx.getId());
        return ResponseEntity.ok(ApiResponse.success(buildControlMap(ctrl)));
    }

    @PutMapping("/v1/audit/control-instances/{id}/assign-auditee")
    @Operation(summary = "Assign auditee to a control instance")
    public ResponseEntity<ApiResponse<Void>> assignControlAuditee(
            @PathVariable Long id,
            @RequestBody Map<String, Long> body) {

        var ctx  = utilityService.getLoggedInDataContext();
        var ctrl = controlRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AuditControlInstance", id));
        ctrl.setAuditeeAssignedUserId(body.get("auditeeUserId"));
        controlRepo.save(ctrl);
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PostMapping("/v1/audit/control-instances/{id}/submit-evidence")
    @Operation(summary = "Mark evidence as submitted for this control")
    public ResponseEntity<ApiResponse<Void>> submitControlEvidence(@PathVariable Long id) {
        var ctx  = utilityService.getLoggedInDataContext();
        var ctrl = controlRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AuditControlInstance", id));

        // WAS: only blocked when the control HAD an assignee, so an unassigned
        // control -- the normal state until someone bulk-assigns a section -- was
        // open to anyone holding the permission. Now the assignee, the section
        // owner or the engagement owner may act, and nobody else.
        controlAccessGuard.requireCanSubmitEvidence(ctrl, ctx.getId());

        ctrl.setAuditeeEvidenceSubmitted(true);
        ctrl.setAuditeeEvidenceSubmittedAt(LocalDateTime.now());
        controlRepo.save(ctrl);
        return ResponseEntity.ok(ApiResponse.success());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST INSTANCES — /v1/audit/test-instances/{id}
    // ══════════════════════════════════════════════════════════════════════════

    @GetMapping("/v1/audit/test-instances/{id}")
    @Operation(summary = "Get test instance by ID — flat response for UMP overview tab")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getTestInstance(@PathVariable Long id) {
        var ctx  = utilityService.getLoggedInDataContext();
        var test = testRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AuditTestInstance", id));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id",                      test.getId());
        result.put("engagementId",            test.getEngagementId());
        // Breadcrumb support — parent engagement name for generic breadcrumb
        if (test.getEngagementId() != null) {
            engagementRepo.findById(test.getEngagementId()).ifPresent(eng -> {
                result.put("engagementName", eng.getName());
                result.put("engagementRef",  eng.getEngagementRef());
            });
        }
        result.put("originalTestId",          test.getOriginalTestId());
        result.put("testNameSnapshot",        test.getTestNameSnapshot());
        result.put("testRefSnapshot",         test.getTestRefSnapshot());
        result.put("descriptionSnapshot",     test.getDescriptionSnapshot());
        result.put("testProcedureSnapshot",   test.getTestProcedureSnapshot());
        result.put("evidenceGuidanceSnapshot",test.getEvidenceGuidanceSnapshot());
        result.put("frameworkRefSnapshot",    test.getFrameworkRefSnapshot());
        result.put("controlTagSnapshot",      test.getControlTagSnapshot());
        result.put("automationTypeSnapshot",  test.getAutomationTypeSnapshot());
        result.put("automationKeySnapshot",   test.getAutomationKeySnapshot());
        result.put("frequencySnapshot",       test.getFrequencySnapshot());
        result.put("testResult",              test.getTestResult());
        result.put("runAt",                   test.getRunAt());
        result.put("runByUserId",             test.getRunByUserId());
        result.put("runBySystem",             test.isRunBySystem());
        result.put("testerNotes",             test.getTesterNotes());
        result.put("failureDetail",           test.getFailureDetail());
        result.put("exceptionReason",         test.getExceptionReason());
        result.put("automationRawResult",     test.getAutomationRawResult());
        result.put("automationRunAt",         test.getAutomationRunAt());
        result.put("affectedControlCount",    test.getAffectedControlCount());
        result.put("snapshottedAt",           test.getSnapshottedAt());

        List<Long> controlIds = ctrlTestMappingRepo.findControlInstanceIdsByTestInstanceId(id);
        boolean isAssigned = !controlIds.isEmpty() &&
                controlRepo.findAllById(controlIds).stream()
                        .anyMatch(c -> ctx.getId().equals(c.getAssignedAuditorId())
                                || c.getAssignedAuditorId() == null);
        result.put("isAssignedToCurrentUser", isAssigned);

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/v1/audit/test-instances/{id}/controls")
    @Operation(summary = "List all control instances that this test is mapped to")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getTestControls(
            @PathVariable Long id) {

        List<AuditControlInstanceTestMapping> mappings =
                ctrlTestMappingRepo.findByTestInstanceId(id);

        // Batch-load — same N+1 fix as getControlTests above.
        List<Long> mappedControlIds = mappings.stream()
                .map(AuditControlInstanceTestMapping::getControlInstanceId)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, AuditControlInstance> mappedControlsById = mappedControlIds.isEmpty()
                ? Map.of()
                : controlRepo.findAllById(mappedControlIds).stream()
                  .collect(Collectors.toMap(AuditControlInstance::getId, c -> c));

        List<Map<String, Object>> result = mappings.stream().map(m -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("mappingId",           m.getId());
            row.put("controlInstanceId",   m.getControlInstanceId());
            row.put("isRequired",          m.isRequired());
            row.put("orderNo",             m.getOrderNo());
            row.put("mappingNoteSnapshot", m.getMappingNoteSnapshot());
            AuditControlInstance c = mappedControlsById.get(m.getControlInstanceId());
            if (c != null) {
                row.put("controlCodeSnapshot",c.getControlCodeSnapshot());
                row.put("controlNameSnapshot",c.getControlNameSnapshot());
                row.put("controlTagSnapshot", c.getControlTagSnapshot());
                row.put("testResult",         c.getTestResult());
                row.put("sectionBreadcrumb",  c.getSectionBreadcrumbSnapshot());
                row.put("engagementId",       c.getEngagementId());
            }
            return row;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PutMapping("/v1/audit/test-instances/{id}/result")
    @Operation(summary = "Set test result — cascades to all mapped control instances")
    public ResponseEntity<ApiResponse<Map<String, Object>>> setTestResult(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {

        var ctx  = utilityService.getLoggedInDataContext();
        var test = testRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AuditTestInstance", id));

        boolean hasAssignPermission = ctx.getRoles().stream()
                .flatMap(r -> r.getPermissions().stream())
                .anyMatch(p -> "audit:control:assign-auditor".equals(p.getCode()));
        if (!hasAssignPermission) {
            List<Long> controlInstanceIds = ctrlTestMappingRepo
                    .findControlInstanceIdsByTestInstanceId(id);
            // The old `|| c.getAssignedAuditorId() == null` made every unassigned
            // control testable by anyone. Delegating to the guard keeps section
            // owners and the lead auditor able to act without opening it up.
            boolean isAssigned = !controlInstanceIds.isEmpty() &&
                    controlRepo.findAllById(controlInstanceIds).stream()
                            .anyMatch(c -> controlAccessGuard.canAct(c, ctx.getId(), false));
            if (!isAssigned) {
                throw new com.kashi.grc.common.exception.BusinessException(
                        "TEST_NOT_ASSIGNED",
                        "You are not assigned to any control mapped to this test");
            }
        }

        AuditTestInstance.TestResult newResult =
                AuditTestInstance.TestResult.valueOf(body.get("testResult"));

        test.setTestResult(newResult);
        test.setRunAt(LocalDateTime.now());
        test.setRunByUserId(ctx.getId());
        test.setRunBySystem(false);
        if (body.containsKey("testerNotes"))     test.setTesterNotes(body.get("testerNotes"));
        if (body.containsKey("failureDetail"))   test.setFailureDetail(body.get("failureDetail"));
        if (body.containsKey("exceptionReason")) test.setExceptionReason(body.get("exceptionReason"));
        testRepo.save(test);

        snapshotService.cascadeDeriveControlResults(id, ctx.getTenantId());

        int affectedCount = ctrlTestMappingRepo.findControlInstanceIdsByTestInstanceId(id).size();
        test.setAffectedControlCount(affectedCount);
        testRepo.save(test);

        log.info("[TEST-INSTANCE] Result set | id={} | result={} | affectedControls={}",
                id, newResult, affectedCount);

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "testInstanceId",    id,
                "testResult",        newResult,
                "affectedControls",  affectedCount
        )));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // POLICY INSTANCES — /v1/audit/policy-instances/{id}
    // ══════════════════════════════════════════════════════════════════════════

    @GetMapping("/v1/audit/policy-instances/{id}")
    @Operation(summary = "Get policy instance by ID — flat response for UMP overview tab")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPolicyInstance(@PathVariable Long id) {
        var policy = policyRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AuditPolicyInstance", id));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id",                      policy.getId());
        result.put("engagementId",            policy.getEngagementId());
        // Breadcrumb support — parent engagement name for generic breadcrumb
        if (policy.getEngagementId() != null) {
            engagementRepo.findById(policy.getEngagementId()).ifPresent(eng -> {
                result.put("engagementName", eng.getName());
                result.put("engagementRef",  eng.getEngagementRef());
            });
        }
        result.put("originalPolicyId",        policy.getOriginalPolicyId());
        result.put("titleSnapshot",           policy.getTitleSnapshot());
        result.put("policyRefSnapshot",       policy.getPolicyRefSnapshot());
        result.put("versionSnapshot",         policy.getVersionSnapshot());
        result.put("descriptionSnapshot",     policy.getDescriptionSnapshot());
        result.put("contentTypeSnapshot",     policy.getContentTypeSnapshot());
        result.put("contentBodySnapshot",     policy.getContentBodySnapshot());
        result.put("evidenceRecordIdSnapshot",policy.getEvidenceRecordIdSnapshot());
        result.put("externalUrlSnapshot",     policy.getExternalUrlSnapshot());
        result.put("ownerIdSnapshot",         policy.getOwnerIdSnapshot());
        result.put("approvedAtSnapshot",      policy.getApprovedAtSnapshot());
        result.put("effectiveDateSnapshot",   policy.getEffectiveDateSnapshot());
        result.put("nextReviewDateSnapshot",  policy.getNextReviewDateSnapshot());
        result.put("policyStatusSnapshot",    policy.getPolicyStatusSnapshot());
        result.put("controlTagsSnapshot",     policy.getControlTagsSnapshot());
        result.put("frameworkRefsSnapshot",   policy.getFrameworkRefsSnapshot());
        result.put("reviewResult",            policy.getReviewResult());
        result.put("reviewedById",            policy.getReviewedById());
        result.put("reviewedAt",              policy.getReviewedAt());
        result.put("auditorNotes",            policy.getAuditorNotes());
        result.put("snapshottedAt",           policy.getSnapshottedAt());

        var ctx2 = utilityService.getLoggedInDataContext();
        List<Long> policyControlIds = policyCtrlMappingRepo.findByPolicyInstanceId(id)
                .stream().map(m -> m.getControlInstanceId()).toList();
        boolean isPolicyAssigned = !policyControlIds.isEmpty() &&
                controlRepo.findAllById(policyControlIds).stream()
                        .anyMatch(c -> ctx2.getId().equals(c.getAssignedAuditorId())
                                || c.getAssignedAuditorId() == null);
        result.put("isAssignedToCurrentUser", isPolicyAssigned);

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/v1/audit/policy-instances/{id}/controls")
    @Operation(summary = "List all control instances covered by this policy")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getPolicyControls(
            @PathVariable Long id) {

        List<AuditPolicyInstanceControlMapping> mappings =
                policyCtrlMappingRepo.findByPolicyInstanceId(id);

        // Batch-load — same N+1 fix as getControlTests above.
        List<Long> coveredControlIds = mappings.stream()
                .map(AuditPolicyInstanceControlMapping::getControlInstanceId)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, AuditControlInstance> coveredControlsById = coveredControlIds.isEmpty()
                ? Map.of()
                : controlRepo.findAllById(coveredControlIds).stream()
                  .collect(Collectors.toMap(AuditControlInstance::getId, c -> c));

        List<Map<String, Object>> result = mappings.stream().map(m -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("mappingId",           m.getId());
            row.put("controlInstanceId",   m.getControlInstanceId());
            row.put("reviewContribution",  m.getReviewContribution());
            row.put("mappingTypeSnapshot", m.getMappingTypeSnapshot());
            row.put("mappingNoteSnapshot", m.getMappingNoteSnapshot());
            AuditControlInstance c = coveredControlsById.get(m.getControlInstanceId());
            if (c != null) {
                row.put("controlCodeSnapshot", c.getControlCodeSnapshot());
                row.put("controlNameSnapshot", c.getControlNameSnapshot());
                row.put("controlTagSnapshot",  c.getControlTagSnapshot());
                row.put("testResult",          c.getTestResult());
                row.put("sectionBreadcrumb",   c.getSectionBreadcrumbSnapshot());
            }
            return row;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PutMapping("/v1/audit/policy-instances/{id}/review")
    @Operation(summary = "Set auditor review result on a policy instance")
    public ResponseEntity<ApiResponse<Void>> reviewPolicy(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {

        var ctx    = utilityService.getLoggedInDataContext();
        var policy = policyRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AuditPolicyInstance", id));

        boolean hasAssignPermission = ctx.getRoles().stream()
                .flatMap(r -> r.getPermissions().stream())
                .anyMatch(p -> "audit:control:assign-auditor".equals(p.getCode()));
        if (!hasAssignPermission) {
            List<Long> controlInstanceIds = policyCtrlMappingRepo.findByPolicyInstanceId(id)
                    .stream().map(m -> m.getControlInstanceId()).toList();
            // The old `|| c.getAssignedAuditorId() == null` made every unassigned
            // control testable by anyone. Delegating to the guard keeps section
            // owners and the lead auditor able to act without opening it up.
            boolean isAssigned = !controlInstanceIds.isEmpty() &&
                    controlRepo.findAllById(controlInstanceIds).stream()
                            .anyMatch(c -> controlAccessGuard.canAct(c, ctx.getId(), false));
            if (!isAssigned) {
                throw new com.kashi.grc.common.exception.BusinessException(
                        "POLICY_NOT_ASSIGNED",
                        "You are not assigned to any control mapped to this policy");
            }
        }

        policy.setReviewResult(
                AuditPolicyInstance.ReviewResult.valueOf(body.get("reviewResult")));
        policy.setReviewedById(ctx.getId());
        policy.setReviewedAt(LocalDateTime.now());
        if (body.containsKey("auditorNotes")) policy.setAuditorNotes(body.get("auditorNotes"));
        policyRepo.save(policy);

        // One finding per policy (not per control) when INADEQUATE.
        // The policy owner is responsible for remediation — not the individual control auditees.
        if (policy.getReviewResult() == AuditPolicyInstance.ReviewResult.INADEQUATE) {
            String autoTitle = "Policy gap: " + policy.getTitleSnapshot();
            boolean alreadyExists = findingRepo
                    .findByEngagementIdAndTenantId(policy.getEngagementId(), ctx.getTenantId())
                    .stream()
                    .anyMatch(f -> autoTitle.equals(f.getTitle())
                            && f.getStatus() != AuditFinding.Status.CLOSED
                            && f.getStatus() != AuditFinding.Status.ACCEPTED_RISK
                            && f.getStatus() != AuditFinding.Status.WITHDRAWN);

            if (!alreadyExists) {
                AuditFinding autoFinding = AuditFinding.builder()
                        .tenantId(ctx.getTenantId())
                        .findingRef(generateFindingRef(ctx.getTenantId()))
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
                        .ownerId(policy.getOwnerIdSnapshot())  // policy owner remediates policy gaps
                        .raisedBy(ctx.getId())
                        .raisedAt(LocalDateTime.now())
                        .build();
                findingRepo.save(autoFinding);
                log.info("[POLICY-INSTANCE] Auto-finding raised | policyInstanceId={} findingRef={}",
                        id, autoFinding.getFindingRef());

                autoEscalateToIssue(autoFinding, ctx.getId(), ctx.getTenantId());
            }

            snapshotService.syncEngagementScore(policy.getEngagementId(), ctx.getTenantId());
        }

        log.info("[POLICY-INSTANCE] Reviewed | id={} | result={}", id, policy.getReviewResult());
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PutMapping("/v1/audit/policy-instances/{id}/controls/{controlId}/contribution")
    @Operation(summary = "Set per-control review contribution for a policy instance")
    public ResponseEntity<ApiResponse<Void>> setContribution(
            @PathVariable Long id,
            @PathVariable Long controlId,
            @RequestBody Map<String, String> body) {

        AuditPolicyInstanceControlMapping mapping =
                policyCtrlMappingRepo.findByPolicyInstanceIdAndControlInstanceId(id, controlId)
                        .orElseThrow(() -> new ResourceNotFoundException("PolicyControlMapping", id));

        mapping.setReviewContribution(
                AuditPolicyInstanceControlMapping.ReviewContribution.valueOf(body.get("contribution")));
        policyCtrlMappingRepo.save(mapping);

        return ResponseEntity.ok(ApiResponse.success());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // LIST ENDPOINTS — tenant-scoped, optionally filtered by engagementId
    // ══════════════════════════════════════════════════════════════════════════

    @GetMapping("/v1/audit/control-instances")
    @Operation(summary = "List all control instances for this tenant, optionally filtered by engagement")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listControlInstances(
            @RequestParam(required = false) Long engagementId) {
        var ctx = utilityService.getLoggedInDataContext();
        List<AuditControlInstance> items = engagementId != null
                ? controlRepo.findByEngagementId(engagementId)
                : controlRepo.findByTenantIdOrderByControlCodeSnapshotAsc(ctx.getTenantId());
        return ResponseEntity.ok(ApiResponse.success(
                items.stream().map(this::buildControlMap).collect(Collectors.toList())));
    }

    @GetMapping("/v1/audit/test-instances")
    @Operation(summary = "List all test instances for this tenant, optionally filtered by engagement")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listTestInstances(
            @RequestParam(required = false) Long engagementId) {
        var ctx = utilityService.getLoggedInDataContext();
        List<AuditTestInstance> items = engagementId != null
                ? testRepo.findByEngagementIdOrderByTestNameSnapshotAsc(engagementId)
                : testRepo.findByTenantIdOrderByTestNameSnapshotAsc(ctx.getTenantId());
        return ResponseEntity.ok(ApiResponse.success(
                items.stream().map(t -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", t.getId()); m.put("engagementId", t.getEngagementId());
                    m.put("testRefSnapshot", t.getTestRefSnapshot());
                    m.put("testNameSnapshot", t.getTestNameSnapshot());
                    m.put("controlTagSnapshot", t.getControlTagSnapshot());
                    m.put("automationTypeSnapshot", t.getAutomationTypeSnapshot());
                    m.put("frequencySnapshot", t.getFrequencySnapshot());
                    m.put("testResult", t.getTestResult());
                    m.put("affectedControlCount", t.getAffectedControlCount());
                    return m;
                }).collect(Collectors.toList())));
    }

    @GetMapping("/v1/audit/policy-instances")
    @Operation(summary = "List all policy instances for this tenant, optionally filtered by engagement")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listPolicyInstances(
            @RequestParam(required = false) Long engagementId) {
        var ctx = utilityService.getLoggedInDataContext();
        List<AuditPolicyInstance> items = engagementId != null
                ? policyRepo.findByEngagementIdOrderByTitleSnapshotAsc(engagementId)
                : policyRepo.findByTenantIdOrderByTitleSnapshotAsc(ctx.getTenantId());
        return ResponseEntity.ok(ApiResponse.success(
                items.stream().map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", p.getId()); m.put("engagementId", p.getEngagementId());
                    m.put("policyRefSnapshot", p.getPolicyRefSnapshot());
                    m.put("titleSnapshot", p.getTitleSnapshot());
                    m.put("versionSnapshot", p.getVersionSnapshot());
                    m.put("contentTypeSnapshot", p.getContentTypeSnapshot());
                    m.put("policyStatusSnapshot", p.getPolicyStatusSnapshot());
                    m.put("reviewResult", p.getReviewResult());
                    return m;
                }).collect(Collectors.toList())));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> buildControlMap(AuditControlInstance c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",                       c.getId());
        m.put("engagementId",             c.getEngagementId());
        // Breadcrumb support
        if (c.getEngagementId() != null) {
            engagementRepo.findById(c.getEngagementId()).ifPresent(eng -> {
                m.put("engagementName", eng.getName());
                m.put("engagementRef",  eng.getEngagementRef());
            });
        }
        m.put("controlCodeSnapshot",      c.getControlCodeSnapshot());
        m.put("controlNameSnapshot",      c.getControlNameSnapshot());
        m.put("descriptionSnapshot",      c.getDescriptionSnapshot());
        m.put("testProcedureSnapshot",    c.getTestProcedure());
        // No evidence-guidance column on AuditControlInstance — getControlInstance
        // overwrites this by rolling up the mapped tests' evidenceGuidanceSnapshot.
        m.put("evidenceGuidanceSnapshot", null);
        m.put("controlTagSnapshot",       c.getControlTagSnapshot());
        m.put("testTypeSnapshot",         c.getTestTypeSnapshot());
        m.put("frameworkRefSnapshot",     c.getFrameworkRefSnapshot());
        m.put("testResult",               c.getTestResult());
        m.put("testNotes",                c.getTestNotes());
        m.put("assignedAuditorId",        c.getAssignedAuditorId());
        m.put("auditeeAssignedUserId",    c.getAuditeeAssignedUserId());
        m.put("auditeeEvidenceSubmitted", c.isAuditeeEvidenceSubmitted());
        m.put("evidenceSubmittedAt",      c.getAuditeeEvidenceSubmittedAt());
        m.put("testedAt",                 c.getTestedAt());
        m.put("testedBy",                 c.getTestedBy());
        m.put("findingLinked",            c.isFindingLinked());
        m.put("findingIssueId",           c.getFindingIssueId());
        m.put("sectionBreadcrumbSnapshot",c.getSectionBreadcrumbSnapshot());
        m.put("sectionInstanceId",        c.getSectionInstanceId());
        return m;
    }

    /**
     * Auto-escalate a finding to Issue Management using workflow 15.
     * Step 1 (ENTITY_CREATOR + auto_complete_actor_on_submit=1) completes immediately on creation.
     * Step 2 (ENTITY_OWNER) lands in the owner's task inbox.
     * Wrapped in try-catch so a workflow config issue never breaks the parent operation.
     */
    private void autoEscalateToIssue(AuditFinding finding, Long createdBy, Long tenantId) {
        // IssueService now rejects an owner or creator who is not a user of this
        // tenant. Checking here first keeps the finding intact and the log
        // readable instead of relying on the catch below.
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