package com.kashi.grc.audit.csv;

import com.kashi.grc.audit.domain.*;
import com.kashi.grc.audit.repository.*;
import com.kashi.grc.audit.service.AuditSectionService;
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
import java.time.Year;
import java.util.*;

/**
 * AuditCsvImportService — extended to support TEST, POLICY,
 * CONTROL_TEST_MAPPING, and POLICY_CONTROL_MAPPING row types.
 *
 * REPLACES the existing AuditCsvImportService.java entirely.
 * All existing TEMPLATE / SECTION / CONTROL row logic is preserved unchanged.
 *
 * ── NEW ROW TYPES ──────────────────────────────────────────────────────────────
 *
 * TEST
 *   Creates/updates an AuditTest in the library.
 *   Required columns: type, name
 *   Optional columns: test_ref, description, framework_ref, automation_type,
 *                     frequency, control_tag, test_procedure, evidence_guidance,
 *                     automation_key
 *
 * POLICY
 *   Creates/updates an AuditPolicy in the library (status=DRAFT).
 *   Required columns: type, name (= title)
 *   Optional columns: policy_ref, description, framework_refs, control_tags,
 *                     content_type, review_frequency_months, owner_team
 *
 * CONTROL_TEST_MAPPING
 *   Links an AuditControl to an AuditTest (many-to-many).
 *   Required columns: type, control_code, test_ref
 *   Optional columns: is_required, order_no, mapping_note
 *   Idempotent — re-importing is safe.
 *
 * POLICY_CONTROL_MAPPING
 *   Links an AuditPolicy to an AuditControl.
 *   Required columns: type, policy_ref, control_code
 *   Optional columns: mapping_note
 *   Idempotent — re-importing is safe.
 *
 * ── FULL HEADER SET ────────────────────────────────────────────────────────────
 * type, level, name, description, framework_ref, framework_refs, audit_type,
 * section_code, control_code, test_type, control_tag, control_tags,
 * weight, is_mandatory, order_no, is_required,
 * test_ref, automation_type, automation_key, frequency,
 * test_procedure, evidence_guidance,
 * policy_ref, content_type, review_frequency_months, owner_team,
 * mapping_note
 *
 * ── SOC 2 IMPORT ORDER ────────────────────────────────────────────────────────
 * 1. TEMPLATE row
 * 2. SECTION rows (level=0 for categories CC/A/PI/C/P, level=1 for sub-sections)
 * 3. CONTROL rows (one per SOC 2 criterion)
 * 4. TEST rows (one per test)
 * 5. CONTROL_TEST_MAPPING rows (link controls ↔ tests)
 * 6. POLICY rows (one per policy)
 * 7. POLICY_CONTROL_MAPPING rows (link policies ↔ controls)
 *
 * This order ensures foreign keys exist before mappings reference them.
 * A single CSV file can contain all row types in this order.
 *
 * ── IDEMPOTENCY ───────────────────────────────────────────────────────────────
 * All upserts are find-or-create. Re-importing the same CSV is fully safe.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditCsvImportService {

    // ── Existing repositories (unchanged) ─────────────────────────────────────
    private final AuditTemplateRepository               templateRepository;
    private final AuditSectionRepository                sectionRepository;
    private final AuditControlRepository                controlRepository;
    private final AuditTemplateSectionMappingRepository templateSectionMappingRepository;
    private final AuditSectionControlMappingRepository  sectionControlMappingRepository;
    private final AuditSectionService                   sectionService;

    // ── New repositories for TEST / POLICY support ────────────────────────────
    private final AuditTestRepository                   testRepository;
    private final AuditPolicyRepository                 policyRepository;
    private final AuditControlTestMappingRepository     controlTestMappingRepository;
    private final AuditPolicyControlMappingRepository   policyControlMappingRepository;

    /**
     * Import an audit library from CSV. Supports all 7 row types.
     * NOT @Transactional — each row upsert commits independently via REQUIRES_NEW.
     */
    public CsvImportResult importLibraryCsv(MultipartFile file, Long tenantId, Long createdBy) {
        log.info("[AUDIT-CSV] Import started | tenantId={} | file={} | size={}",
                tenantId, file.getOriginalFilename(), file.getSize());

        CsvImportResult.CsvImportResultBuilder result =
                CsvImportResult.builder().totalRows(0).successCount(0).failureCount(0);

        if (file == null || file.isEmpty())
            return result.fatalError(true).summary("No file uploaded").build();
        if (!Objects.requireNonNullElse(file.getOriginalFilename(), "")
                .toLowerCase().endsWith(".csv"))
            return result.fatalError(true).summary("Only .csv files are accepted").build();

        List<CsvImportResult.ImportLogEntry> importLog = new ArrayList<>();
        int successCount = 0, failureCount = 0, totalRows = 0;
        Long templateId = null;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {

            String headerLine = reader.readLine();
            if (headerLine == null)
                return result.fatalError(true).summary("File is empty").build();

            Map<String, Integer> h = parseHeaders(headerLine);
            if (!h.containsKey("type") || !h.containsKey("name"))
                return result.fatalError(true)
                        .summary("CSV must have at least 'type' and 'name' columns").build();

            AuditTemplate              currentTemplate  = null;
            Map<Integer, AuditSection> sectionStack     = new LinkedHashMap<>();
            int                        rootSectionCount = 0;
            int rowNum = 1;
            String line;

            while ((line = reader.readLine()) != null) {
                rowNum++;
                totalRows++;
                if (line.isBlank() || line.startsWith("#")) { totalRows--; continue; }

                String[] cols = parseCsvLine(line);
                String   type = col(cols, h, "type").toUpperCase().trim();

                try {
                    switch (type) {

                        // ── Existing row types (unchanged logic) ─────────────

                        case "TEMPLATE" -> {
                            sectionStack.clear();
                            rootSectionCount = 0;
                            currentTemplate = upsertTemplate(cols, h, tenantId, createdBy);
                            templateId      = currentTemplate.getId();
                            String msg = String.format("Template: \"%s\" (id=%d)",
                                    currentTemplate.getName(), templateId);
                            log.info("[AUDIT-CSV] Row {}: {}", rowNum, msg);
                            importLog.add(entry(msg, "SUCCESS"));
                            successCount++;
                        }

                        case "SECTION" -> {
                            if (currentTemplate == null) {
                                String msg = "Row " + rowNum + ": SECTION before TEMPLATE — skipped";
                                log.warn("[AUDIT-CSV] {}", msg);
                                importLog.add(entry(msg, "ERROR")); failureCount++; continue;
                            }
                            int level = parseInt(col(cols, h, "level"), 0);
                            sectionStack.entrySet().removeIf(e -> e.getKey() >= level);
                            Long parentId = level == 0 ? null
                                    : sectionStack.containsKey(level - 1)
                                      ? sectionStack.get(level - 1).getId() : null;
                            int orderNo = parseInt(col(cols, h, "order_no"),
                                    level == 0 ? ++rootSectionCount : countChildrenOf(parentId));
                            AuditSection section = upsertSection(cols, h, tenantId, parentId,
                                    orderNo, createdBy);
                            sectionStack.put(level, section);
                            if (level == 0)
                                upsertTemplateSectionMapping(currentTemplate.getId(),
                                        section.getId(), orderNo);
                            String msg = String.format("  Section[%d]: \"%s\" (id=%d)",
                                    level, section.getName(), section.getId());
                            log.info("[AUDIT-CSV] Row {}: {}", rowNum, msg);
                            importLog.add(entry(msg, "SUCCESS"));
                            successCount++;
                        }

                        case "CONTROL" -> {
                            AuditControl control = upsertControl(cols, h, tenantId, createdBy);
                            String sectionCodeCol = col(cols, h, "section_code");

                            // Resolve target section:
                            // Priority 1 — explicit section_code column (e.g. "CC6.1")
                            // Priority 2 — derive from control_code prefix (e.g. "CC6.1-C1" → "CC6.1")
                            // Priority 3 — fall back to deepest section in sectionStack
                            AuditSection target = null;

                            if (!sectionCodeCol.isBlank()) {
                                // Exact match first, then prefix match (CC6 matches CC6.x)
                                String sc = sectionCodeCol.trim();
                                target = sectionRepository.findAll().stream()
                                        .filter(s -> sc.equals(s.getSectionCode())
                                                && Objects.equals(s.getTenantId(), tenantId))
                                        .findFirst()
                                        // Fallback: section whose code starts with our prefix
                                        .orElseGet(() -> sectionRepository.findAll().stream()
                                                .filter(s -> s.getSectionCode() != null
                                                        && s.getSectionCode().startsWith(sc)
                                                        && Objects.equals(s.getTenantId(), tenantId))
                                                .findFirst().orElse(null));
                            }

                            if (target == null) {
                                // Try deriving section code from control_code prefix
                                // e.g. "CC6.1-C1" → prefix "CC6.1"
                                String ctrlCode = col(cols, h, "control_code");
                                if (!ctrlCode.isBlank() && ctrlCode.contains("-")) {
                                    String prefix = ctrlCode.substring(0, ctrlCode.lastIndexOf('-'));
                                    target = sectionRepository.findAll().stream()
                                            .filter(s -> prefix.equals(s.getSectionCode())
                                                    && Objects.equals(s.getTenantId(), tenantId))
                                            .findFirst().orElse(null);
                                }
                            }

                            if (target == null) {
                                // Fall back to deepest section in stack
                                if (sectionStack.isEmpty()) {
                                    String msg = "Row " + rowNum + ": CONTROL — no section resolved, skipped";
                                    log.warn("[AUDIT-CSV] {}", msg);
                                    importLog.add(entry(msg, "ERROR")); failureCount++; continue;
                                }
                                int deepest = sectionStack.keySet().stream()
                                        .mapToInt(Integer::intValue).max().orElse(0);
                                target = sectionStack.get(deepest);
                            }

                            int ctrlOrder = parseInt(col(cols, h, "order_no"),
                                    countControlsIn(target.getId()));
                            double weight = parseDouble(col(cols, h, "weight"), 1.0);
                            boolean mand  = parseBool(col(cols, h, "is_mandatory"), false);
                            upsertSectionControlMapping(target.getId(), control.getId(),
                                    ctrlOrder, weight, mand);
                            String msg = String.format("    Control: \"%s\" → %s (id=%d)",
                                    control.getName(),
                                    target.getSectionCode() != null
                                            ? target.getSectionCode() : target.getName(),
                                    control.getId());
                            log.info("[AUDIT-CSV] Row {}: {}", rowNum, msg);
                            importLog.add(entry(msg, "SUCCESS"));
                            successCount++;
                        }

                        // ── NEW: TEST row ────────────────────────────────────

                        case "TEST" -> {
                            AuditTest test = upsertTest(cols, h, tenantId, createdBy);
                            String msg = String.format("  Test: \"%s\" ref=%s (id=%d)",
                                    test.getName(), test.getTestRef(), test.getId());
                            log.info("[AUDIT-CSV] Row {}: {}", rowNum, msg);
                            importLog.add(entry(msg, "SUCCESS"));
                            successCount++;
                        }

                        // ── NEW: POLICY row ──────────────────────────────────

                        case "POLICY" -> {
                            AuditPolicy policy = upsertPolicy(cols, h, tenantId, createdBy);
                            String msg = String.format("  Policy: \"%s\" ref=%s (id=%d)",
                                    policy.getTitle(), policy.getPolicyRef(), policy.getId());
                            log.info("[AUDIT-CSV] Row {}: {}", rowNum, msg);
                            importLog.add(entry(msg, "SUCCESS"));
                            successCount++;
                        }

                        // ── NEW: CONTROL_TEST_MAPPING row ────────────────────

                        case "CONTROL_TEST_MAPPING" -> {
                            String controlCode = col(cols, h, "control_code");
                            String testRef     = col(cols, h, "test_ref");
                            if (controlCode.isBlank() || testRef.isBlank()) {
                                String msg = "Row " + rowNum +
                                        ": CONTROL_TEST_MAPPING requires control_code and test_ref — skipped";
                                importLog.add(entry(msg, "ERROR")); failureCount++; continue;
                            }
                            String mappingMsg = upsertControlTestMapping(
                                    controlCode, testRef, tenantId, cols, h, createdBy);
                            log.info("[AUDIT-CSV] Row {}: {}", rowNum, mappingMsg);
                            importLog.add(entry(mappingMsg, "SUCCESS"));
                            successCount++;
                        }

                        // ── NEW: POLICY_CONTROL_MAPPING row ─────────────────

                        case "POLICY_CONTROL_MAPPING" -> {
                            String policyRef   = col(cols, h, "policy_ref");
                            String controlCode = col(cols, h, "control_code");
                            if (policyRef.isBlank() || controlCode.isBlank()) {
                                String msg = "Row " + rowNum +
                                        ": POLICY_CONTROL_MAPPING requires policy_ref and control_code — skipped";
                                importLog.add(entry(msg, "ERROR")); failureCount++; continue;
                            }
                            String mappingMsg = upsertPolicyControlMapping(
                                    policyRef, controlCode, tenantId, cols, h, createdBy);
                            log.info("[AUDIT-CSV] Row {}: {}", rowNum, mappingMsg);
                            importLog.add(entry(mappingMsg, "SUCCESS"));
                            successCount++;
                        }

                        default -> {
                            if (!type.isEmpty()) {
                                String msg = "Row " + rowNum + ": unknown type \""
                                        + type + "\" — skipped";
                                log.warn("[AUDIT-CSV] {}", msg);
                                importLog.add(entry(msg, "WARNING"));
                            }
                        }
                    }

                } catch (Exception e) {
                    String msg = "Row " + rowNum + " [" + type + "]: " + e.getMessage();
                    log.warn("[AUDIT-CSV] {}", msg, e);
                    importLog.add(entry(msg, "ERROR"));
                    failureCount++;
                }
            }

            if (templateId == null && successCount == 0)
                return result.fatalError(true)
                        .summary("No valid rows processed")
                        .log(importLog).build();

            String summary = String.format(
                    "Audit library import complete | templateId=%s | %d succeeded | %d failed | %d rows",
                    templateId != null ? templateId : "n/a",
                    successCount, failureCount, totalRows);
            log.info("[AUDIT-CSV] {}", summary);

            return result.fatalError(false).summary(summary)
                    .totalRows(totalRows).successCount(successCount).failureCount(failureCount)
                    .log(importLog)
                    .createdEntityId(templateId).createdEntityType("templateId")
                    .build();

        } catch (Exception e) {
            log.error("[AUDIT-CSV] Fatal: {}", e.getMessage(), e);
            return result.fatalError(true)
                    .summary("Failed to read file: " + e.getMessage())
                    .log(importLog).build();
        }
    }

    // ── Existing upsert helpers (unchanged) ───────────────────────────────────

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditTemplate upsertTemplate(String[] cols, Map<String, Integer> h,
                                        Long tenantId, Long createdBy) {
        String name        = col(cols, h, "name");
        String description = col(cols, h, "description");
        String frameworkRef= col(cols, h, "framework_ref");
        String typeStr     = col(cols, h, "audit_type");
        AuditTemplate.AuditType auditType = typeStr.isBlank()
                ? AuditTemplate.AuditType.INTERNAL
                : AuditTemplate.AuditType.valueOf(typeStr.toUpperCase());
        return templateRepository.findAll().stream()
                .filter(t -> name.equals(t.getName()) && Objects.equals(t.getTenantId(), tenantId))
                .findFirst()
                .map(t -> { if (!description.isBlank()) t.setDescription(description);
                    if (!frameworkRef.isBlank()) t.setFrameworkRef(frameworkRef);
                    t.setAuditType(auditType); t.setTemplateName(name);
                    return templateRepository.save(t); })
                .orElseGet(() -> templateRepository.save(AuditTemplate.builder()
                        .name(name).templateName(name)
                        .description(description.isBlank() ? null : description)
                        .frameworkRef(frameworkRef.isBlank() ? null : frameworkRef)
                        .auditType(auditType).status("DRAFT").version(1)
                        .tenantId(tenantId).createdBy(createdBy).build()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditSection upsertSection(String[] cols, Map<String, Integer> h,
                                      Long tenantId, Long parentId, int orderNo, Long createdBy) {
        String name        = col(cols, h, "name");
        String description = col(cols, h, "description");
        String sectionCode = col(cols, h, "section_code");
        String frameworkRef= col(cols, h, "framework_ref");
        Optional<AuditSection> existing = !sectionCode.isBlank()
                ? sectionRepository.findBySectionCodeAndFrameworkRef(sectionCode,
                frameworkRef.isBlank() ? null : frameworkRef)
                : sectionRepository.findAll().stream()
                  .filter(s -> name.equals(s.getName())
                               && Objects.equals(s.getTenantId(), tenantId)
                               && Objects.equals(s.getParentId(), parentId))
                  .findFirst();
        return existing.map(s -> { if (!description.isBlank()) s.setDescription(description);
                    if (!sectionCode.isBlank())  s.setSectionCode(sectionCode);
                    if (!frameworkRef.isBlank()) s.setFrameworkRef(frameworkRef);
                    return sectionRepository.save(s); })
                .orElseGet(() -> sectionService.createSection(name,
                        description.isBlank() ? null : description,
                        sectionCode.isBlank()  ? null : sectionCode,
                        frameworkRef.isBlank() ? null : frameworkRef,
                        orderNo, parentId, tenantId, createdBy));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditControl upsertControl(String[] cols, Map<String, Integer> h,
                                      Long tenantId, Long createdBy) {
        String name        = col(cols, h, "name");
        String description = col(cols, h, "description");
        String controlCode = col(cols, h, "control_code");
        String frameworkRef= col(cols, h, "framework_ref");
        String testTypeStr = col(cols, h, "test_type");
        String controlTag  = col(cols, h, "control_tag");
        AuditControl.TestType testType = testTypeStr.isBlank()
                ? AuditControl.TestType.DOCUMENT_REVIEW
                : AuditControl.TestType.valueOf(testTypeStr.toUpperCase());
        Optional<AuditControl> existing = controlCode.isBlank()
                ? controlRepository.findAll().stream()
                  .filter(c -> name.equals(c.getName()) && Objects.equals(c.getTenantId(), tenantId))
                  .findFirst()
                : controlRepository.findAll().stream()
                  .filter(c -> controlCode.equals(c.getControlCode())
                               && Objects.equals(c.getTenantId(), tenantId))
                  .findFirst();
        return existing.map(c -> { if (!name.isBlank())         c.setName(name);
                    if (!description.isBlank())  c.setDescription(description);
                    if (!controlCode.isBlank())  c.setControlCode(controlCode);
                    if (!frameworkRef.isBlank()) c.setFrameworkRef(frameworkRef);
                    if (!controlTag.isBlank())   c.setControlTag(controlTag.toUpperCase().trim());
                    c.setTestType(testType);
                    return controlRepository.save(c); })
                .orElseGet(() -> controlRepository.save(AuditControl.builder()
                        .name(name)
                        .description(description.isBlank() ? null : description)
                        .controlCode(controlCode.isBlank()  ? null : controlCode)
                        .frameworkRef(frameworkRef.isBlank() ? null : frameworkRef)
                        .controlTag(controlTag.isBlank()     ? null : controlTag.toUpperCase().trim())
                        .testType(testType)
                        .tenantId(tenantId).createdBy(createdBy).build()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void upsertTemplateSectionMapping(Long templateId, Long sectionId, int orderNo) {
        templateSectionMappingRepository.findByTemplateIdAndSectionId(templateId, sectionId)
                .ifPresentOrElse(
                        m -> { m.setOrderNo(orderNo); templateSectionMappingRepository.save(m); },
                        () -> templateSectionMappingRepository.save(
                                AuditTemplateSectionMapping.builder()
                                        .templateId(templateId).sectionId(sectionId)
                                        .orderNo(orderNo).build()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void upsertSectionControlMapping(Long sectionId, Long controlId,
                                            int orderNo, double weight, boolean mandatory) {
        sectionControlMappingRepository.findBySectionIdAndControlId(sectionId, controlId)
                .ifPresentOrElse(
                        m -> { m.setOrderNo(orderNo); m.setWeight(weight); m.setMandatory(mandatory);
                            sectionControlMappingRepository.save(m); },
                        () -> sectionControlMappingRepository.save(
                                AuditSectionControlMapping.builder()
                                        .sectionId(sectionId).controlId(controlId)
                                        .orderNo(orderNo).weight(weight).isMandatory(mandatory)
                                        .build()));
    }

    // ── NEW: TEST upsert ──────────────────────────────────────────────────────

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditTest upsertTest(String[] cols, Map<String, Integer> h,
                                Long tenantId, Long createdBy) {
        String name            = col(cols, h, "name");
        String testRef         = col(cols, h, "test_ref");
        String description     = col(cols, h, "description");
        String frameworkRef    = col(cols, h, "framework_ref");
        String controlTag      = col(cols, h, "control_tag");
        String automationKey   = col(cols, h, "automation_key");
        String testProcedure   = col(cols, h, "test_procedure");
        String evidenceGuide   = col(cols, h, "evidence_guidance");
        String autoTypeStr     = col(cols, h, "automation_type");
        String freqStr         = col(cols, h, "frequency");

        AuditTest.AutomationType autoType = autoTypeStr.isBlank()
                ? AuditTest.AutomationType.MANUAL
                : parseAutoType(autoTypeStr);
        AuditTest.Frequency freq = freqStr.isBlank()
                ? AuditTest.Frequency.ANNUAL
                : AuditTest.Frequency.valueOf(freqStr.toUpperCase());

        // Find existing by testRef (preferred) or name
        Optional<AuditTest> existing = !testRef.isBlank()
                ? testRepository.findAll().stream()
                  .filter(t -> testRef.equals(t.getTestRef())
                               && Objects.equals(t.getTenantId(), tenantId))
                  .findFirst()
                : testRepository.findByNameAndTenantId(name, tenantId);

        return existing.map(t -> {
            if (!name.isBlank())          t.setName(name);
            if (!description.isBlank())   t.setDescription(description);
            if (!frameworkRef.isBlank())  t.setFrameworkRef(frameworkRef);
            if (!controlTag.isBlank())    t.setControlTag(controlTag.toUpperCase().trim());
            if (!automationKey.isBlank()) t.setAutomationKey(automationKey);
            if (!testProcedure.isBlank()) t.setTestProcedure(testProcedure);
            if (!evidenceGuide.isBlank()) t.setEvidenceGuidance(evidenceGuide);
            t.setAutomationType(autoType);
            t.setFrequency(freq);
            return testRepository.save(t);
        }).orElseGet(() -> {
            String ref = testRef.isBlank() ? generateTestRef(tenantId) : testRef;
            return testRepository.save(AuditTest.builder()
                    .name(name)
                    .testRef(ref)
                    .description(description.isBlank()   ? null : description)
                    .frameworkRef(frameworkRef.isBlank() ? null : frameworkRef)
                    .controlTag(controlTag.isBlank()     ? null : controlTag.toUpperCase().trim())
                    .automationKey(automationKey.isBlank() ? null : automationKey)
                    .testProcedure(testProcedure.isBlank() ? null : testProcedure)
                    .evidenceGuidance(evidenceGuide.isBlank() ? null : evidenceGuide)
                    .automationType(autoType)
                    .frequency(freq)
                    .tenantId(tenantId).createdBy(createdBy)
                    .build());
        });
    }
    // ── NEW: POLICY upsert ────────────────────────────────────────────────────

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditPolicy upsertPolicy(String[] cols, Map<String, Integer> h,
                                    Long tenantId, Long createdBy) {
        String nameRaw         = col(cols, h, "name");
        String description     = col(cols, h, "description");
        // Fallback: if 'name' col is blank the policy title is in 'description' col
        String title           = nameRaw.isBlank() ? description : nameRaw;
        String policyRef       = col(cols, h, "policy_ref");
        String frameworkRefs   = col(cols, h, "framework_refs");
        String controlTags     = col(cols, h, "control_tags");
        String contentTypeStr  = col(cols, h, "content_type");
        String ownerTeam       = col(cols, h, "owner_team");
        String reviewFreqStr   = col(cols, h, "review_frequency_months");

        AuditPolicy.ContentType contentType = contentTypeStr.isBlank()
                ? AuditPolicy.ContentType.RICH_TEXT
                : AuditPolicy.ContentType.valueOf(contentTypeStr.toUpperCase());
        Integer reviewFreq = reviewFreqStr.isBlank() ? 12 : parseInt(reviewFreqStr, 12);

        // Use global-aware lookup: findByPolicyRefForTenant handles tenantId=NULL (global) records
        Optional<AuditPolicy> existing = !policyRef.isBlank()
                ? policyRepository.findByPolicyRefForTenant(policyRef, tenantId).stream().findFirst()
                : policyRepository.findByTenantIdOrderByTitleAsc(tenantId).stream()
                  .filter(p -> title.equals(p.getTitle()))
                  .findFirst();

        return existing.map(p -> {
            if (!title.isBlank())         p.setTitle(title);
            if (!description.isBlank())   p.setDescription(description);
            if (!frameworkRefs.isBlank()) p.setFrameworkRefs(frameworkRefs);
            if (!controlTags.isBlank())   p.setControlTags(controlTags);
            if (!ownerTeam.isBlank())     p.setOwnerTeam(ownerTeam);
            p.setContentType(contentType);
            p.setReviewFrequencyMonths(reviewFreq);
            if (p.getStatus() == null)    p.setStatus(AuditPolicy.PolicyStatus.DRAFT);
            return policyRepository.save(p);
        }).orElseGet(() -> {
            String ref = policyRef.isBlank() ? generatePolicyRef(tenantId) : policyRef;
            return policyRepository.save(AuditPolicy.builder()
                    .title(title)
                    .policyRef(ref)
                    .description(description.isBlank()   ? null : description)
                    .frameworkRefs(frameworkRefs.isBlank() ? null : frameworkRefs)
                    .controlTags(controlTags.isBlank()   ? null : controlTags)
                    .ownerTeam(ownerTeam.isBlank()       ? null : ownerTeam)
                    .contentType(contentType)
                    .reviewFrequencyMonths(reviewFreq)
                    .status(AuditPolicy.PolicyStatus.DRAFT)
                    .version(1)
                    .tenantId(tenantId).createdBy(createdBy)
                    .build());
        });
    }
    // ── NEW: CONTROL_TEST_MAPPING upsert ─────────────────────────────────────

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String upsertControlTestMapping(String controlCode, String testRef,
                                           Long tenantId, String[] cols,
                                           Map<String, Integer> h, Long createdBy) {
        AuditControl control = controlRepository.findAll().stream()
                .filter(c -> controlCode.equals(c.getControlCode())
                        && Objects.equals(c.getTenantId(), tenantId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException(
                        "Control not found: controlCode=" + controlCode));

        AuditTest test = testRepository.findAll().stream()
                .filter(t -> testRef.equals(t.getTestRef())
                        && Objects.equals(t.getTenantId(), tenantId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException(
                        "Test not found: testRef=" + testRef));

        boolean isRequired = parseBool(col(cols, h, "is_required"), true);
        int orderNo        = parseInt(col(cols, h, "order_no"),
                controlTestMappingRepository.findByControlIdOrderByOrderNoAsc(
                        control.getId()).size());
        String note        = col(cols, h, "mapping_note");

        controlTestMappingRepository.findByControlIdAndTestId(control.getId(), test.getId())
                .ifPresentOrElse(
                        m -> { m.setRequired(isRequired); m.setOrderNo(orderNo);
                            controlTestMappingRepository.save(m); },
                        () -> controlTestMappingRepository.save(
                                AuditControlTestMapping.builder()
                                        .controlId(control.getId())
                                        .testId(test.getId())
                                        .isRequired(isRequired)
                                        .orderNo(orderNo)
                                        .mappingNote(note.isBlank() ? null : note)
                                        .tenantId(tenantId)
                                        .createdBy(createdBy)
                                        .build()));

        return String.format("    Mapped: %s → %s", controlCode, testRef);
    }

    // ── NEW: POLICY_CONTROL_MAPPING upsert ────────────────────────────────────

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String upsertPolicyControlMapping(String policyRef, String controlCode,
                                             Long tenantId, String[] cols,
                                             Map<String, Integer> h, Long createdBy) {
        AuditPolicy policy = policyRepository.findByPolicyRefForTenant(policyRef, tenantId)
                .stream().findFirst()
                .orElseThrow(() -> new RuntimeException(
                        "Policy not found: policyRef=" + policyRef));

        AuditControl control = controlRepository.findAll().stream()
                .filter(c -> controlCode.equals(c.getControlCode())
                        && Objects.equals(c.getTenantId(), tenantId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException(
                        "Control not found: controlCode=" + controlCode));

        String note = col(cols, h, "mapping_note");

        policyControlMappingRepository.findByPolicyIdAndControlId(policy.getId(), control.getId())
                .ifPresentOrElse(
                        m -> policyControlMappingRepository.save(m),
                        () -> policyControlMappingRepository.save(
                                AuditPolicyControlMapping.builder()
                                        .policyId(policy.getId())
                                        .controlId(control.getId())
                                        .mappingType(AuditPolicyControlMapping.MappingType.DIRECT)
                                        .mappingNote(note.isBlank() ? null : note)
                                        .tenantId(tenantId)
                                        .createdBy(createdBy)
                                        .build()));

        return String.format("    Policy-Control: %s → %s", policyRef, controlCode);
    }

    // ── Auto-ref generators ───────────────────────────────────────────────────

    private String generateTestRef(Long tenantId) {
        long count = testRepository.findAll().stream()
                .filter(t -> Objects.equals(t.getTenantId(), tenantId)
                        || t.getTenantId() == null)
                .count() + 1;
        return String.format("AT-%04d", count);
    }

    private String generatePolicyRef(Long tenantId) {
        long count = policyRepository.findAll().stream()
                .filter(p -> Objects.equals(p.getTenantId(), tenantId)
                        || p.getTenantId() == null)
                .count() + 1;
        return String.format("POL-%04d", count);
    }

    // ── Helpers (unchanged from original) ─────────────────────────────────────

    private int countChildrenOf(Long parentId) {
        if (parentId == null) return 0;
        return sectionRepository.findByParentIdOrderByOrderNoAsc(parentId).size();
    }

    private int countControlsIn(Long sectionId) {
        return sectionControlMappingRepository.findBySectionIdOrderByOrderNoAsc(sectionId).size();
    }

    private Map<String, Integer> parseHeaders(String line) {
        String[] parts = parseCsvLine(line);
        Map<String, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < parts.length; i++)
            map.put(parts[i].trim().toLowerCase().replace(" ", "_"), i);
        return map;
    }

    private String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder current = new StringBuilder();
        for (char c : line.toCharArray()) {
            if (c == '"')               inQuotes = !inQuotes;
            else if (c == ',' && !inQuotes) {
                fields.add(current.toString().trim()); current.setLength(0);
            } else                      current.append(c);
        }
        fields.add(current.toString().trim());
        return fields.toArray(new String[0]);
    }

    private String  col(String[] cols, Map<String, Integer> h, String key) {
        Integer idx = h.get(key);
        if (idx == null || idx >= cols.length) return "";
        String v = cols[idx].trim();
        // Treat Python None / SQL NULL strings as empty — these appear in exported CSVs
        if ("None".equals(v) || "NULL".equals(v) || "null".equals(v) || "N/A".equals(v)) return "";
        return v;
    }
    private int     parseInt(String v, int d)      { try { return Integer.parseInt(v);   } catch (Exception e) { return d; } }

    /**
     * Parses automation_type from CSV.
     * Handles both clean enum tokens ("UPLOAD", "MANUAL") and
     * full-sentence values from audit CSVs where the type is the first word:
     *   "UPLOAD IDP MFA ENFORCEMENT SCREENSHOT SHOWING ALL USERS REQUIRED TO USE MFA"
     *   → first word "UPLOAD" is used as the enum key.
     * Falls back to MANUAL if the token doesn't match any enum constant.
     */
    private AuditTest.AutomationType parseAutoType(String raw) {
        if (raw == null || raw.isBlank()) return AuditTest.AutomationType.MANUAL;
        // Take the first word only (handles long evidence-description style values)
        String token = raw.trim().split("\\s+")[0].toUpperCase();
        try {
            return AuditTest.AutomationType.valueOf(token);
        } catch (IllegalArgumentException e) {
            log.warn("[AUDIT-CSV] Unknown automation_type token '{}' (from '{}') — defaulting to MANUAL",
                    token, raw.length() > 60 ? raw.substring(0, 60) + "…" : raw);
            return AuditTest.AutomationType.MANUAL;
        }
    }
    private double  parseDouble(String v, double d) { try { return Double.parseDouble(v); } catch (Exception e) { return d; } }
    private boolean parseBool(String v, boolean d)  {
        if (v == null || v.isBlank()) return d;
        return "true".equalsIgnoreCase(v) || "1".equals(v) || "yes".equalsIgnoreCase(v);
    }
    private CsvImportResult.ImportLogEntry entry(String message, String status) {
        return CsvImportResult.ImportLogEntry.builder().message(message).status(status).build();
    }
}