package com.kashi.grc.audit.csv;

import com.kashi.grc.audit.domain.*;
import com.kashi.grc.audit.repository.*;
import com.kashi.grc.common.dto.CsvImportResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * AuditCsvImportExtension — extends AuditCsvImportService with TEST, POLICY, and MAPPING rows.
 *
 * ── NEW ROW TYPES ─────────────────────────────────────────────────────────────
 *
 * EXTENDS the existing CSV format. New rows can be intermixed with existing
 * TEMPLATE / SECTION / CONTROL rows or used in standalone mapping CSVs.
 *
 * ── FULL CSV FORMAT (extended) ───────────────────────────────────────────────
 *
 * REQUIRED HEADERS:
 *   type, name
 *
 * FULL HEADER SET:
 *   type, level, name, description, framework_ref, audit_type,
 *   section_code, control_code, test_type, control_tag, weight, is_mandatory, order_no,
 *   automation_type, automation_key, frequency, test_ref, evidence_guidance,
 *   policy_ref, content_type, owner_team, review_frequency_months,
 *   mapping_type, is_required, test_name, policy_title
 *
 * ROW TYPES:
 *   TEMPLATE              → creates AuditTemplate (existing)
 *   SECTION               → creates AuditSection + maps to template (existing)
 *   CONTROL               → creates AuditControl + maps to section (existing)
 *   TEST                  → creates AuditTest in the library
 *   POLICY                → creates AuditPolicy in the library (DRAFT status)
 *   CONTROL_TEST_MAPPING  → links an existing control (by control_code) to a test (by test_ref or name)
 *   POLICY_CONTROL_MAPPING→ links an existing policy (by policy_ref or title) to a control (by control_code)
 *
 * ── IDEMPOTENCY ──────────────────────────────────────────────────────────────
 * All operations are find-or-create. Re-importing the same CSV is safe.
 *
 * ── EXAMPLE CSV ──────────────────────────────────────────────────────────────
 * See: AuditCsvImportExtension.EXAMPLE_CSV_CONTENT constant below.
 *
 * ── TRANSACTION STRATEGY ─────────────────────────────────────────────────────
 * Same as AuditCsvImportService: each row is REQUIRES_NEW.
 * One bad row logs and continues — partial success is valid.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditCsvImportExtension {

    private final AuditTestRepository              testRepository;
    private final AuditControlTestMappingRepository controlTestMappingRepository;
    private final AuditControlRepository            controlRepository;
    private final AuditPolicyRepository             policyRepository;
    private final AuditPolicyControlMappingRepository policyControlMappingRepository;

    /**
     * Example CSV content downloadable from the UI.
     * Shows all row types including the new TEST, POLICY, and MAPPING rows.
     */
    public static final String EXAMPLE_CSV_CONTENT =
            "type,name,description,control_code,test_ref,policy_ref,framework_ref,audit_type," +
                    "section_code,test_type,control_tag,weight,is_mandatory,automation_type,automation_key," +
                    "frequency,evidence_guidance,content_type,review_frequency_months,mapping_type,is_required\n" +
                    "TEMPLATE,\"ISO 27001 Internal Audit\",,,,,,ISO 27001,INTERNAL,,,,,,,,,,,,\n" +
                    "SECTION,\"A — Organisational Controls\",,,,,,,,A,,,,,,,,,,,\n" +
                    "SECTION,\"A.9 — Access Control\",,,,,,,,A.9,,,,,,,,,,,\n" +
                    "CONTROL,\"User access management\",,A.9.1.1,,,,ISO 27001,,DOCUMENT_REVIEW,ACCESS_MGMT,1.0,true,,,,,,,,\n" +
                    "CONTROL,\"MFA enforcement\",,A.9.4.2,,,,ISO 27001,,TECHNICAL_TEST,MFA,1.0,true,,,,,,,,\n" +
                    "TEST,\"Quarterly access review completed\",\"Verify that user access reviews are performed quarterly and documented\",,,QACR-001,ISO 27001,,,,,,,MANUAL,,QUARTERLY,\"Evidence: Signed access review reports for all four quarters\",,,,\n" +
                    "TEST,\"MFA enforced on all production systems\",\"Verify MFA is enabled for all users accessing production\",,,MFA-001,ISO 27001,,,,,,,AUTOMATED,kashiguard.mfa_enforced,CONTINUOUS,\"Evidence: IAM console screenshot showing MFA enforced\",,,,\n" +
                    "POLICY,\"Access Control Policy\",\"Defines requirements for user access management and authentication\",,,,,,,,,,,,,,,RICH_TEXT,12,,\n" +
                    "POLICY,\"Information Security Policy\",\"Top-level ISMS policy\",,,,,,,,,,,,,,,RICH_TEXT,12,,\n" +
                    "CONTROL_TEST_MAPPING,,,A.9.1.1,QACR-001,,,,,,,,,,,,,,,,true\n" +
                    "CONTROL_TEST_MAPPING,,,A.9.4.2,MFA-001,,,,,,,,,,,,,,,,true\n" +
                    "POLICY_CONTROL_MAPPING,,,A.9.1.1,,\"Access Control Policy\",,,,,,,,,,,,,,,DIRECT,\n" +
                    "POLICY_CONTROL_MAPPING,,,A.9.4.2,,\"Access Control Policy\",,,,,,,,,,,,,,,DIRECT,\n" +
                    "POLICY_CONTROL_MAPPING,,,A.9.1.1,,\"Information Security Policy\",,,,,,,,,,,,,,,PARTIAL,\n";

    /**
     * Import tests, policies, and mappings from CSV.
     * Designed to work standalone OR appended to the existing TEMPLATE/SECTION/CONTROL CSV.
     *
     * @param file      the CSV file
     * @param tenantId  tenant (null for global/Platform Admin)
     * @param createdBy user performing the import
     */
    public CsvImportResult importTestsPoliciesAndMappings(
            MultipartFile file, Long tenantId, Long createdBy) {

        log.info("[AUDIT-CSV-EXT] Import started | tenantId={} file={}", tenantId,
                file.getOriginalFilename());

        List<CsvImportResult.ImportLogEntry> importLog = new ArrayList<>();
        int successCount = 0, failureCount = 0, totalRows = 0;

        // Context maps — built during import for cross-row lookups
        // testRef/name → AuditTest.id
        Map<String, Long> testRefToId   = new HashMap<>();
        // policy_ref/title → AuditPolicy.id
        Map<String, Long> policyRefToId = new HashMap<>();
        // control_code → AuditControl.id
        Map<String, Long> controlCodeToId = new HashMap<>();

        // Pre-populate from existing library data
        testRepository.findByTenantIdIsNullOrTenantId(tenantId)
                .forEach(t -> {
                    if (t.getTestRef() != null) testRefToId.put(t.getTestRef(), t.getId());
                    testRefToId.put(t.getName().toLowerCase(), t.getId());
                });
        policyRepository.findByTenantIdOrderByTitleAsc(tenantId)
                .forEach(p -> {
                    if (p.getPolicyRef() != null) policyRefToId.put(p.getPolicyRef(), p.getId());
                    policyRefToId.put(p.getTitle().toLowerCase(), p.getId());
                });
        controlRepository.findByTenantIdIsNullOrTenantId(tenantId)
                .forEach(c -> {
                    if (c.getControlCode() != null) controlCodeToId.put(c.getControlCode(), c.getId());
                });

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {

            String headerLine = reader.readLine();
            if (headerLine == null) {
                return CsvImportResult.builder().fatalError(true).summary("Empty file").build();
            }

            String[] headers = splitCsvLine(headerLine);
            Map<String, Integer> colIdx = buildColIndex(headers);

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                totalRows++;
                String[] cols = splitCsvLine(line);
                String type   = getCsvValue(cols, colIdx, "type");

                try {
                    switch (type.toUpperCase()) {
                        case "TEST" -> {
                            Long id = upsertTest(cols, colIdx, tenantId, createdBy);
                            String testRef = getCsvValue(cols, colIdx, "test_ref");
                            String name    = getCsvValue(cols, colIdx, "name");
                            if (testRef != null && !testRef.isBlank()) testRefToId.put(testRef, id);
                            if (name    != null && !name.isBlank())    testRefToId.put(name.toLowerCase(), id);
                            importLog.add(CsvImportResult.ImportLogEntry.builder().status("SUCCESS").message("TEST: " + name).build());
                            successCount++;
                        }
                        case "POLICY" -> {
                            Long id = upsertPolicy(cols, colIdx, tenantId, createdBy);
                            String ref   = getCsvValue(cols, colIdx, "policy_ref");
                            String title = getCsvValue(cols, colIdx, "name");
                            if (ref   != null && !ref.isBlank())   policyRefToId.put(ref, id);
                            if (title != null && !title.isBlank()) policyRefToId.put(title.toLowerCase(), id);
                            importLog.add(CsvImportResult.ImportLogEntry.builder().status("SUCCESS").message("POLICY: " + title).build());
                            successCount++;
                        }
                        case "CONTROL_TEST_MAPPING" -> {
                            String controlCode = getCsvValue(cols, colIdx, "control_code");
                            String testRef     = getCsvValue(cols, colIdx, "test_ref");
                            String testName    = getCsvValue(cols, colIdx, "test_name");
                            boolean isRequired = !"false".equalsIgnoreCase(
                                    getCsvValue(cols, colIdx, "is_required"));

                            Long controlId = controlCodeToId.get(controlCode);
                            Long testId    = testRef != null ? testRefToId.get(testRef)
                                    : testName != null ? testRefToId.get(testName.toLowerCase()) : null;

                            if (controlId == null || testId == null) {
                                importLog.add(CsvImportResult.ImportLogEntry.builder().status("WARNING").message("CONTROL_TEST_MAPPING skipped — control=" + controlCode + " test=" + (testRef != null ? testRef : testName) + " not found").build());
                                failureCount++;
                            } else {
                                upsertControlTestMapping(controlId, testId, isRequired, tenantId, createdBy);
                                importLog.add(CsvImportResult.ImportLogEntry.builder().status("SUCCESS").message("CTRL→TEST: " + controlCode + " → " + (testRef != null ? testRef : testName)).build());
                                successCount++;
                            }
                        }
                        case "POLICY_CONTROL_MAPPING" -> {
                            String controlCode  = getCsvValue(cols, colIdx, "control_code");
                            String policyRef    = getCsvValue(cols, colIdx, "policy_ref");
                            String policyTitle  = getCsvValue(cols, colIdx, "policy_title");
                            String mappingType  = getCsvValue(cols, colIdx, "mapping_type");

                            Long controlId = controlCodeToId.get(controlCode);
                            Long policyId  = policyRef != null ? policyRefToId.get(policyRef)
                                    : policyTitle != null ? policyRefToId.get(policyTitle.toLowerCase()) : null;

                            if (controlId == null || policyId == null) {
                                importLog.add(CsvImportResult.ImportLogEntry.builder().status("WARNING").message("POLICY_CONTROL_MAPPING skipped — control=" + controlCode + " policy=" + (policyRef != null ? policyRef : policyTitle) + " not found").build());
                                failureCount++;
                            } else {
                                upsertPolicyControlMapping(policyId, controlId, mappingType, tenantId, createdBy);
                                importLog.add(CsvImportResult.ImportLogEntry.builder().status("SUCCESS").message("POLICY→CTRL: " + (policyRef != null ? policyRef : policyTitle) + " → " + controlCode).build());
                                successCount++;
                            }
                        }
                        default -> {
                            // Unknown type — skip silently (handled by main AuditCsvImportService)
                        }
                    }
                } catch (Exception e) {
                    log.error("[AUDIT-CSV-EXT] Row {} failed: {}", totalRows, e.getMessage());
                    importLog.add(CsvImportResult.ImportLogEntry.builder().status("ERROR").message("Row " + totalRows + ": " + e.getMessage()).build());
                    failureCount++;
                }
            }
        } catch (Exception e) {
            log.error("[AUDIT-CSV-EXT] Fatal error: {}", e.getMessage(), e);
            return CsvImportResult.builder()
                    .fatalError(true)
                    .summary("Fatal error: " + e.getMessage())
                    .build();
        }

        String summary = String.format(
                "Import complete: %d succeeded, %d failed, %d total rows",
                successCount, failureCount, totalRows);
        log.info("[AUDIT-CSV-EXT] {}", summary);

        return CsvImportResult.builder()
                .fatalError(false)
                .summary(summary)
                .totalRows(totalRows)
                .successCount(successCount)
                .failureCount(failureCount)
                .log(importLog)
                .build();
    }

    // ── Upsert helpers ─────────────────────────────────────────────────────────

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long upsertTest(String[] cols, Map<String, Integer> colIdx,
                           Long tenantId, Long createdBy) {
        String name    = getCsvValue(cols, colIdx, "name");
        String testRef = getCsvValue(cols, colIdx, "test_ref");

        // Find existing by testRef or name
        AuditTest test = testRef != null && !testRef.isBlank()
                ? testRepository.findAll().stream()
                  .filter(t -> testRef.equalsIgnoreCase(t.getTestRef()))
                  .filter(t -> tenantId == null
                               ? t.getTenantId() == null
                               : tenantId.equals(t.getTenantId()))
                  .findFirst().orElse(null)
                : null;

        if (test == null) {
            test = AuditTest.builder()
                    .name(name)
                    .tenantId(tenantId)
                    .createdBy(createdBy)
                    .build();
        }

        test.setName(name);
        if (testRef != null && !testRef.isBlank()) test.setTestRef(testRef);

        String desc    = getCsvValue(cols, colIdx, "description");
        String fwRef   = getCsvValue(cols, colIdx, "framework_ref");
        String tag     = getCsvValue(cols, colIdx, "control_tag");
        String autoType = getCsvValue(cols, colIdx, "automation_type");
        String autoKey = getCsvValue(cols, colIdx, "automation_key");
        String freq    = getCsvValue(cols, colIdx, "frequency");
        String proc    = getCsvValue(cols, colIdx, "test_procedure");
        String guid    = getCsvValue(cols, colIdx, "evidence_guidance");

        if (desc    != null && !desc.isBlank())    test.setDescription(desc);
        if (fwRef   != null && !fwRef.isBlank())   test.setFrameworkRef(fwRef);
        if (tag     != null && !tag.isBlank())     test.setControlTag(tag.trim().toUpperCase());
        if (autoKey != null && !autoKey.isBlank()) test.setAutomationKey(autoKey);
        if (proc    != null && !proc.isBlank())    test.setTestProcedure(proc);
        if (guid    != null && !guid.isBlank())    test.setEvidenceGuidance(guid);

        try {
            if (autoType != null && !autoType.isBlank())
                test.setAutomationType(AuditTest.AutomationType.valueOf(autoType.toUpperCase()));
        } catch (IllegalArgumentException ignored) {
            test.setAutomationType(AuditTest.AutomationType.MANUAL);
        }
        try {
            if (freq != null && !freq.isBlank())
                test.setFrequency(AuditTest.Frequency.valueOf(freq.toUpperCase()));
        } catch (IllegalArgumentException ignored) {
            test.setFrequency(AuditTest.Frequency.ANNUAL);
        }

        testRepository.save(test);
        log.debug("[AUDIT-CSV-EXT] Upserted test | id={} name={}", test.getId(), test.getName());
        return test.getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long upsertPolicy(String[] cols, Map<String, Integer> colIdx,
                             Long tenantId, Long createdBy) {
        String title     = getCsvValue(cols, colIdx, "name");
        String policyRef = getCsvValue(cols, colIdx, "policy_ref");

        AuditPolicy policy = policyRef != null && !policyRef.isBlank()
                ? policyRepository.findByPolicyRefAndTenantId(policyRef, tenantId).orElse(null)
                : null;

        if (policy == null) {
            policy = AuditPolicy.builder()
                    .title(title)
                    .status(AuditPolicy.PolicyStatus.DRAFT)
                    .version(1)
                    .tenantId(tenantId)
                    .createdBy(createdBy)
                    .build();
        }

        policy.setTitle(title);
        if (policyRef != null && !policyRef.isBlank()) policy.setPolicyRef(policyRef);

        String desc    = getCsvValue(cols, colIdx, "description");
        String ctType  = getCsvValue(cols, colIdx, "content_type");
        // FIX: policy uses plural column names control_tags / framework_refs, not singular
        String tags    = getCsvValue(cols, colIdx, "control_tags");
        String fwRefs  = getCsvValue(cols, colIdx, "framework_refs");
        String revFreq = getCsvValue(cols, colIdx, "review_frequency_months");
        String team    = getCsvValue(cols, colIdx, "owner_team");

        if (desc   != null && !desc.isBlank())  policy.setDescription(desc);
        if (tags   != null && !tags.isBlank())  policy.setControlTags(tags);
        if (fwRefs != null && !fwRefs.isBlank()) policy.setFrameworkRefs(fwRefs);
        if (team   != null && !team.isBlank())  policy.setOwnerTeam(team);
        try {
            if (ctType != null && !ctType.isBlank())
                policy.setContentType(AuditPolicy.ContentType.valueOf(ctType.toUpperCase()));
        } catch (IllegalArgumentException ignored) {}
        try {
            if (revFreq != null && !revFreq.isBlank())
                policy.setReviewFrequencyMonths(Integer.parseInt(revFreq.trim()));
        } catch (NumberFormatException ignored) {}

        policyRepository.save(policy);
        return policy.getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void upsertControlTestMapping(Long controlId, Long testId,
                                         boolean isRequired, Long tenantId, Long createdBy) {
        AuditControlTestMapping mapping = controlTestMappingRepository
                .findByControlIdAndTestId(controlId, testId)
                .orElseGet(() -> AuditControlTestMapping.builder()
                        .controlId(controlId)
                        .testId(testId)
                        .tenantId(tenantId)
                        .createdBy(createdBy)
                        .build());
        mapping.setRequired(isRequired);
        controlTestMappingRepository.save(mapping);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void upsertPolicyControlMapping(Long policyId, Long controlId,
                                           String mappingType, Long tenantId, Long createdBy) {
        AuditPolicyControlMapping mapping = policyControlMappingRepository
                .findByPolicyIdAndControlId(policyId, controlId)
                .orElseGet(() -> AuditPolicyControlMapping.builder()
                        .policyId(policyId)
                        .controlId(controlId)
                        .tenantId(tenantId)
                        .createdBy(createdBy)
                        .build());
        try {
            if (mappingType != null && !mappingType.isBlank())
                mapping.setMappingType(AuditPolicyControlMapping.MappingType.valueOf(
                        mappingType.toUpperCase()));
        } catch (IllegalArgumentException ignored) {}
        policyControlMappingRepository.save(mapping);
    }

    // ── CSV parsing helpers ───────────────────────────────────────────────────

    private Map<String, Integer> buildColIndex(String[] headers) {
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < headers.length; i++) {
            idx.put(headers[i].trim().toLowerCase().replace(" ", "_"), i);
        }
        return idx;
    }

    private String getCsvValue(String[] cols, Map<String, Integer> colIdx, String key) {
        Integer idx = colIdx.get(key);
        if (idx == null || idx >= cols.length) return null;
        String v = cols[idx].trim();
        if (v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1).trim();
        return v.isBlank() ? null : v;
    }

    private String[] splitCsvLine(String line) {
        List<String> result = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder current = new StringBuilder();
        for (char c : line.toCharArray()) {
            if (c == '"') { inQuotes = !inQuotes; }
            else if (c == ',' && !inQuotes) { result.add(current.toString()); current.setLength(0); }
            else { current.append(c); }
        }
        result.add(current.toString());
        return result.toArray(new String[0]);
    }
}