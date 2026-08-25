package com.kashi.grc.audit.controller;

import com.kashi.grc.audit.domain.*;
import com.kashi.grc.audit.repository.*;
import com.kashi.grc.audit.service.AuditTestPolicySnapshotService;
import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.util.UtilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * AuditTestController — REST API for audit tests (library + runtime instances).
 *
 * ── LIBRARY ENDPOINTS (admin) ────────────────────────────────────────────────
 * GET    /v1/audit/library/tests                  — list all tests
 * POST   /v1/audit/library/tests                  — create a test
 * GET    /v1/audit/library/tests/{id}             — get test
 * PUT    /v1/audit/library/tests/{id}             — update test
 * DELETE /v1/audit/library/tests/{id}             — delete test
 *
 * ── CONTROL↔TEST MAPPING (library) ──────────────────────────────────────────
 * GET    /v1/audit/library/controls/{cid}/tests   — list tests for a control
 * POST   /v1/audit/library/controls/{cid}/tests/{tid} — link test to control
 * DELETE /v1/audit/library/controls/{cid}/tests/{tid} — unlink test from control
 *
 * ── RUNTIME (engagement) ────────────────────────────────────────────────────
 * GET    /v1/audit/engagements/{eid}/tests            — list test instances for engagement
 * GET    /v1/audit/engagements/{eid}/tests/{tiid}     — get one test instance
 * PUT    /v1/audit/engagements/{eid}/tests/{tiid}/result — record test result
 * GET    /v1/audit/engagements/{eid}/controls/{ciid}/tests — tests for a control instance
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "Audit Tests", description = "Audit test library and runtime test instance management")
public class AuditTestController {

    private final AuditTestRepository                       testRepository;
    private final AuditControlTestMappingRepository         controlTestMappingRepository;
    private final com.kashi.grc.audit.service.AuditLibraryCacheService libraryCache;
    private final com.kashi.grc.audit.repository.AuditControlRepository controlRepository;
    private final AuditTestInstanceRepository               testInstanceRepository;
    private final AuditControlInstanceTestMappingRepository controlInstanceTestMappingRepository;
    private final AuditTestPolicySnapshotService            snapshotService;
    private final UtilityService                            utilityService;
    private final com.kashi.grc.evidence.repository.EvidenceLinkRepository evidenceLinkRepository;
    private final com.kashi.grc.audit.repository.AuditControlInstanceRepository controlInstanceRepository;

    // ══════════════════════════════════════════════════════════════════════════
    // LIBRARY — TESTS CRUD
    // ══════════════════════════════════════════════════════════════════════════

    @GetMapping("/v1/audit/library/tests")
    @Operation(summary = "List all tests in the library")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listTests(
            @RequestParam(required = false) String search) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();

        // Projection, not entities. This endpoint loaded full AuditTest rows —
        // test_procedure and evidence_guidance included, both TEXT — and took
        // 30-36 seconds for a screen showing seven short columns. The perf log
        // showed 3 queries, so the cost was the payload, not the round trips.
        //
        // NOTE: this narrows the list response. toTestMap emits testProcedure and
        // evidenceGuidance; the summary does not. Both are still returned by
        // GET /tests/{id} and by create/update, and nothing in the frontend reads
        // either field off the list — returning them here is what made it slow.
        var cached = libraryCache.testList(tenantId, search);

        // See AuditPolicyController.listPolicies — `editable` is per USER and the
        // cache key is per TENANT, so it is added after the read, onto a copy.
        boolean sysUser = utilityService.isSystemUser();
        List<Map<String, Object>> out = cached.stream().map(m -> {
            Map<String, Object> copy = new java.util.LinkedHashMap<>(m);
            copy.put("editable", !"GLOBAL".equals(m.get("origin")) || sysUser);
            return copy;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(out));
    }

