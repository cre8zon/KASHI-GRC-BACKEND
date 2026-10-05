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
    private final com.kashi.grc.audit.service.AuditScopeService     auditScopeService;
    private final AuditTestInstanceRepository               testRepo;
    private final AuditPolicyInstanceRepository             policyRepo;
    private final AuditControlInstanceTestMappingRepository ctrlTestMappingRepo;
    private final AuditPolicyInstanceControlMappingRepository policyCtrlMappingRepo;
    private final AuditTestPolicySnapshotService             snapshotService;
    private final UtilityService                            utilityService;
    // Test results and policy reviews: the one shared implementation.
    private final com.kashi.grc.audit.service.AuditFieldworkService fieldworkService;
    private final com.kashi.grc.audit.repository.AuditEngagementRepository engagementRepo;
    private final com.kashi.grc.evidence.repository.EvidenceLinkRepository evidenceLinkRepo;
    private final com.kashi.grc.audit.service.AuditObligationService obligationService;
    // The one implementation of "record a control-level result" — counters,
    // section gate, evidence gate and obligation closing live there.
    private final com.kashi.grc.audit.service.AuditEngagementService engagementService;

    // ══════════════════════════════════════════════════════════════════════════
    // CONTROL INSTANCES — /v1/audit/control-instances/{id}
    // ══════════════════════════════════════════════════════════════════════════

    // One read-only transaction for the whole request. open-in-view is off, so
    // without it every repository call below opened and committed its own
    // transaction — ~4 database round trips per query (~150 ms each to Aiven).
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    @GetMapping("/v1/audit/control-instances/{id}")
    @Operation(summary = "Get control instance by ID — flat response for UMP overview tab")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getControlInstance(@PathVariable Long id) {
        var ctx  = utilityService.getLoggedInDataContext();
        var ctrl = controlRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AuditControlInstance", id));
        // Loaded by id alone, so any authenticated user in any tenant could read
        // any control — and a guest any engagement's. Same rule as the engagement
        // detail page: tenant, then the guest's staffed engagements.
        controlAccessGuard.requireReadable(ctrl.getTenantId(), ctrl.getEngagementId());

        Map<String, Object> result = buildControlMap(ctrl,
                controlAccessGuard.evaluator(ctrl.getEngagementId(), ctx.getId()));

        // PRECEDENCE: the control's own guidance wins; the mapped tests are only a
        // fallback. Evidence guidance used to live solely on AuditTestInstance and
        // was rolled up here, which produced nothing for a control with no tests
        // mapped yet — the common case in a library still being built. Controls now
        // carry their own, so the rollup runs only when that is blank. Existing
        // engagements have a null snapshot and therefore behave exactly as before.
        //
        // The extra queries are skipped entirely when the snapshot is present, so
        // the authored path is also the cheap one.
        Object ownGuidance = result.get("evidenceGuidanceSnapshot");
        boolean hasOwnGuidance = ownGuidance instanceof String str && !str.isBlank();

        List<Long> guidanceTestIds = hasOwnGuidance ? List.of() : ctrlTestMappingRepo
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

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    @GetMapping("/v1/audit/control-instances/{id}/tests")
    @Operation(summary = "List all test instances mapped to this control")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getControlTests(
            @PathVariable Long id) {

        var parentCtrl = controlRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AuditControlInstance", id));
        controlAccessGuard.requireReadable(parentCtrl.getTenantId(), parentCtrl.getEngagementId());

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

        // Evidence badge data. entityIdsWithAnyLink is the existing batch lookup
        // (any status, any collection type), so this stays at two queries no
        // matter how many tests are mapped - no N+1 reintroduced.
        java.util.Set<Long> testsWithEvidence =
                evidenceLinkRepo.entityIdsWithAnyLink("AUDIT_TEST_INSTANCE", testInstanceIds);
        boolean controlHasEvidence = !evidenceLinkRepo
                .entityIdsWithAnyLink("AUDIT_CONTROL_INSTANCE", List.of(id)).isEmpty();

        // Recording a result needs ASSIGNMENT, not just the permission - the same
        // rule setTestResult enforces. Telling the client here is what lets the
        // fieldwork tab render read-only instead of offering a Save that 400s.
        //
        // Per TEST now, through the same guard setTestResult calls. It used to be
        // one control-level value plus a bypass for anyone holding
        // audit:control:assign-auditor — a permission check standing in for an
        // assignment check, which is exactly the hole being closed. Cover for an
        // absent colleague is the override permission, inside the guard.
        var evCtx = utilityService.getLoggedInDataContext();
        var ev = controlAccessGuard.evaluator(parentCtrl.getEngagementId(), evCtx.getId())
                .prefetchControls(List.of(parentCtrl))
                .prefetchTests(testInstanceIds);
        boolean ctrlObligation = ev.hasControlObligation(id, false);

        List<Map<String, Object>> result = mappings.stream().map(m -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("mappingId",          m.getId());
            row.put("testInstanceId",     m.getTestInstanceId());
            row.put("isRequired",         m.isRequired());
            row.put("orderNo",            m.getOrderNo());
            row.put("mappingNoteSnapshot",m.getMappingNoteSnapshot());
            AuditTestInstance t = testsById.get(m.getTestInstanceId());
            row.put("canRecordResult",    t != null && ev.canActOnTest(t));
            // A live delegation on this test, or on the control it sits under —
            // lets the UI offer the editor to a delegate who holds no workflow task.
            row.put("hasMyObligation",    ctrlObligation || ev.hasTestObligation(m.getTestInstanceId()));
            if (t != null) {
                row.put("testNameSnapshot",        t.getTestNameSnapshot());
                row.put("testRefSnapshot",         t.getTestRefSnapshot());
                row.put("testResult",              t.getTestResult());
                // Evidence in ANY form backing this test: work papers on the test
                // itself plus whatever is attached to the control it sits under.
                row.put("hasEvidence",
                        testsWithEvidence.contains(m.getTestInstanceId()) || controlHasEvidence);
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

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    @GetMapping("/v1/audit/control-instances/{id}/policies")
    @Operation(summary = "List all policy instances covering this control")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getControlPolicies(
            @PathVariable Long id) {

        var parentCtrl = controlRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AuditControlInstance", id));
        controlAccessGuard.requireReadable(parentCtrl.getTenantId(), parentCtrl.getEngagementId());

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

        // Same guard reviewPolicy and setContribution enforce, per row, so the
        // fieldwork and policies tabs render read-only where Save would 403.
        var polCtx = utilityService.getLoggedInDataContext();
        var polEv  = controlAccessGuard.evaluator(parentCtrl.getEngagementId(), polCtx.getId())
                .prefetchControls(List.of(parentCtrl))
                .prefetchPolicies(policyInstanceIds);
        boolean canActOnParent = polEv.canAct(parentCtrl, false);

        List<Map<String, Object>> result = mappings.stream().map(m -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("mappingId",            m.getId());
            row.put("policyInstanceId",     m.getPolicyInstanceId());
            row.put("reviewContribution",   m.getReviewContribution());
            row.put("mappingTypeSnapshot",  m.getMappingTypeSnapshot());
            row.put("mappingNoteSnapshot",  m.getMappingNoteSnapshot());
            AuditPolicyInstance p = policiesById.get(m.getPolicyInstanceId());
            boolean canReviewPolicy = p != null && polEv.canActOnPolicy(p);
            row.put("canReviewPolicy",      canReviewPolicy);
            row.put("canSetContribution",   canReviewPolicy || canActOnParent);
            row.put("hasMyObligation",      polEv.hasPolicyObligation(m.getPolicyInstanceId()));
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

        // WAS a second implementation here: it saved the result but never updated
        // the engagement counters or fired TEST_RECORDED, so the section gate
        // could stall with every control tested, and its evidence gate differed
        // from the engagement path's. Both endpoints now share one method.
        ctrl = engagementService.recordControlResult(ctrl, req, ctx.getId(), ctx.getTenantId());
        log.info("[CTRL-INST] Test result set | id={} result={} by={}", id, req.getTestResult(), ctx.getId());
        return ResponseEntity.ok(ApiResponse.success(buildControlMap(ctrl,
                controlAccessGuard.evaluator(ctrl.getEngagementId(), ctx.getId()))));
    }

    @PutMapping("/v1/audit/control-instances/{id}/assign-auditee")
    @Operation(summary = "Assign auditee to a control instance")
    public ResponseEntity<ApiResponse<Void>> assignControlAuditee(
            @PathVariable Long id,
            @RequestBody Map<String, Long> body) {

        var ctx  = utilityService.getLoggedInDataContext();
        var ctrl = controlRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AuditControlInstance", id));

        // Same tenant/scope rule as every instance endpoint, then the SAME
        // assignment rule as PUT /engagements/{id}/controls/{cid}/assign-auditee
        // (section-level assigner, or owner of the control's section on the
        // auditee side). It used to check the AUDITOR result rule here, so an
        // auditee section owner was refused and an auditor was allowed.
        controlAccessGuard.requireReadable(ctrl.getTenantId(), ctrl.getEngagementId());
        controlAccessGuard.requireCanAssign(ctrl, ctx.getId(), true);

        // One implementation: notifications, delegation clean-up and the null
        // (= unassign) case are handled exactly as on the engagement path.
        engagementService.assignAuditeeToControl(ctrl.getEngagementId(), id,
                body.get("auditeeUserId"), ctx.getTenantId());
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PostMapping("/v1/audit/control-instances/{id}/submit-evidence")
    @Operation(summary = "Mark evidence as submitted for this control")
    public ResponseEntity<ApiResponse<Void>> submitControlEvidence(@PathVariable Long id) {
        var ctx  = utilityService.getLoggedInDataContext();
        var ctrl = controlRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AuditControlInstance", id));

        // Tenant / guest scope, then ONE implementation. This endpoint used to set
        // the flag itself — no "is there any evidence" check, no section
        // auto-submit, no EVIDENCE_UPLOADED checklist event — so evidence
        // submitted from the control drawer or detail page never completed its
        // section or ticked the workflow item, while the same click on the
        // engagement Controls tab did. submitControlEvidence runs the assignment
        // guard (ControlAccessGuard) and closes obligations itself.
        controlAccessGuard.requireReadable(ctrl.getTenantId(), ctrl.getEngagementId());
        engagementService.submitControlEvidence(ctrl.getEngagementId(), id, ctx.getId(), ctx.getTenantId());
        return ResponseEntity.ok(ApiResponse.success());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DELEGATION — per-instance action items (AuditObligationService)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Delegate one control to a colleague, on one side.
     *
     *   body: { assignedTo, side: "AUDITEE"|"AUDITOR", note, dueAt, priority }
     *
     * Only someone who may act on that side of the control may delegate it
     * (ControlAccessGuard), and only to a user who can do that side's work. The
     * live item then lets the delegate act on this one control — nothing else —
     * until they finish it or the delegator revokes it.
     */
    @PostMapping("/v1/audit/control-instances/{id}/delegate")
    @Operation(summary = "Delegate a control instance to a colleague (raises a per-control action item)")
    public ResponseEntity<ApiResponse<com.kashi.grc.actionitem.dto.ActionItemResponse>> delegateControl(
            @PathVariable Long id,
            @RequestBody com.kashi.grc.audit.service.AuditObligationService.DelegateRequest req) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
                .body(ApiResponse.success(obligationService.delegateControl(id, req)));
    }

    /** Delegate one test instance (auditor side). Same body; side is ignored or AUDITOR. */
    @PostMapping("/v1/audit/test-instances/{id}/delegate")
    @Operation(summary = "Delegate a test instance to a colleague auditor")
    public ResponseEntity<ApiResponse<com.kashi.grc.actionitem.dto.ActionItemResponse>> delegateTest(
            @PathVariable Long id,
            @RequestBody com.kashi.grc.audit.service.AuditObligationService.DelegateRequest req) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
                .body(ApiResponse.success(obligationService.delegateTest(id, req)));
    }

    /** Delegate one policy-instance review (auditor side). */
    @PostMapping("/v1/audit/policy-instances/{id}/delegate")
    @Operation(summary = "Delegate a policy instance review to a colleague auditor")
    public ResponseEntity<ApiResponse<com.kashi.grc.actionitem.dto.ActionItemResponse>> delegatePolicy(
            @PathVariable Long id,
            @RequestBody com.kashi.grc.audit.service.AuditObligationService.DelegateRequest req) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
                .body(ApiResponse.success(obligationService.delegatePolicy(id, req)));
    }

    /**
     * Move a live delegation (action item on a control / test / policy) to
     * someone else in one step — the new one is raised through the normal
     * delegate checks first, then the old one is closed.
     *
     *   body: { assignedTo, note?, dueAt?, priority? }
     */
    @PutMapping("/v1/audit/delegations/{actionItemId}/reassign")
    @Operation(summary = "Reassign an audit delegation to another user (revoke + delegate in one step)")
    public ResponseEntity<ApiResponse<com.kashi.grc.actionitem.dto.ActionItemResponse>> reassignDelegation(
            @PathVariable Long actionItemId,
            @RequestBody com.kashi.grc.audit.service.AuditObligationService.DelegateRequest req) {
        return ResponseEntity.ok(ApiResponse.success(obligationService.reassignDelegation(actionItemId, req)));
    }

    /**
     * Who the delegate picker may offer for one kind of work — users holding
     * that work's permission, whatever their role or side. The same list the
     * delegate endpoints validate against, so the picker cannot offer someone
     * the server will refuse. Excludes the caller.
     *
     *   ?work=EVIDENCE | TESTING | POLICY_REVIEW   (?side=AUDITEE|AUDITOR still accepted)
     */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    @GetMapping("/v1/audit/delegate-candidates")
    @Operation(summary = "Users who can take delegated audit work (EVIDENCE | TESTING | POLICY_REVIEW)")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> delegateCandidates(
            @RequestParam(required = false) String work,
            @RequestParam(required = false) String side) {
        if (com.kashi.grc.common.config.multitenancy.AccessScope.isVendor()) {
            throw new com.kashi.grc.common.exception.BusinessException("AUDIT_INSTANCE_NOT_ACCESSIBLE",
                    "Vendor accounts do not have access to audit engagements",
                    org.springframework.http.HttpStatus.FORBIDDEN);
        }
        var w = com.kashi.grc.audit.service.AuditObligationService.Work.parse(work != null ? work : side);
        if (w == null) {
            throw new com.kashi.grc.common.exception.BusinessException("INVALID_WORK",
                    "work must be EVIDENCE, TESTING or POLICY_REVIEW");
        }
        return ResponseEntity.ok(ApiResponse.success(obligationService.candidates(w)));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST INSTANCES — /v1/audit/test-instances/{id}
    // ══════════════════════════════════════════════════════════════════════════

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    @GetMapping("/v1/audit/test-instances/{id}")
    @Operation(summary = "Get test instance by ID — flat response for UMP overview tab")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getTestInstance(@PathVariable Long id) {
        var ctx  = utilityService.getLoggedInDataContext();
        var test = testRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AuditTestInstance", id));
        controlAccessGuard.requireReadable(test.getTenantId(), test.getEngagementId());

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

        // WAS: `|| c.getAssignedAuditorId() == null` — any unassigned mapped
        // control made the test "assigned" to everyone. Now the same guard
        // setTestResult enforces, so the header buttons mirror the server.
        var testEv = controlAccessGuard.evaluator(test.getEngagementId(), ctx.getId());
        boolean canRecordTest = testEv.canActOnTest(test);
        result.put("isAssignedToCurrentUser", canRecordTest);
        result.put("canRecordResult",         canRecordTest);
        result.put("hasMyObligation",         testEv.hasTestObligation(id));
        // Keyed by the permission a ui_action carries, so a requires_assignment
        // button is gated on THIS side's answer, not on either side's. Read by
        // UniversalModulePage's shared action filter.
        result.put("assignmentByPermission", Map.of(
                "audit:control:record-test-result", canRecordTest));

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    @GetMapping("/v1/audit/test-instances/{id}/controls")
    @Operation(summary = "List all control instances that this test is mapped to")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getTestControls(
            @PathVariable Long id) {

        var parentTest = testRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AuditTestInstance", id));
        controlAccessGuard.requireReadable(parentTest.getTenantId(), parentTest.getEngagementId());

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

        // WAS: anyone holding audit:control:assign-auditor skipped the check
        // entirely, in any tenant — a permission standing in for an assignment.
        // Now one rule, in the guard: a mapped control the caller may act on
        // (assignee, section owner, delegate), a delegation on this test, or the
        // auditor-override permission for covering an absent colleague. The guard
        // also refuses other tenants and a guest's unstaffed engagements.
        controlAccessGuard.requireCanRecordTestResult(test, ctx.getId());

        // One implementation for every screen that records a test result —
        // control Fieldwork, test detail, admin Tests tab (AuditFieldworkService).
        return ResponseEntity.ok(ApiResponse.success(
                fieldworkService.recordTestResult(test, body, ctx.getId(), ctx.getTenantId())));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // POLICY INSTANCES — /v1/audit/policy-instances/{id}
    // ══════════════════════════════════════════════════════════════════════════

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    @GetMapping("/v1/audit/policy-instances/{id}")
    @Operation(summary = "Get policy instance by ID — flat response for UMP overview tab")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPolicyInstance(@PathVariable Long id) {
        var policy = policyRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AuditPolicyInstance", id));
        controlAccessGuard.requireReadable(policy.getTenantId(), policy.getEngagementId());

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

        // WAS: `|| c.getAssignedAuditorId() == null` — see getTestInstance. Now
        // the same guard reviewPolicy enforces.
        var ctx2 = utilityService.getLoggedInDataContext();
        var polEv = controlAccessGuard.evaluator(policy.getEngagementId(), ctx2.getId());
        boolean canReviewPolicy = polEv.canActOnPolicy(policy);
        result.put("isAssignedToCurrentUser", canReviewPolicy);
        result.put("canReviewPolicy",         canReviewPolicy);
        result.put("hasMyObligation",         polEv.hasPolicyObligation(id));
        result.put("assignmentByPermission", Map.of(
                "audit:policy:review", canReviewPolicy));

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    @GetMapping("/v1/audit/policy-instances/{id}/controls")
    @Operation(summary = "List all control instances covered by this policy")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getPolicyControls(
            @PathVariable Long id) {

        var parentPolicy = policyRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AuditPolicyInstance", id));
        controlAccessGuard.requireReadable(parentPolicy.getTenantId(), parentPolicy.getEngagementId());

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

        // Mirrors setContribution's guard per row: the policy's reviewer, or
        // whoever may act on that control auditor-side.
        var pcCtx = utilityService.getLoggedInDataContext();
        var pcEv  = controlAccessGuard.evaluator(parentPolicy.getEngagementId(), pcCtx.getId())
                .prefetchControls(coveredControlsById.values())
                .prefetchPolicies(List.of(id));
        boolean canReviewParent = pcEv.canActOnPolicy(parentPolicy);

        List<Map<String, Object>> result = mappings.stream().map(m -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("mappingId",           m.getId());
            row.put("controlInstanceId",   m.getControlInstanceId());
            row.put("reviewContribution",  m.getReviewContribution());
            row.put("mappingTypeSnapshot", m.getMappingTypeSnapshot());
            row.put("mappingNoteSnapshot", m.getMappingNoteSnapshot());
            AuditControlInstance c = coveredControlsById.get(m.getControlInstanceId());
            row.put("canSetContribution",  canReviewParent || (c != null && pcEv.canAct(c, false)));
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

        // WAS: anyone holding audit:control:assign-auditor skipped the check, in
        // any tenant. Now the guard alone — see setTestResult.
        controlAccessGuard.requireCanReviewPolicy(policy, ctx.getId());

        // One implementation for every screen that records a policy review —
        // control Fieldwork, Policy content tab, admin Policies tab — so the
        // INADEQUATE policy-gap finding is raised whichever one is used.
        fieldworkService.reviewPolicy(policy, body, ctx.getId(), ctx.getTenantId());
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

        // Two path ids and nothing else — the mapping was reachable from any
        // tenant. Checked against the control instance rather than the mapping
        // because that is the row carrying tenant and engagement.
        var ctxUser = utilityService.getLoggedInDataContext();
        var ctrlInst = controlRepo.findById(controlId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditControlInstance", controlId));
        if (!java.util.Objects.equals(ctrlInst.getTenantId(), ctxUser.getTenantId())) {
            throw new com.kashi.grc.common.exception.BusinessException("CONTROL_ACCESS_DENIED",
                    "You can only modify controls belonging to your organisation",
                    org.springframework.http.HttpStatus.FORBIDDEN);
        }
        auditScopeService.requireEngagementVisible(ctrlInst.getEngagementId());

        // Tenant and visibility were the whole check, so any auditor in the
        // engagement could rewrite any control's contribution. Same answer as
        // the row's canSetContribution flag: the policy's reviewer, or whoever
        // may act on this control auditor-side.
        var contribPolicy = policyRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AuditPolicyInstance", id));
        if (!java.util.Objects.equals(contribPolicy.getEngagementId(), ctrlInst.getEngagementId())) {
            throw new ResourceNotFoundException("PolicyControlMapping", id);
        }
        if (!controlAccessGuard.canActOnPolicy(contribPolicy, ctxUser.getId())
                && !controlAccessGuard.canAct(ctrlInst, ctxUser.getId(), false)) {
            throw new com.kashi.grc.common.exception.BusinessException("POLICY_NOT_ASSIGNED",
                    "Only the policy's reviewer or the control's auditor can set its contribution.",
                    org.springframework.http.HttpStatus.FORBIDDEN);
        }

        mapping.setReviewContribution(
                AuditPolicyInstanceControlMapping.ReviewContribution.valueOf(body.get("contribution")));
        policyCtrlMappingRepo.save(mapping);

        return ResponseEntity.ok(ApiResponse.success());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // LIST ENDPOINTS — tenant-scoped, optionally filtered by engagementId
    // ══════════════════════════════════════════════════════════════════════════

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    @GetMapping("/v1/audit/control-instances")
    @Operation(summary = "List all control instances for this tenant, optionally filtered by engagement")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listControlInstances(
            @RequestParam(required = false) Long engagementId) {
        var ctx = utilityService.getLoggedInDataContext();
        List<AuditControlInstance> items = engagementId != null
                ? controlRepo.findByEngagementId(requireListableEngagement(engagementId))
                : controlRepo.findByTenantIdOrderByControlCodeSnapshotAsc(ctx.getTenantId());
        items = onlyVisible(items, AuditControlInstance::getEngagementId);

        // One evaluator per engagement, prefetched — buildControlMap used to run
        // the workflow tier twice per row.
        Map<Long, com.kashi.grc.audit.service.ControlAccessGuard.Evaluator> evaluators = new HashMap<>();
        items.stream().collect(Collectors.groupingBy(AuditControlInstance::getEngagementId))
                .forEach((eid, ctrls) -> evaluators.put(eid,
                        controlAccessGuard.evaluator(eid, ctx.getId()).prefetchControls(ctrls)));
        return ResponseEntity.ok(ApiResponse.success(
                items.stream().map(c -> buildControlMap(c, evaluators.get(c.getEngagementId())))
                        .collect(Collectors.toList())));
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    @GetMapping("/v1/audit/test-instances")
    @Operation(summary = "List all test instances for this tenant, optionally filtered by engagement")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listTestInstances(
            @RequestParam(required = false) Long engagementId) {
        var ctx = utilityService.getLoggedInDataContext();
        List<AuditTestInstance> items = engagementId != null
                ? testRepo.findByEngagementIdOrderByTestNameSnapshotAsc(requireListableEngagement(engagementId))
                : testRepo.findByTenantIdOrderByTestNameSnapshotAsc(ctx.getTenantId());
        items = onlyVisible(items, AuditTestInstance::getEngagementId);
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

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    @GetMapping("/v1/audit/policy-instances")
    @Operation(summary = "List all policy instances for this tenant, optionally filtered by engagement")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listPolicyInstances(
            @RequestParam(required = false) Long engagementId) {
        var ctx = utilityService.getLoggedInDataContext();
        List<AuditPolicyInstance> items = engagementId != null
                ? policyRepo.findByEngagementIdOrderByTitleSnapshotAsc(requireListableEngagement(engagementId))
                : policyRepo.findByTenantIdOrderByTitleSnapshotAsc(ctx.getTenantId());
        items = onlyVisible(items, AuditPolicyInstance::getEngagementId);
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

    /**
     * An engagementId filter on a list endpoint: the engagement must exist in
     * the caller's tenant and be visible to a guest. Previously the id was used
     * as-is, so ?engagementId= read another tenant's instances.
     */
    private Long requireListableEngagement(Long engagementId) {
        var eng = engagementRepo.findById(engagementId)
                .orElseThrow(() -> new com.kashi.grc.common.exception.BusinessException(
                        "AUDIT_INSTANCE_NOT_ACCESSIBLE", "You do not have access to this record.",
                        org.springframework.http.HttpStatus.FORBIDDEN));
        controlAccessGuard.requireReadable(eng.getTenantId(), eng.getId());
        return engagementId;
    }

    /**
     * Tenant-wide lists: a vendor sees nothing, and a guest sees only the
     * engagements they are staffed on. HOME members are unaffected.
     */
    private <T> List<T> onlyVisible(List<T> items, java.util.function.Function<T, Long> engagementOf) {
        if (com.kashi.grc.common.config.multitenancy.AccessScope.isVendor()) {
            throw new com.kashi.grc.common.exception.BusinessException("AUDIT_INSTANCE_NOT_ACCESSIBLE",
                    "Vendor accounts do not have access to audit engagements",
                    org.springframework.http.HttpStatus.FORBIDDEN);
        }
        java.util.Set<Long> visible = com.kashi.grc.common.config.multitenancy.AccessScope.engagementIds();
        if (visible == null) return items;
        return items.stream().filter(i -> visible.contains(engagementOf.apply(i))).toList();
    }

    private Map<String, Object> buildControlMap(AuditControlInstance c,
                                                com.kashi.grc.audit.service.ControlAccessGuard.Evaluator ev) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",                       c.getId());
        // The frontend's requires_assignment gate reads entity.isAssignedToCurrentUser
        // and this endpoint never emitted it, so setting the flag on a ui_action was
        // a no-op and every header button showed to anyone holding the permission.
        // canAct covers assignee, section owner, engagement lead and workflow step.
        // Either side: the PERMISSION on each ui_action already decides which side
        // the user is (audit:control:record-test-result vs :submit-evidence), so
        // this flag only needs to answer "assigned to THIS control". Auditor-only
        // would have killed SUBMIT_EVIDENCE for the assigned auditee, whose
        // assignment lives in auditeeAssignedUserId, not assignedAuditorId.
        //
        // ...which still let a user holding BOTH permissions see both buttons when
        // only one side was theirs. canSubmitEvidence / canRecordResult answer each
        // side separately, and assignmentByPermission keys those answers by the
        // permission a ui_action carries, so the shared action filter gates a
        // requires_assignment button on the side it actually belongs to.
        boolean auditorSide = ev.canAct(c, false);
        boolean auditeeSide = ev.canAct(c, true);
        m.put("isAssignedToCurrentUser", auditorSide || auditeeSide);
        m.put("canRecordResult",         auditorSide);
        m.put("canSubmitEvidence",       auditeeSide);
        m.put("hasMyObligation",         ev.hasControlObligation(c.getId(), true)
                || ev.hasControlObligation(c.getId(), false));
        m.put("assignmentByPermission",  Map.of(
                "audit:control:submit-evidence",    auditeeSide,
                "audit:control:record-test-result", auditorSide,
                // AuditFindingController guards a control-linked finding with
                // requireCanRecordResult — same answer.
                "audit:finding:create",             auditorSide));
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
        // Authored on the library control and frozen here at engagement creation.
        // Null on rows created before the column existed, and on controls with no
        // guidance authored — getControlInstance then falls back to the test rollup.
        m.put("evidenceGuidanceSnapshot", c.getEvidenceGuidanceSnapshot());
        m.put("controlTagSnapshot",       c.getControlTagSnapshot());
        m.put("testTypeSnapshot",         c.getTestTypeSnapshot());
        m.put("frameworkRefSnapshot",     c.getFrameworkRefSnapshot());
        m.put("testResult",               c.getTestResult());
        m.put("testNotes",                c.getTestNotes());
        m.put("assignedAuditorId",        c.getAssignedAuditorId());
        m.put("auditeeAssignedUserId",    c.getAuditeeAssignedUserId());
        m.put("auditeeEvidenceSubmitted", c.isAuditeeEvidenceSubmitted());
        m.put("evidenceSubmittedAt",      c.getAuditeeEvidenceSubmittedAt());
        // Submitted without evidence / left untested, with the reason — and the
        // derived flag a ui_actions row hides "Not tested" on.
        m.put("evidenceGapReason",        c.getEvidenceGapReason());
        m.put("evidenceGapAt",            c.getEvidenceGapAt());
        m.put("notTestedReason",          c.getNotTestedReason());
        m.put("notTestedAt",              c.getNotTestedAt());
        m.put("testConcluded",            c.isTestConcluded());
        // "Ask to resubmit" — the evidence side (who may delegate the evidence)
        // can reopen what was submitted until the auditor concludes the control.
        m.put("canReopenEvidence", (c.isAuditeeEvidenceSubmitted()
                || (c.getEvidenceGapReason() != null && !c.getEvidenceGapReason().isBlank()))
                && !c.isTestConcluded() && ev.canDelegate(c, true));
        m.put("testedAt",                 c.getTestedAt());
        m.put("testedBy",                 c.getTestedBy());
        m.put("findingLinked",            c.isFindingLinked());
        m.put("findingIssueId",           c.getFindingIssueId());
        m.put("sectionBreadcrumbSnapshot",c.getSectionBreadcrumbSnapshot());
        m.put("sectionInstanceId",        c.getSectionInstanceId());
        return m;
    }

}