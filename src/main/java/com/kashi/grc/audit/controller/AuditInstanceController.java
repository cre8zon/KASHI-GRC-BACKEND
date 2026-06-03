package com.kashi.grc.audit.controller;

import com.kashi.grc.audit.domain.*;
import com.kashi.grc.audit.dto.request.AuditControlTestRequest;
import com.kashi.grc.audit.repository.*;
import com.kashi.grc.audit.service.AuditTestPolicySnapshotService;
import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.util.UtilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
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
    private final AuditTestInstanceRepository               testRepo;
    private final AuditPolicyInstanceRepository             policyRepo;
    private final AuditControlInstanceTestMappingRepository ctrlTestMappingRepo;
    private final AuditPolicyInstanceControlMappingRepository policyCtrlMappingRepo;
    private final AuditTestPolicySnapshotService             snapshotService;
    private final UtilityService                            utilityService;

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
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/v1/audit/control-instances/{id}/tests")
    @Operation(summary = "List all test instances mapped to this control")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getControlTests(
            @PathVariable Long id) {

        List<AuditControlInstanceTestMapping> mappings =
                ctrlTestMappingRepo.findByControlInstanceIdOrderByOrderNoAsc(id);

        List<Map<String, Object>> result = mappings.stream().map(m -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("mappingId",          m.getId());
            row.put("testInstanceId",     m.getTestInstanceId());
            row.put("isRequired",         m.isRequired());
            row.put("orderNo",            m.getOrderNo());
            row.put("mappingNoteSnapshot",m.getMappingNoteSnapshot());
            // Enrich with test instance data
            testRepo.findById(m.getTestInstanceId()).ifPresent(t -> {
                row.put("testNameSnapshot",        t.getTestNameSnapshot());
                row.put("testRefSnapshot",         t.getTestRefSnapshot());
                row.put("testResult",              t.getTestResult());
                row.put("automationTypeSnapshot",  t.getAutomationTypeSnapshot());
                row.put("controlTagSnapshot",      t.getControlTagSnapshot());
                row.put("runAt",                   t.getRunAt());
                row.put("runBySystem",             t.isRunBySystem());
                row.put("automationResult",        t.getAutomationRawResult());
            });
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

        List<Map<String, Object>> result = mappings.stream().map(m -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("mappingId",            m.getId());
            row.put("policyInstanceId",     m.getPolicyInstanceId());
            row.put("reviewContribution",   m.getReviewContribution());
            row.put("mappingTypeSnapshot",  m.getMappingTypeSnapshot());
            row.put("mappingNoteSnapshot",  m.getMappingNoteSnapshot());
            policyRepo.findById(m.getPolicyInstanceId()).ifPresent(p -> {
                row.put("titleSnapshot",        p.getTitleSnapshot());
                row.put("policyRefSnapshot",    p.getPolicyRefSnapshot());
                row.put("versionSnapshot",      p.getVersionSnapshot());
                row.put("reviewResult",         p.getReviewResult());
                row.put("contentTypeSnapshot",  p.getContentTypeSnapshot());
                row.put("policyStatusSnapshot", p.getPolicyStatusSnapshot());
            });
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

        ctrl.setTestResult(req.getTestResult());
        if (req.getTestNotes() != null) ctrl.setTestNotes(req.getTestNotes());
        ctrl.setTestedAt(LocalDateTime.now());
        ctrl.setTestedBy(ctx.getId());
        controlRepo.save(ctrl);
        // Cascade to section completion tracking
        log.info("[CTRL-INST] Test result set | id={} result={}", id, req.getTestResult());
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
        var test = testRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AuditTestInstance", id));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id",                      test.getId());
        result.put("engagementId",            test.getEngagementId());
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

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/v1/audit/test-instances/{id}/controls")
    @Operation(summary = "List all control instances that this test is mapped to")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getTestControls(
            @PathVariable Long id) {

        List<AuditControlInstanceTestMapping> mappings =
                ctrlTestMappingRepo.findByTestInstanceId(id);

        List<Map<String, Object>> result = mappings.stream().map(m -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("mappingId",           m.getId());
            row.put("controlInstanceId",   m.getControlInstanceId());
            row.put("isRequired",          m.isRequired());
            row.put("orderNo",             m.getOrderNo());
            row.put("mappingNoteSnapshot", m.getMappingNoteSnapshot());
            controlRepo.findById(m.getControlInstanceId()).ifPresent(c -> {
                row.put("controlCodeSnapshot",c.getControlCodeSnapshot());
                row.put("controlNameSnapshot",c.getControlNameSnapshot());
                row.put("controlTagSnapshot", c.getControlTagSnapshot());
                row.put("testResult",         c.getTestResult());
                row.put("sectionBreadcrumb",  c.getSectionBreadcrumbSnapshot());
                row.put("engagementId",       c.getEngagementId());
            });
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

        AuditTestInstance.TestResult newResult =
                AuditTestInstance.TestResult.valueOf(body.get("testResult"));

        test.setTestResult(newResult);
        test.setRunAt(LocalDateTime.now());
        test.setRunByUserId(ctx.getId());
        test.setRunBySystem(false);
        if (body.containsKey("testerNotes"))   test.setTesterNotes(body.get("testerNotes"));
        if (body.containsKey("failureDetail")) test.setFailureDetail(body.get("failureDetail"));
        if (body.containsKey("exceptionReason")) test.setExceptionReason(body.get("exceptionReason"));
        testRepo.save(test);

        // Cascade — re-derive result for all control instances linked to this test.
        // cascadeDeriveControlResults() handles the full derivation logic:
        //   any required test FAIL → INEFFECTIVE
        //   all required tests PASS → EFFECTIVE
        //   no tests run yet → unchanged
        snapshotService.cascadeDeriveControlResults(id, ctx.getTenantId());

        // Update affected control count on the test instance
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

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/v1/audit/policy-instances/{id}/controls")
    @Operation(summary = "List all control instances covered by this policy")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getPolicyControls(
            @PathVariable Long id) {

        List<AuditPolicyInstanceControlMapping> mappings =
                policyCtrlMappingRepo.findByPolicyInstanceId(id);

        List<Map<String, Object>> result = mappings.stream().map(m -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("mappingId",           m.getId());
            row.put("controlInstanceId",   m.getControlInstanceId());
            row.put("reviewContribution",  m.getReviewContribution());
            row.put("mappingTypeSnapshot", m.getMappingTypeSnapshot());
            row.put("mappingNoteSnapshot", m.getMappingNoteSnapshot());
            controlRepo.findById(m.getControlInstanceId()).ifPresent(c -> {
                row.put("controlCodeSnapshot", c.getControlCodeSnapshot());
                row.put("controlNameSnapshot", c.getControlNameSnapshot());
                row.put("controlTagSnapshot",  c.getControlTagSnapshot());
                row.put("testResult",          c.getTestResult());
                row.put("sectionBreadcrumb",   c.getSectionBreadcrumbSnapshot());
            });
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

        policy.setReviewResult(
                AuditPolicyInstance.ReviewResult.valueOf(body.get("reviewResult")));
        policy.setReviewedById(ctx.getId());
        policy.setReviewedAt(LocalDateTime.now());
        if (body.containsKey("auditorNotes")) policy.setAuditorNotes(body.get("auditorNotes"));
        policyRepo.save(policy);

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
        m.put("id",                      c.getId());
        m.put("engagementId",            c.getEngagementId());
        m.put("controlCodeSnapshot",     c.getControlCodeSnapshot());
        m.put("controlNameSnapshot",     c.getControlNameSnapshot());
        m.put("descriptionSnapshot",     c.getDescriptionSnapshot());
        m.put("testProcedureSnapshot",   c.getTestProcedure());
        m.put("evidenceGuidanceSnapshot",null);   // not stored on control instance
        m.put("controlTagSnapshot",      c.getControlTagSnapshot());
        m.put("testTypeSnapshot",        c.getTestTypeSnapshot());
        m.put("frameworkRefSnapshot",    c.getFrameworkRefSnapshot());
        m.put("testResult",              c.getTestResult());
        m.put("testNotes",               c.getTestNotes());
        m.put("testNotes",               c.getTestNotes());
        m.put("assignedAuditorId",       c.getAssignedAuditorId());
        m.put("auditeeAssignedUserId",   c.getAuditeeAssignedUserId());
        m.put("auditeeEvidenceSubmitted",   c.isAuditeeEvidenceSubmitted());
        m.put("evidenceSubmittedAt",         c.getAuditeeEvidenceSubmittedAt());
        m.put("testedAt",                c.getTestedAt());
        m.put("testedBy",                c.getTestedBy());
        m.put("findingLinked",           c.isFindingLinked());
        m.put("findingIssueId",          c.getFindingIssueId());
        m.put("sectionBreadcrumbSnapshot",c.getSectionBreadcrumbSnapshot());
        m.put("sectionInstanceId",       c.getSectionInstanceId());
        return m;
    }
}