    @GetMapping("/v1/audit/library/tests/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getTest(@PathVariable Long id) {
        AuditTest test = testRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AuditTest", id));
        Map<String, Object> result = toTestMap(test);

        // Include linked controls
        List<AuditControlTestMapping> mappings =
                controlTestMappingRepository.findByTestIdOrderByOrderNoAsc(id);
        result.put("linkedControlCount", mappings.size());
        result.put("linkedControlMappings", mappings.stream().map(m -> {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("controlId", m.getControlId());
            mm.put("isRequired", m.isRequired());
            mm.put("orderNo", m.getOrderNo());
            return mm;
        }).collect(Collectors.toList()));

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/v1/audit/library/tests")
    @Operation(summary = "Create a new test in the library")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createTest(
            @RequestBody AuditTestRequest req) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        Long userId   = utilityService.getLoggedInDataContext().getId();

        // ── Auto-generate testRef if not supplied ────────────────────────────────
        // Pattern: AT-001, AT-002, ... — padded 3-digit sequential per tenant.
        // Loops until a free ref is found (handles deletions leaving gaps).
        String resolvedRef = req.getTestRef();
        if (resolvedRef == null || resolvedRef.isBlank()) {
            long base = testRepository.countForTenant(tenantId) + 1;
            String candidate;
            do {
                candidate = String.format("AT-%03d", base++);
            } while (testRepository.existsByTestRefAndTenantId(candidate, tenantId));
            resolvedRef = candidate;
        }

        AuditTest test = AuditTest.builder()
                .name(req.getName())
                .description(req.getDescription())
                .testRef(resolvedRef)
                .frameworkTestId(req.getFrameworkTestId())
                .frameworkRef(req.getFrameworkRef())
                .controlTag(req.getControlTag() != null
                        ? req.getControlTag().trim().toUpperCase() : null)
                .automationType(req.getAutomationType() != null
                        ? AuditTest.AutomationType.valueOf(req.getAutomationType()) : AuditTest.AutomationType.MANUAL)
                .automationKey(req.getAutomationKey())
                .frequency(req.getFrequency() != null
                        ? AuditTest.Frequency.valueOf(req.getFrequency()) : AuditTest.Frequency.ANNUAL)
                .testProcedure(req.getTestProcedure())
                .evidenceGuidance(req.getEvidenceGuidance())
                .createdBy(userId)
                .tenantId(tenantId)
                .build();

        testRepository.save(test);
        log.info("[AUDIT-TEST] Created test | id={} name={}", test.getId(), test.getName());
        // See AuditPolicyController — library lists are cached, mutations evict.
        libraryCache.evictLibraryLists();

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(Map.of("id", test.getId(), "name", test.getName())));
    }

    // ══════════════════════════════════════════════════════════════════════
    // TESTS ARE SYSTEM-AUTHORED
    //
    // A test carries automationType, automationKey and controlTag — the three
    // fields the integration runner and the evidence-reuse engine match on. None
    // of these handlers checked ownership, so any org admin could rewrite or
    // delete a GLOBAL test and take its automationKey with it, breaking
    // automated evidence collection for every tenant on the instance.
    // ══════════════════════════════════════════════════════════════════════

    private void requireOwnedTest(AuditTest test) {
        if (utilityService.isSystemUser()) return;
        Long caller = utilityService.getLoggedInDataContext().getTenantId();
        if (java.util.Objects.equals(test.getTenantId(), caller)) return;

        log.warn("[AUDIT-TEST] Refused write to test {} (tenant {}) by tenant {}",
                test.getId(), test.getTenantId(), caller);
        throw new com.kashi.grc.common.exception.BusinessException("TEST_ACCESS_DENIED",
                test.getTenantId() == null
                        ? "This test belongs to the global library and cannot be modified"
                        : "You can only modify tests belonging to your organisation",
                HttpStatus.FORBIDDEN);
    }
    @PutMapping("/v1/audit/library/tests/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateTest(
            @PathVariable Long id, @RequestBody AuditTestRequest req) {
        AuditTest test = testRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AuditTest", id));
        requireOwnedTest(test);

        if (req.getName() != null)         test.setName(req.getName());
        if (req.getDescription() != null)  test.setDescription(req.getDescription());
        if (req.getTestRef() != null)      test.setTestRef(req.getTestRef());
        if (req.getFrameworkRef() != null) test.setFrameworkRef(req.getFrameworkRef());
        if (req.getControlTag() != null)   test.setControlTag(req.getControlTag().trim().toUpperCase());
        if (req.getAutomationType() != null)
            test.setAutomationType(AuditTest.AutomationType.valueOf(req.getAutomationType()));
        if (req.getAutomationKey() != null) test.setAutomationKey(req.getAutomationKey());
        if (req.getFrequency() != null)
            test.setFrequency(AuditTest.Frequency.valueOf(req.getFrequency()));
        if (req.getTestProcedure() != null) test.setTestProcedure(req.getTestProcedure());
        if (req.getEvidenceGuidance() != null) test.setEvidenceGuidance(req.getEvidenceGuidance());

        testRepository.save(test);
        // See AuditPolicyController — library lists are cached, mutations evict.
        libraryCache.evictLibraryLists();

        return ResponseEntity.ok(ApiResponse.success(toTestMap(test)));
    }

    @DeleteMapping("/v1/audit/library/tests")
    @Transactional
    @Operation(summary = "Bulk delete tests by ID list")
    public ResponseEntity<ApiResponse<Map<String, Object>>> bulkDeleteTests(
            @RequestParam List<Long> ids) {
        boolean isSystem  = utilityService.isSystemUser();
        Long callerTenant = utilityService.getLoggedInDataContext().getTenantId();

        int deleted = 0, skipped = 0;
        for (Long id : ids) {
            var found = testRepository.findById(id);
            if (found.isEmpty()) continue;

            // Skipped rather than thrown so a bulk action never aborts halfway.
            if (!isSystem && !java.util.Objects.equals(found.get().getTenantId(), callerTenant)) {
                skipped++;
                continue;
            }

            // Remove control-test mappings first
            controlTestMappingRepository.findByTestIdOrderByOrderNoAsc(id)
                    .forEach(controlTestMappingRepository::delete);
            testRepository.deleteById(id);
            deleted++;
        }
        if (skipped > 0)
            log.warn("[AUDIT-LIBRARY] Bulk delete skipped {} tests not owned by tenant {}", skipped, callerTenant);
        log.info("[AUDIT-LIBRARY] Bulk deleted {} tests", deleted);
        // See AuditPolicyController — library lists are cached, mutations evict.
        libraryCache.evictLibraryLists();

        return ResponseEntity.ok(ApiResponse.success(Map.of("deleted", deleted, "skipped", skipped)));
    }

    @DeleteMapping("/v1/audit/library/tests/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteTest(@PathVariable Long id) {
        // Loaded solely to check ownership — deleteById(id) previously took the
        // path variable straight to the database.
        AuditTest test = testRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AuditTest", id));
        requireOwnedTest(test);

        controlTestMappingRepository.deleteByTestId(id);
        testRepository.deleteById(id);
        log.info("[AUDIT-TEST] Deleted test id={}", id);
        // See AuditPolicyController — library lists are cached, mutations evict.
        libraryCache.evictLibraryLists();

        return ResponseEntity.ok(ApiResponse.success());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // LIBRARY — CONTROL ↔ TEST MAPPINGS
    // ══════════════════════════════════════════════════════════════════════════

    @GetMapping("/v1/audit/library/controls/{controlId}/tests")
    @Operation(summary = "List tests mapped to a library control")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listControlTests(
            @PathVariable Long controlId) {
        List<AuditControlTestMapping> mappings =
                controlTestMappingRepository.findByControlIdOrderByOrderNoAsc(controlId);

        // BATCHED — was one testRepository.findById() per row.
        List<Long> testIds = mappings.stream().map(AuditControlTestMapping::getTestId).toList();
        Map<Long, AuditTest> testsById = testIds.isEmpty() ? Map.of()
                : testRepository.findAllById(testIds).stream()
                  .collect(Collectors.toMap(AuditTest::getId, t -> t));

        List<Map<String, Object>> result = mappings.stream().map(m -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("mappingId",   m.getId());
            row.put("controlId",   m.getControlId());
            row.put("testId",      m.getTestId());
            row.put("isRequired",  m.isRequired());
            row.put("orderNo",     m.getOrderNo());
            row.put("mappingNote", m.getMappingNote());
            AuditTest t = testsById.get(m.getTestId());
            if (t != null) {
                row.put("testName",        t.getName());
                row.put("testRef",         t.getTestRef());
                row.put("automationType",  t.getAutomationType());
                row.put("frequency",       t.getFrequency());
                row.put("controlTag",      t.getControlTag());
            }
            return row;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/v1/audit/library/tests/{testId}/controls")
    @Operation(summary = "List controls mapped to a library test")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listTestControls(
            @PathVariable Long testId) {
        List<AuditControlTestMapping> mappings =
                controlTestMappingRepository.findByTestIdOrderByOrderNoAsc(testId);

        // BATCHED — was one controlRepository.findById() per row.
        List<Long> controlIds = mappings.stream().map(AuditControlTestMapping::getControlId).toList();
        Map<Long, AuditControl> controlsById = controlIds.isEmpty() ? Map.of()
                : controlRepository.findAllById(controlIds).stream()
                  .collect(Collectors.toMap(AuditControl::getId, c -> c));

        List<Map<String, Object>> result = mappings.stream().map(m -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("mappingId",   m.getId());
            row.put("testId",      m.getTestId());
            row.put("controlId",   m.getControlId());
            row.put("isRequired",  m.isRequired());
            row.put("orderNo",     m.getOrderNo());
            AuditControl c = controlsById.get(m.getControlId());
            if (c != null) {
                row.put("controlName", c.getName());
                row.put("controlCode", c.getControlCode());
                row.put("controlTag",  c.getControlTag());
                row.put("testType",    c.getTestType() != null ? c.getTestType().name() : null);
                row.put("frameworkRef",c.getFrameworkRef());
            }
            return row;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/v1/audit/library/controls/{controlId}/tests/{testId}")
    @Operation(summary = "Link a test to a library control")
    public ResponseEntity<ApiResponse<Map<String, Object>>> linkControlTest(
            @PathVariable Long controlId, @PathVariable Long testId,
            @RequestParam(defaultValue = "true")  boolean required,
            @RequestParam(defaultValue = "0")     int orderNo,
            @RequestParam(required = false)       String note) {

        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        Long userId   = utilityService.getLoggedInDataContext().getId();

        // Idempotent — if mapping exists, update it
        AuditControlTestMapping mapping = controlTestMappingRepository
                .findByControlIdAndTestId(controlId, testId)
                .orElseGet(() -> AuditControlTestMapping.builder()
                        .controlId(controlId)
                        .testId(testId)
                        .tenantId(tenantId)
                        .createdBy(userId)
                        .build());

        mapping.setRequired(required);
        mapping.setOrderNo(orderNo);
        if (note != null) mapping.setMappingNote(note);
        controlTestMappingRepository.save(mapping);

        // See AuditPolicyController — library lists are cached, mutations evict.
        libraryCache.evictLibraryLists();

        return ResponseEntity.ok(ApiResponse.success(
                Map.of("mappingId", mapping.getId(), "controlId", controlId, "testId", testId)));
    }

    @DeleteMapping("/v1/audit/library/controls/{controlId}/tests/{testId}")
    public ResponseEntity<ApiResponse<Void>> unlinkControlTest(
            @PathVariable Long controlId, @PathVariable Long testId) {
        // Unlinking a global control↔test mapping removes that test from every
        // tenant's next engagement, silently.
        requireOwnedTest(testRepository.findById(testId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditTest", testId)));

        controlTestMappingRepository.findByControlIdAndTestId(controlId, testId)
                .ifPresent(controlTestMappingRepository::delete);
        // See AuditPolicyController — library lists are cached, mutations evict.
        libraryCache.evictLibraryLists();

        return ResponseEntity.ok(ApiResponse.success());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // RUNTIME — TEST INSTANCES (per engagement)
    // ══════════════════════════════════════════════════════════════════════════

    @GetMapping("/v1/audit/engagements/{engagementId}/tests")
    @Operation(summary = "List test instances for an engagement")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listTestInstances(
            @PathVariable Long engagementId) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        List<AuditTestInstance> instances =
                testInstanceRepository.findByEngagementIdAndTenantId(engagementId, tenantId);
        return ResponseEntity.ok(ApiResponse.success(
                instances.stream().map(this::toTestInstanceMap).collect(Collectors.toList())));
    }

    @GetMapping("/v1/audit/engagements/{engagementId}/tests/{testInstanceId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getTestInstance(
            @PathVariable Long engagementId, @PathVariable Long testInstanceId) {
        AuditTestInstance instance = testInstanceRepository.findById(testInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditTestInstance", testInstanceId));
        return ResponseEntity.ok(ApiResponse.success(toTestInstanceMap(instance)));
    }

    @PutMapping("/v1/audit/engagements/{engagementId}/tests/{testInstanceId}/result")
    @Operation(summary = "Record test result for a test instance — auto-derives linked control results")
    public ResponseEntity<ApiResponse<Map<String, Object>>> recordTestResult(
            @PathVariable Long engagementId,
            @PathVariable Long testInstanceId,
            @RequestBody TestResultRequest req) {

        Long userId = utilityService.getLoggedInDataContext().getId();

        AuditTestInstance instance = testInstanceRepository.findById(testInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditTestInstance", testInstanceId));

        // Evidence must exist on the control(s) this test belongs to before recording
        // a result — same rule as the direct control-level test-result endpoint
        // (AuditEngagementService.recordTestResult). A test's result ultimately
        // derives the control's result via cascadeDeriveControlResults below, so
        // this is the path that actually needs the gate enforced in real usage.
        List<Long> linkedControlIds = controlInstanceTestMappingRepository
                .findControlInstanceIdsByTestInstanceId(testInstanceId);
        for (Long controlId : linkedControlIds) {
            AuditControlInstance control = controlInstanceRepository.findById(controlId).orElse(null);
            boolean hasEvidence = control != null && (
                    control.isAuditeeEvidenceSubmitted()
                            || evidenceLinkRepository.countAcceptedForEntity(
                            "AUDIT_CONTROL_INSTANCE", controlId) > 0
            );
            if (!hasEvidence) {
                throw new com.kashi.grc.common.exception.BusinessException(
                        "EVIDENCE_NOT_SUBMITTED",
                        "Evidence has not been submitted for the control this test belongs to. " +
                                "The auditee must upload evidence before the auditor can record a test result.");
            }
        }

        instance.setTestResult(AuditTestInstance.TestResult.valueOf(req.getTestResult()));
        instance.setTesterNotes(req.getTesterNotes());
        instance.setFailureDetail(req.getFailureDetail());
        instance.setRunAt(LocalDateTime.now());
        instance.setRunByUserId(userId);
        instance.setRunBySystem(false);
        testInstanceRepository.save(instance);

        // Cascade: re-derive testResult for all linked control instances
        snapshotService.cascadeDeriveControlResults(testInstanceId,
                utilityService.getLoggedInDataContext().getTenantId());

        log.info("[AUDIT-TEST] Result recorded | instanceId={} result={}", testInstanceId, req.getTestResult());
        return ResponseEntity.ok(ApiResponse.success(toTestInstanceMap(instance)));
    }

    @GetMapping("/v1/audit/engagements/{engagementId}/controls/{controlInstanceId}/tests")
    @Operation(summary = "List test instances for a specific control instance")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listControlInstanceTests(
            @PathVariable Long engagementId, @PathVariable Long controlInstanceId) {

        List<Long> testInstanceIds = controlInstanceTestMappingRepository
                .findByControlInstanceIdOrderByOrderNoAsc(controlInstanceId)
                .stream()
                .map(AuditControlInstanceTestMapping::getTestInstanceId)
                .collect(Collectors.toList());

        List<Map<String, Object>> result = testInstanceRepository.findAllById(testInstanceIds)
                .stream()
                .map(ti -> {
                    Map<String, Object> row = toTestInstanceMap(ti);
                    // Add mapping metadata
                    controlInstanceTestMappingRepository
                            .findByControlInstanceIdOrderByOrderNoAsc(controlInstanceId)
                            .stream()
                            .filter(m -> m.getTestInstanceId().equals(ti.getId()))
                            .findFirst()
                            .ifPresent(m -> {
                                row.put("isRequired",      m.isRequired());
                                row.put("mappingNote",     m.getMappingNoteSnapshot());
                            });
                    return row;
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // ── Serializers ────────────────────────────────────────────────────────────

    /**
     * List overload. Same key set as the entity version minus testProcedure and
     * evidenceGuidance — see AuditTestSummary for why those are absent.
     */
    private Map<String, Object> toTestMap(com.kashi.grc.audit.repository.AuditTestSummary t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",               t.id());
        m.put("name",             t.name());
        m.put("testRef",          t.testRef());
        m.put("description",      t.description());
        m.put("frameworkRef",     t.frameworkRef());
        m.put("frameworkTestId",  t.frameworkTestId());
        m.put("controlTag",       t.controlTag());
        m.put("automationType",   t.automationType() != null ? t.automationType().name() : null);
        m.put("automationKey",    t.automationKey());
        m.put("frequency",        t.frequency()      != null ? t.frequency().name()      : null);

        boolean globalT = t.tenantId() == null;
        m.put("origin",   globalT ? "GLOBAL" : "ORG");
        m.put("editable", !globalT || utilityService.isSystemUser());
        m.put("tenantId",         t.tenantId());
        m.put("createdAt",        t.createdAt());
        return m;
    }

    private Map<String, Object> toTestMap(AuditTest t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",               t.getId());
        m.put("name",             t.getName());
        m.put("testRef",          t.getTestRef());
        m.put("description",      t.getDescription());
        m.put("frameworkRef",     t.getFrameworkRef());
        m.put("frameworkTestId",  t.getFrameworkTestId());
        m.put("controlTag",       t.getControlTag());
        m.put("automationType",   t.getAutomationType() != null ? t.getAutomationType().name() : null);
        m.put("automationKey",    t.getAutomationKey());
        // See AuditPolicyController — lets the UI badge global rows and hide Edit.
        boolean globalT = t.getTenantId() == null;
        m.put("origin",   globalT ? "GLOBAL" : "ORG");
        m.put("editable", !globalT || utilityService.isSystemUser());
        m.put("frequency",        t.getFrequency() != null ? t.getFrequency().name() : null);
        m.put("testProcedure",    t.getTestProcedure());
        m.put("evidenceGuidance", t.getEvidenceGuidance());
        m.put("tenantId",         t.getTenantId());
        m.put("createdAt",        t.getCreatedAt());
        return m;
    }

    private Map<String, Object> toTestInstanceMap(AuditTestInstance t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",                   t.getId());
        m.put("originalTestId",       t.getOriginalTestId());
        m.put("engagementId",         t.getEngagementId());
        m.put("testNameSnapshot",     t.getTestNameSnapshot());
        m.put("testRefSnapshot",      t.getTestRefSnapshot());
        m.put("descriptionSnapshot",  t.getDescriptionSnapshot());
        m.put("testProcedureSnapshot",t.getTestProcedureSnapshot());
        m.put("evidenceGuidanceSnapshot", t.getEvidenceGuidanceSnapshot());
        m.put("frameworkRefSnapshot", t.getFrameworkRefSnapshot());
        m.put("controlTagSnapshot",   t.getControlTagSnapshot());
        m.put("automationTypeSnapshot", t.getAutomationTypeSnapshot());
        m.put("frequencySnapshot",    t.getFrequencySnapshot());
        m.put("testResult",           t.getTestResult());
        m.put("runAt",                t.getRunAt());
        m.put("runByUserId",          t.getRunByUserId());
        m.put("runBySystem",          t.isRunBySystem());
        m.put("testerNotes",          t.getTesterNotes());
        m.put("failureDetail",        t.getFailureDetail());
        m.put("affectedControlCount", t.getAffectedControlCount());
        m.put("snapshottedAt",        t.getSnapshottedAt());
        return m;
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    @Data
    public static class AuditTestRequest {
        @NotBlank private String name;
        private String description;
        private String testRef;
        private String frameworkTestId;
        private String frameworkRef;
        private String controlTag;
        private String automationType;  // AUTOMATED | MANUAL | HYBRID
        private String automationKey;
        private String frequency;       // CONTINUOUS | DAILY | WEEKLY | MONTHLY | QUARTERLY | ANNUAL
        private String testProcedure;
        private String evidenceGuidance;
    }

    @Data
    public static class TestResultRequest {
        @NotBlank private String testResult;  // PASS | FAIL | EXCEPTION
        private String testerNotes;
        private String failureDetail;
    }
}