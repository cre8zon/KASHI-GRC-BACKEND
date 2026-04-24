package com.kashi.grc.common.service;

import com.kashi.grc.assessment.domain.*;
import com.kashi.grc.assessment.repository.*;
import com.kashi.grc.common.dto.CsvImportResult;
import com.kashi.grc.workflow.domain.*;
import com.kashi.grc.workflow.enums.*;
import com.kashi.grc.workflow.repository.*;
import com.kashi.grc.workflow.domain.WorkflowStepSection;
import com.kashi.grc.usermanagement.domain.Role;
import com.kashi.grc.usermanagement.repository.RoleRepository;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Backend CSV import service using OpenCSV.
 *
 * WHY BACKEND:
 *   CSV parsing belongs on the server for security, consistency, auditability,
 *   and reusability. The frontend uploads a File and receives a structured result.
 *   No CSV parsing happens in JavaScript.
 *
 * REUSABILITY:
 *   Add new public methods (e.g. importVendors, importUsers) following the same pattern.
 *   Each method is fully self-contained with its own validation and log output.
 *
 * ─── METHOD 1: importAssessmentTemplate ───────────────────────────────────────
 *   Hierarchical format — one CSV builds an entire template tree.
 *   Uses find-or-create. Re-importing the same CSV is fully idempotent.
 *
 *   CSV FORMAT (col 7 = question_tag, optional — backward-compatible):
 *     type, name_or_text, response_type, weight, is_mandatory, option_value, score, question_tag
 *     TEMPLATE, Assessment Type - I, , , , , ,
 *     SECTION,  Information Security Governance, , , , , ,
 *     QUESTION, Do you have a SOC 2 report?, SINGLE_CHOICE, 10, true, , , CERTIFICATION
 *     OPTION,   , , , , Yes — Fully implemented, 10,
 *     OPTION,   , , , , No, 0,
 *
 *   question_tag (col 7): KashiGuard category label. Blank = untagged (guard skips it).
 *   Re-importing with a new tag updates the library question tag.
 *   Does NOT retroactively update running assessment instances — use the backfill SQL.
 *
 * ─── METHOD 2: importLibraryQuestions ─────────────────────────────────────────
 *   Flat format. Auto-detects two layouts:
 *
 *   Format A (with tag):   questionText, responseType, questionTag, optValue1, score1, ...
 *   Format B (no tag):     questionText, responseType, optValue1, score1, ...
 *
 *   Detection: col2 is non-numeric and non-blank → Format A (tag). Numeric → Format B (score).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CsvImportService {

    // ── Valid response types ───────────────────────────────────────────────────
    private static final Set<String> VALID_RESPONSE_TYPES =
            Set.of("SINGLE_CHOICE", "MULTI_CHOICE", "TEXT", "FILE_UPLOAD");

    // ── Column indices for hierarchical (template) format ─────────────────────
    private static final int COL_TYPE          = 0;
    private static final int COL_NAME_OR_TEXT  = 1;
    private static final int COL_RESPONSE_TYPE = 2;
    private static final int COL_WEIGHT        = 3;
    private static final int COL_IS_MANDATORY  = 4;
    private static final int COL_OPTION_VALUE  = 5;
    private static final int COL_SCORE         = 6;
    private static final int COL_QUESTION_TAG  = 7;   // NEW — optional, guard rule category
    private static final int MIN_COLS          = 7;   // 7 = backward-compatible; col 7 is optional

    // ── Repositories ──────────────────────────────────────────────────────────
    private final AssessmentTemplateRepository       templateRepository;
    private final AssessmentSectionRepository        sectionRepository;
    private final AssessmentQuestionRepository       questionRepository;
    private final AssessmentQuestionOptionRepository optionRepository;
    private final TemplateSectionMappingRepository   templateSectionMappingRepository;
    private final SectionQuestionMappingRepository   sectionQuestionMappingRepository;
    private final QuestionOptionMappingRepository    questionOptionMappingRepository;

    // ── Workflow repositories ─────────────────────────────────────────────────
    private final WorkflowRepository                 workflowRepository;
    private final WorkflowStepRepository             workflowStepRepository;
    private final WorkflowStepRoleRepository         workflowStepRoleRepository;
    private final WorkflowStepAssignerRoleRepository workflowStepAssignerRoleRepository;
    private final WorkflowStepObserverRoleRepository workflowStepObserverRoleRepository;
    private final WorkflowStepSectionRepository      workflowStepSectionRepository;
    private final RoleRepository                     roleRepository;

    // ══════════════════════════════════════════════════════════════════════════
    // METHOD 1 — importAssessmentTemplate
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Parses and imports a hierarchical assessment template CSV.
     *
     * Find-or-create strategy:
     *   - Looks up each entity by its natural key before creating
     *   - Skips mapping creation if the mapping already exists
     *   - Re-importing the same CSV is fully idempotent
     *
     * @param file     Uploaded .csv file
     * @param tenantId Caller's tenant ID — null means Platform Admin (global entities)
     * @return         Structured result with per-row log, counts, and created template ID
     */
    @Transactional
    public CsvImportResult importAssessmentTemplate(MultipartFile file, Long tenantId) {
        log.info("[CSV-IMPORT] Starting template import | tenantId={} | file={} | size={}",
                tenantId, file.getOriginalFilename(), file.getSize());

        CsvImportResult.CsvImportResultBuilder result = CsvImportResult.builder()
                .totalRows(0).successCount(0).failureCount(0);

        // ── Validate file ──────────────────────────────────────────────────────
        if (file == null || file.isEmpty()) {
            log.warn("[CSV-IMPORT] Rejected: empty or null file");
            return result.fatalError(true).summary("No file uploaded").build();
        }
        String filename = Objects.requireNonNullElse(file.getOriginalFilename(), "");
        if (!filename.toLowerCase().endsWith(".csv")) {
            log.warn("[CSV-IMPORT] Rejected: invalid file type — {}", filename);
            return result.fatalError(true).summary("Only .csv files are accepted").build();
        }

        // ── Parse CSV rows ─────────────────────────────────────────────────────
        List<String[]> rows;
        try {
            rows = readCsvRows(file);
            log.info("[CSV-IMPORT] Parsed {} data rows from file", rows.size());
        } catch (Exception e) {
            log.error("[CSV-IMPORT] CSV parse failure: {}", e.getMessage(), e);
            return result.fatalError(true)
                    .summary("Failed to parse CSV: " + e.getMessage()).build();
        }

        if (rows.isEmpty()) {
            return result.fatalError(true).summary("CSV file is empty (no data rows)").build();
        }

        // ── Process rows ───────────────────────────────────────────────────────
        List<CsvImportResult.ImportLogEntry> importLog = new ArrayList<>();
        Long   templateId        = null;
        Long   currentSectionId  = null;
        Long   currentQuestionId = null;
        int    sectionOrder      = 0;
        int    questionOrder     = 0;
        int    optionOrder       = 0;
        int    successCount      = 0;
        int    failureCount      = 0;
        int    totalRows         = rows.size();

        for (int i = 0; i < rows.size(); i++) {
            String[] row    = rows.get(i);
            int      lineNo = i + 2; // +2: row 1 is header

            if (row.length < MIN_COLS) {
                String msg = String.format("Row %d: expected %d columns, got %d — skipped",
                        lineNo, MIN_COLS, row.length);
                log.warn("[CSV-IMPORT] {}", msg);
                importLog.add(entry(msg, "ERROR"));
                failureCount++;
                continue;
            }

            String type = trim(row[COL_TYPE]).toUpperCase();
            log.debug("[CSV-IMPORT] Row {}: type={}", lineNo, type);

            switch (type) {

                // ── TEMPLATE ─────────────────────────────────────────────────
                case "TEMPLATE" -> {
                    String name = trim(row[COL_NAME_OR_TEXT]);
                    if (name.isEmpty()) {
                        String msg = "Row " + lineNo + ": TEMPLATE row has no name — skipped";
                        log.warn("[CSV-IMPORT] {}", msg);
                        importLog.add(entry(msg, "ERROR")); failureCount++; continue;
                    }
                    if (templateId != null) {
                        String msg = "Row " + lineNo + ": Only one TEMPLATE row allowed — extra row skipped";
                        log.warn("[CSV-IMPORT] {}", msg);
                        importLog.add(entry(msg, "WARNING")); continue;
                    }

                    // Find-or-create — flag array pattern (same as SECTION/QUESTION below)
                    boolean[] created = {false};
                    AssessmentTemplate template = templateRepository
                            .findByNameAndTenantIdIsNull(name)
                            .orElseGet(() -> {
                                created[0] = true;
                                return templateRepository.save(AssessmentTemplate.builder()
                                        .tenantId(tenantId).name(name).version(1).status("DRAFT")
                                        .build());
                            });
                    templateId   = template.getId();
                    sectionOrder = 0;

                    String msg = String.format("%s template: \"%s\" (ID: %d)",
                            created[0] ? "Created" : "Reusing", name, templateId);
                    log.info("[CSV-IMPORT] {}", msg);
                    importLog.add(entry(msg, "INFO")); successCount++;
                }

                // ── SECTION ──────────────────────────────────────────────────
                case "SECTION" -> {
                    if (templateId == null) {
                        String msg = "Row " + lineNo + ": SECTION before TEMPLATE row — skipped";
                        log.warn("[CSV-IMPORT] {}", msg);
                        importLog.add(entry(msg, "ERROR")); failureCount++; continue;
                    }
                    String name = trim(row[COL_NAME_OR_TEXT]);
                    if (name.isEmpty()) {
                        String msg = "Row " + lineNo + ": SECTION has no name — skipped";
                        log.warn("[CSV-IMPORT] {}", msg);
                        importLog.add(entry(msg, "ERROR")); failureCount++; continue;
                    }

                    boolean[] created = {false};
                    AssessmentSection section = sectionRepository
                            .findByNameAndTenantIdIsNull(name)
                            .orElseGet(() -> {
                                created[0] = true;
                                return sectionRepository.save(AssessmentSection.builder()
                                        .tenantId(tenantId).name(name).build());
                            });
                    currentSectionId  = section.getId();
                    currentQuestionId = null;
                    sectionOrder++;
                    questionOrder = 0;

                    // Idempotent mapping — skip if already mapped
                    if (!templateSectionMappingRepository
                            .existsByTemplateIdAndSectionId(templateId, currentSectionId)) {
                        templateSectionMappingRepository.save(TemplateSectionMapping.builder()
                                .templateId(templateId).sectionId(currentSectionId)
                                .orderNo(sectionOrder).build());
                        log.debug("[CSV-IMPORT] Mapped section {} → template {}", currentSectionId, templateId);
                    } else {
                        log.debug("[CSV-IMPORT] Section {} already mapped to template {} — skipping mapping",
                                currentSectionId, templateId);
                    }

                    String msg = String.format("  Section %d: \"%s\" (ID: %d) [%s]",
                            sectionOrder, name, currentSectionId, created[0] ? "created" : "reused");
                    log.info("[CSV-IMPORT] {}", msg);
                    importLog.add(entry(msg, "INFO")); successCount++;
                }

                // ── QUESTION ─────────────────────────────────────────────────
                case "QUESTION" -> {
                    if (currentSectionId == null) {
                        String msg = "Row " + lineNo + ": QUESTION before any SECTION — skipped";
                        log.warn("[CSV-IMPORT] {}", msg);
                        importLog.add(entry(msg, "ERROR")); failureCount++;
                        currentQuestionId = null; continue;
                    }
                    String text         = trim(row[COL_NAME_OR_TEXT]);
                    String responseType = trim(row[COL_RESPONSE_TYPE]).toUpperCase();
                    if (text.isEmpty()) {
                        String msg = "Row " + lineNo + ": QUESTION has no text — skipped";
                        log.warn("[CSV-IMPORT] {}", msg);
                        importLog.add(entry(msg, "ERROR")); failureCount++;
                        currentQuestionId = null; continue;
                    }
                    if (!VALID_RESPONSE_TYPES.contains(responseType)) {
                        String msg = String.format(
                                "Row %d: invalid response_type \"%s\" — valid: %s",
                                lineNo, responseType, VALID_RESPONSE_TYPES);
                        log.warn("[CSV-IMPORT] {}", msg);
                        importLog.add(entry(msg, "ERROR")); failureCount++;
                        currentQuestionId = null; continue;
                    }

                    Double  weight    = parseDouble(row[COL_WEIGHT]);
                    boolean mandatory = "true".equalsIgnoreCase(trim(row[COL_IS_MANDATORY]));

                    // question_tag — optional column 7 (backward-compatible: blank = untagged)
                    // If the CSV was exported without the column, row.length < 8 → null tag.
                    // Null tag means GuardEvaluator silently skips this question — no rules fire.
                    String questionTag = (row.length > COL_QUESTION_TAG)
                            ? trim(row[COL_QUESTION_TAG]).isEmpty() ? null : trim(row[COL_QUESTION_TAG])
                            : null;

                    // weight/mandatory are NOT part of the question identity — they live on
                    // SectionQuestionMapping. Same question can have different weights per section.
                    boolean[] created = {false};
                    AssessmentQuestion question = questionRepository
                            .findByQuestionTextAndResponseTypeAndTenantIdIsNull(text, responseType)
                            .orElseGet(() -> {
                                created[0] = true;
                                return questionRepository.save(AssessmentQuestion.builder()
                                        .tenantId(tenantId).questionText(text)
                                        .responseType(responseType)
                                        .questionTag(questionTag)
                                        .build());
                            });

                    // If the question already existed, update its tag if the CSV provides one.
                    // This allows re-importing to fix missing tags on existing questions.
                    // Only updates if CSV explicitly provides a tag (non-blank col 7).
                    // Never clears an existing tag with a blank import — use the library UI for that.
                    if (!created[0] && questionTag != null
                            && !questionTag.equals(question.getQuestionTag())) {
                        question.setQuestionTag(questionTag);
                        questionRepository.save(question);
                        log.info("[CSV-IMPORT] Updated questionTag → '{}' for question id={}",
                                questionTag, question.getId());
                    }

                    currentQuestionId = question.getId();
                    optionOrder = 0;
                    questionOrder++;

                    // Idempotent mapping — always write if not yet mapped
                    if (!sectionQuestionMappingRepository
                            .existsBySectionIdAndQuestionId(currentSectionId, currentQuestionId)) {
                        sectionQuestionMappingRepository.save(SectionQuestionMapping.builder()
                                .sectionId(currentSectionId).questionId(currentQuestionId)
                                .orderNo(questionOrder).weight(weight).isMandatory(mandatory)
                                .build());
                        log.debug("[CSV-IMPORT] Mapped question {} → section {}", currentQuestionId, currentSectionId);
                    } else {
                        log.debug("[CSV-IMPORT] Question {} already mapped to section {} — skipping mapping",
                                currentQuestionId, currentSectionId);
                    }

                    String label = text.length() > 60 ? text.substring(0, 60) + "…" : text;
                    String msg = String.format(
                            "    Q%d: \"%s\" [%s] weight=%s mandatory=%s tag=%s (ID: %d) [%s]",
                            questionOrder, label, responseType,
                            weight != null ? weight : "—", mandatory,
                            questionTag != null ? questionTag : "—",
                            currentQuestionId, created[0] ? "created" : "reused");
                    log.info("[CSV-IMPORT] {}", msg);
                    importLog.add(entry(msg, "SUCCESS")); successCount++;
                }

                // ── OPTION ───────────────────────────────────────────────────
                case "OPTION" -> {
                    if (currentQuestionId == null) {
                        String msg = "Row " + lineNo + ": OPTION with no active QUESTION — skipped";
                        log.warn("[CSV-IMPORT] {}", msg);
                        importLog.add(entry(msg, "ERROR")); failureCount++; continue;
                    }
                    String optionValue = trim(row[COL_OPTION_VALUE]);
                    if (optionValue.isEmpty()) {
                        // Fallback: some CSV tools put the value in col 1
                        optionValue = trim(row[COL_NAME_OR_TEXT]);
                    }
                    if (optionValue.isEmpty()) {
                        String msg = "Row " + lineNo + ": OPTION has no value — skipped";
                        log.warn("[CSV-IMPORT] {}", msg);
                        importLog.add(entry(msg, "ERROR")); failureCount++; continue;
                    }
                    Double score = parseDouble(row[COL_SCORE]);
                    optionOrder++;

                    // Find-or-create — "Yes, score=10" is a different option from "Yes, score=0"
                    // Null score is also valid (TEXT fallback options), handled separately
                    // because SQL NULL != NULL, so score=null needs its own finder method
                    final String finalOptionValue = optionValue;
                    final Double finalScore       = score;
                    boolean[]    created          = {false};

                    AssessmentQuestionOption option = (finalScore != null
                            ? optionRepository.findByOptionValueAndScoreAndTenantIdIsNull(finalOptionValue, finalScore)
                            : optionRepository.findByOptionValueAndScoreIsNullAndTenantIdIsNull(finalOptionValue))
                            .orElseGet(() -> {
                                created[0] = true;
                                return optionRepository.save(AssessmentQuestionOption.builder()
                                        .tenantId(tenantId).optionValue(finalOptionValue)
                                        .score(finalScore).build());
                            });

                    // Idempotent mapping — skip if already linked
                    if (!questionOptionMappingRepository
                            .existsByQuestionIdAndOptionId(currentQuestionId, option.getId())) {
                        questionOptionMappingRepository.save(QuestionOptionMapping.builder()
                                .questionId(currentQuestionId).optionId(option.getId())
                                .orderNo(optionOrder).build());
                        log.debug("[CSV-IMPORT] Row {}: option \"{}\" score={} (ID: {}) [{}]",
                                lineNo, finalOptionValue, finalScore, option.getId(),
                                created[0] ? "created" : "reused");
                    } else {
                        log.debug("[CSV-IMPORT] Row {}: option {} already mapped to question {} — skipping",
                                lineNo, option.getId(), currentQuestionId);
                    }
                    successCount++;
                }

                default -> {
                    if (!type.isEmpty()) {
                        String msg = String.format("Row %d: unknown type \"%s\" — skipped", lineNo, type);
                        log.warn("[CSV-IMPORT] {}", msg);
                        importLog.add(entry(msg, "WARNING"));
                    }
                }
            }
        }

        if (templateId == null) {
            log.error("[CSV-IMPORT] No TEMPLATE row found in file");
            return result.fatalError(true)
                    .summary("CSV must contain a TEMPLATE row")
                    .log(importLog).build();
        }

        String summary = String.format(
                "Import complete: template ID=%d | %d succeeded | %d failed | %d total rows",
                templateId, successCount, failureCount, totalRows);
        log.info("[CSV-IMPORT] {}", summary);

        return result
                .fatalError(false).summary(summary)
                .totalRows(totalRows).successCount(successCount).failureCount(failureCount)
                .log(importLog)
                .createdEntityId(templateId).createdEntityType("templateId")
                .build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // METHOD 2 — importLibraryQuestions (flat format)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Parses and imports a flat questions-only CSV into the library.
     * Used by the Question Library page bulk import — not the full template import.
     *
     * CSV FORMAT:
     *   questionText, responseType, optionValue1, score1, optionValue2, score2, ...
     *   "Do you have SOC 2?", SINGLE_CHOICE, "Yes — certified", 10, "No", 0
     *   "Describe your DR process.", TEXT
     *
     * Up to 8 option pairs per row (columns 2–17).
     * TEXT and FILE_UPLOAD rows may have no options — this is valid.
     *
     * @param file     Uploaded .csv file
     * @param tenantId Caller's tenant ID — null means Platform Admin
     * @return         Structured result with per-row log and counts
     */
    @Transactional
    public CsvImportResult importLibraryQuestions(MultipartFile file, Long tenantId) {
        log.info("[CSV-LIB-IMPORT] Starting library question import | tenantId={} | file={} | size={}",
                tenantId, file.getOriginalFilename(), file.getSize());

        CsvImportResult.CsvImportResultBuilder result = CsvImportResult.builder()
                .totalRows(0).successCount(0).failureCount(0);

        if (file == null || file.isEmpty()) {
            log.warn("[CSV-LIB-IMPORT] Rejected: empty or null file");
            return result.fatalError(true).summary("No file uploaded").build();
        }
        if (!Objects.requireNonNullElse(file.getOriginalFilename(), "").toLowerCase().endsWith(".csv")) {
            log.warn("[CSV-LIB-IMPORT] Rejected: invalid file type — {}", file.getOriginalFilename());
            return result.fatalError(true).summary("Only .csv files are accepted").build();
        }

        List<String[]> rows;
        try {
            rows = readCsvRows(file);
            log.info("[CSV-LIB-IMPORT] Parsed {} data rows", rows.size());
        } catch (Exception e) {
            log.error("[CSV-LIB-IMPORT] Parse failure: {}", e.getMessage(), e);
            return result.fatalError(true)
                    .summary("Failed to parse CSV: " + e.getMessage()).build();
        }

        if (rows.isEmpty()) {
            return result.fatalError(true).summary("CSV file is empty").build();
        }

        List<CsvImportResult.ImportLogEntry> importLog = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;
        int totalRows    = rows.size();

        for (int i = 0; i < rows.size(); i++) {
            String[] row    = rows.get(i);
            int      lineNo = i + 2;

            if (row.length < 2) {
                String msg = "Row " + lineNo + ": needs at least questionText + responseType — skipped";
                log.warn("[CSV-LIB-IMPORT] {}", msg);
                importLog.add(entry(msg, "ERROR")); failureCount++; continue;
            }

            String text         = trim(row[0]);
            String responseType = trim(row[1]).toUpperCase();

            // Optional column 2: question_tag (if present, before option pairs)
            // Detect: if col 2 exists AND is not a number AND is not blank → it's a tag.
            // Option pairs start at col 2 (optionValue, score). Tag shifts them to col 3.
            // Format A (with tag):    questionText, responseType, questionTag, optValue1, score1, ...
            // Format B (without tag): questionText, responseType, optValue1, score1, ...
            // Detection: if col 2 parses as a double → it's a score, no tag present (Format B).
            //            if col 2 is blank or doesn't parse as double → it's a tag (or empty, Format A).
            String questionTag   = null;
            int    optionColStart = 2;  // default: options start at col 2 (Format B)

            if (row.length > 2) {
                String col2 = trim(row[2]);
                boolean col2IsScore = false;
                if (!col2.isEmpty()) {
                    try { Double.parseDouble(col2); col2IsScore = true; }
                    catch (NumberFormatException ignored) { /* not a number → treat as tag */ }
                }
                // If col2 is not a number and not blank → it's a questionTag (Format A)
                if (!col2IsScore && !col2.isEmpty()) {
                    questionTag    = col2;
                    optionColStart = 3;  // options start at col 3
                }
                // If col2 is blank and col3 looks like a score → still Format A with null tag
                if (!col2IsScore && col2.isEmpty() && row.length > 3) {
                    try { Double.parseDouble(trim(row[3])); optionColStart = 3; }
                    catch (NumberFormatException ignored) { /* col3 is not a score — col2 is optionValue */ }
                }
            }

            if (text.isEmpty()) {
                String msg = "Row " + lineNo + ": question text is empty — skipped";
                log.warn("[CSV-LIB-IMPORT] {}", msg);
                importLog.add(entry(msg, "ERROR")); failureCount++; continue;
            }
            if (!VALID_RESPONSE_TYPES.contains(responseType)) {
                String msg = String.format("Row %d: invalid response_type \"%s\" — valid: %s",
                        lineNo, responseType, VALID_RESPONSE_TYPES);
                log.warn("[CSV-LIB-IMPORT] {}", msg);
                importLog.add(entry(msg, "ERROR")); failureCount++; continue;
            }

            // Find-or-create question
            boolean[] questionCreated = {false};
            final String fQuestionTag = questionTag;
            AssessmentQuestion question = questionRepository
                    .findByQuestionTextAndResponseTypeAndTenantIdIsNull(text, responseType)
                    .orElseGet(() -> {
                        questionCreated[0] = true;
                        return questionRepository.save(AssessmentQuestion.builder()
                                .tenantId(tenantId).questionText(text)
                                .responseType(responseType)
                                .questionTag(fQuestionTag)
                                .build());
                    });

            // Update tag on existing question if CSV provides one and it differs
            if (!questionCreated[0] && fQuestionTag != null
                    && !fQuestionTag.equals(question.getQuestionTag())) {
                question.setQuestionTag(fQuestionTag);
                questionRepository.save(question);
            }

            log.info("[CSV-LIB-IMPORT] Row {}: question id={} type={} tag={} [{}]",
                    lineNo, question.getId(), responseType,
                    fQuestionTag != null ? fQuestionTag : "—",
                    questionCreated[0] ? "created" : "reused");

            // Parse option pairs from remaining columns
            int optionOrder = 0;
            for (int col = optionColStart; col + 1 < row.length; col += 2) {
                String optionValue = trim(row[col]);
                if (optionValue.isEmpty()) continue;

                Double  score    = parseDouble(row[col + 1]);
                optionOrder++;

                // Find-or-create option
                final String fv   = optionValue;
                final Double fs   = score;
                boolean[] optCreated = {false};

                AssessmentQuestionOption option = (fs != null
                        ? optionRepository.findByOptionValueAndScoreAndTenantIdIsNull(fv, fs)
                        : optionRepository.findByOptionValueAndScoreIsNullAndTenantIdIsNull(fv))
                        .orElseGet(() -> {
                            optCreated[0] = true;
                            return optionRepository.save(AssessmentQuestionOption.builder()
                                    .tenantId(tenantId).optionValue(fv).score(fs).build());
                        });

                // Idempotent mapping
                if (!questionOptionMappingRepository
                        .existsByQuestionIdAndOptionId(question.getId(), option.getId())) {
                    questionOptionMappingRepository.save(QuestionOptionMapping.builder()
                            .questionId(question.getId()).optionId(option.getId())
                            .orderNo(optionOrder).build());
                }
                log.debug("[CSV-LIB-IMPORT] Row {}: option \"{}\" score={} id={} [{}]",
                        lineNo, fv, fs, option.getId(), optCreated[0] ? "created" : "reused");
            }

            String label = text.length() > 60 ? text.substring(0, 60) + "…" : text;
            String msg   = String.format("Q%d: \"%s\" [%s] — %d options [%s]",
                    i + 1, label, responseType, optionOrder,
                    questionCreated[0] ? "created" : "reused");
            importLog.add(entry(msg, "SUCCESS")); successCount++;
        }

        String summary = String.format(
                "Library import complete: %d questions imported | %d failed | %d total rows",
                successCount, failureCount, totalRows);
        log.info("[CSV-LIB-IMPORT] {}", summary);

        return result
                .fatalError(false).summary(summary)
                .totalRows(totalRows).successCount(successCount).failureCount(failureCount)
                .log(importLog).build();
    }


    // ══════════════════════════════════════════════════════════════════════════
    // METHOD 3 — importWorkflowSteps
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Parses and imports workflow steps from CSV into an existing workflow blueprint.
     *
     * Auto-detects two formats from the header row:
     *
     *   DB_EXPORT  — columns exported from workflow_steps table:
     *                id, workflow_id, step_order, name, side, step_action,
     *                approval_type, min_approvals_required, sla_hours,
     *                automated_action, assigner_resolution, allow_override
     *
     *   TEMPLATE   — human-authored, role-name-friendly:
     *                order, name, side, stepAction, approvalType, slaHours,
     *                automatedAction, assignerResolution,
     *                actorRoles, assignerRoles, observerRoles
     *                (role columns: semicolon-separated role names, resolved by server)
     *
     * Idempotent:
     *   - Row has an existing step ID → UPDATE that step in place
     *   - Row has no ID / unknown ID  → INSERT new step
     *   - Role columns present        → delete + re-insert all role associations
     *   - Role columns absent         → leave existing role associations untouched
     *
     * @param file       Uploaded .csv file
     * @param workflowId Target workflow blueprint ID
     * @param tenantId   Caller's tenant (used for role name resolution)
     */
    @Transactional
    public CsvImportResult importWorkflowSteps(MultipartFile file, Long workflowId, Long tenantId) {
        log.info("[CSV-WF] Starting | workflowId={} | file={}",
                workflowId, file != null ? file.getOriginalFilename() : "null");

        CsvImportResult.CsvImportResultBuilder result = CsvImportResult.builder()
                .totalRows(0).successCount(0).failureCount(0);

        if (file == null || file.isEmpty())
            return result.fatalError(true).summary("No file uploaded").build();
        if (!Objects.requireNonNullElse(file.getOriginalFilename(), "").toLowerCase().endsWith(".csv"))
            return result.fatalError(true).summary("Only .csv files are accepted").build();

        List<String[]> allRows;
        try { allRows = readAllCsvRows(file); }
        catch (Exception e) {
            return result.fatalError(true)
                    .summary("Failed to parse CSV: " + e.getMessage()).build();
        }
        if (allRows.size() < 2)
            return result.fatalError(true)
                    .summary("CSV must have a header row and at least one data row").build();

        // ── Detect format from header ─────────────────────────────────────────
        String[] header = allRows.get(0);
        Map<String, Integer> ci = new HashMap<>();
        for (int i = 0; i < header.length; i++)
            ci.put(wfNorm(header[i]), i);

        boolean isDbExport = ci.containsKey("workflow_id") || ci.containsKey("step_order");
        log.info("[CSV-WF] Format: {} | cols={}", isDbExport ? "DB_EXPORT" : "TEMPLATE", ci.keySet());

        // Column index lookups
        int ciId    = ci.getOrDefault("id",    -1);
        int ciOrder = isDbExport ? ci.getOrDefault("step_order", -1)          : ci.getOrDefault("order", -1);
        int ciName  = ci.getOrDefault("name",  -1);
        int ciSide  = ci.getOrDefault("side",  -1);
        int ciAct   = isDbExport ? ci.getOrDefault("step_action",   -1)       : wfFirstOf(ci, "stepaction","action");
        int ciAppr  = isDbExport ? ci.getOrDefault("approval_type", -1)       : wfFirstOf(ci, "approvaltype","approval_type");
        int ciMinA  = isDbExport ? ci.getOrDefault("min_approvals_required",-1) : -1;
        int ciSla   = isDbExport ? ci.getOrDefault("sla_hours",    -1)        : wfFirstOf(ci, "slahours","sla_hours");
        int ciAuto  = isDbExport ? ci.getOrDefault("automated_action", -1)    : wfFirstOf(ci, "automatedaction","automated_action");
        int ciRes   = isDbExport ? ci.getOrDefault("assigner_resolution", -1) : wfFirstOf(ci, "assignerresolution","assigner_resolution");
        int ciAllow = isDbExport ? ci.getOrDefault("allow_override", -1)      : -1;
        int ciARol  = wfFirstOf(ci, "actorroles","actor_roles","roles");
        int ciAssR  = wfFirstOf(ci, "assignerroles","assigner_roles");
        int ciObsR  = wfFirstOf(ci, "observerroles","observer_roles");
        int ciNavK  = wfFirstOf(ci, "navkey","nav_key");
        int ciAssNavK = wfFirstOf(ci, "assignernavkey","assigner_nav_key");
        // Sections column — optional, semicolon-separated section definitions
        // Format per section: sectionKey|label|completionEvent|required(true/false)|requiresAssignment(false)|tracksItems(false)
        // Example: "ANSWER_QUESTIONS|Answer questions|ASSESSMENT_SUBMITTED|true|false|false"
        // Multiple sections separated by §  (section sign, avoids clash with ; used in roles)
        int ciSections = wfFirstOf(ci, "sections","compound_sections");

        // Preload all roles for name→id resolution (case-insensitive, spaces→underscores).
        //
        // findAllForTenant returns: tenantId IS NULL (global roles) OR tenantId = orgTenantId
        // This gives us exactly the roles we need — global roles as base, org-specific roles
        // on top. We sort so org-specific (tenantId != null) entries overwrite global ones
        // (tenantId = null) with the same name, ensuring the org role ID wins.
        //
        // The tenantId param is now the org's tenantId (passed explicitly from the frontend),
        // not the Platform Admin's tenantId. See WorkflowController.importSteps.
        Map<String, Long> roleMap = new HashMap<>();
        roleRepository.findAllForTenant(tenantId).stream()
                // Global roles (tenantId=null) first, org-specific last → org IDs win on name clash
                .sorted(java.util.Comparator.comparing(r -> r.getTenantId() == null ? 0L : r.getTenantId()))
                .forEach(r -> roleMap.put(r.getName().toLowerCase().replace(" ", "_"), r.getId()));
        log.info("[CSV-WF] Loaded {} roles for name resolution | tenantId={}", roleMap.size(), tenantId);

        List<CsvImportResult.ImportLogEntry> importLog = new ArrayList<>();
        List<String[]> dataRows = allRows.subList(1, allRows.size());
        int ok = 0, fail = 0;

        if (isDbExport) importLog.add(entry(
                "DB export format detected. Add actorRoles/assignerRoles/observerRoles columns to import roles.",
                "INFO"));

        for (int i = 0; i < dataRows.size(); i++) {
            String[] row    = dataRows.get(i);
            int      lineNo = i + 2;

            String name = wfGet(row, ciName);
            if (name.isEmpty()) {
                importLog.add(entry("Row " + lineNo + ": name required — skipped", "ERROR")); fail++; continue;
            }

            // ── Side ──────────────────────────────────────────────────────────
            String sideRaw = wfGet(row, ciSide).toUpperCase();
            Set<String> validSides = Set.of("ORGANIZATION","VENDOR","AUDITOR","AUDITEE","SYSTEM");
            if (!sideRaw.isEmpty() && !validSides.contains(sideRaw)) {
                importLog.add(entry("Row " + lineNo + ": unknown side \"" + sideRaw + "\" → ORGANIZATION", "WARNING"));

                sideRaw = "ORGANIZATION";
            }
            if (sideRaw.isEmpty()) sideRaw = "ORGANIZATION";

            // ── StepAction ────────────────────────────────────────────────────
            StepAction stepAction = null;
            String saRaw = wfGet(row, ciAct).toUpperCase();
            if (!saRaw.isEmpty() && !saRaw.equals("NULL")) {
                try { stepAction = StepAction.valueOf(saRaw); }
                catch (IllegalArgumentException e) {
                    importLog.add(entry("Row " + lineNo + ": unknown stepAction \"" + saRaw + "\" — ignored", "WARNING"));
                }
            }

            // ── AssignerResolution ────────────────────────────────────────────
            AssignerResolution resolution = AssignerResolution.POOL;
            String resRaw = wfGet(row, ciRes).toUpperCase();
            if (!resRaw.isEmpty() && !resRaw.equals("NULL")) {
                try { resolution = AssignerResolution.valueOf(resRaw); }
                catch (IllegalArgumentException e) {
                    importLog.add(entry("Row " + lineNo + ": unknown resolution \"" + resRaw + "\" → POOL", "WARNING"));
                }
            }

            // ── ApprovalType ──────────────────────────────────────────────────
            ApprovalType approvalType = ApprovalType.ANY_ONE;
            String apRaw = wfGet(row, ciAppr).toUpperCase();
            if (!apRaw.isEmpty() && !apRaw.equals("NULL")) {
                try { approvalType = ApprovalType.valueOf(apRaw); }
                catch (IllegalArgumentException e) {
                    importLog.add(entry("Row " + lineNo + ": unknown approvalType \"" + apRaw + "\" → ANY_ONE", "WARNING"));
                }
            }

            // ── Numeric fields ────────────────────────────────────────────────
            int     stepOrder = wfInt(wfGet(row, ciOrder), i + 1);
            int     minAppr   = wfInt(wfGet(row, ciMinA), 1);
            Integer slaHours  = null;
            String  slaStr    = wfGet(row, ciSla);
            if (!slaStr.isEmpty() && !slaStr.equalsIgnoreCase("NULL")) {
                try { slaHours = Integer.parseInt(slaStr); } catch (NumberFormatException ignored) {}
            }
            String autoAct   = wfGet(row, ciAuto);
            if (autoAct.isEmpty() || autoAct.equalsIgnoreCase("NULL")) autoAct = null;
            String navKey = wfGet(row, ciNavK);
            if (navKey.isEmpty() || navKey.equalsIgnoreCase("NULL")) navKey = null;
            String assignerNavKey = wfGet(row, ciAssNavK);
            if (assignerNavKey.isEmpty() || assignerNavKey.equalsIgnoreCase("NULL")) assignerNavKey = null;
            String allowStr  = wfGet(row, ciAllow);
            boolean allowOverride = !allowStr.equals("0") && !allowStr.equalsIgnoreCase("false");

            // ── Upsert step ───────────────────────────────────────────────────
            String existIdStr = wfGet(row, ciId);
            WorkflowStep step = null;
            if (!existIdStr.isEmpty() && !existIdStr.equalsIgnoreCase("NULL")) {
                try { step = workflowStepRepository.findById(Long.parseLong(existIdStr)).orElse(null); }
                catch (NumberFormatException ignored) {}
            }
            if (step == null) {
                // Try to match by stepOrder within this workflow
                final int fOrder = stepOrder;
                step = workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc(workflowId)
                        .stream().filter(s -> s.getStepOrder() == fOrder).findFirst().orElse(null);
            }
            boolean created = (step == null);
            if (step == null) step = WorkflowStep.builder().workflowId(workflowId).build();

            step.setName(name);
            step.setStepOrder(stepOrder);
            step.setSide(sideRaw);
            step.setStepAction(stepAction);
            step.setAssignerResolution(resolution);
            step.setApprovalType(approvalType);
            step.setMinApprovalsRequired(minAppr);
            step.setSlaHours(slaHours);
            step.setAutomatedAction(autoAct);
            step.setNavKey(navKey);
            step.setAssignerNavKey(assignerNavKey);
            step.setAllowOverride(allowOverride);
            step = workflowStepRepository.save(step);
            final Long stepId = step.getId();

            // ── Role assignments — only if columns present in CSV ─────────────
            // If columns are absent (e.g. DB export without role cols), existing
            // role associations are left untouched. This prevents accidental wipes.
            String aRolesStr  = wfGet(row, ciARol);
            String asRolesStr = wfGet(row, ciAssR);
            String oRolesStr  = wfGet(row, ciObsR);

            if (ciARol >= 0) {  // column exists in this CSV
                workflowStepRoleRepository.deleteByStepId(stepId);
                wfResolveRoles(aRolesStr, roleMap, importLog, lineNo, "actorRole")
                        .forEach(rid -> workflowStepRoleRepository.save(
                                WorkflowStepRole.builder().stepId(stepId).roleId(rid).build()));
            }
            if (ciAssR >= 0) {
                workflowStepAssignerRoleRepository.deleteByStepId(stepId);
                wfResolveRoles(asRolesStr, roleMap, importLog, lineNo, "assignerRole")
                        .forEach(rid -> workflowStepAssignerRoleRepository.save(
                                WorkflowStepAssignerRole.builder().stepId(stepId).roleId(rid).build()));
            }
            if (ciObsR >= 0) {
                workflowStepObserverRoleRepository.deleteByStepId(stepId);
                wfResolveRoles(oRolesStr, roleMap, importLog, lineNo, "observerRole")
                        .forEach(rid -> workflowStepObserverRoleRepository.save(
                                WorkflowStepObserverRole.builder().stepId(stepId).roleId(rid).build()));
            }

            // ── Sections (compound task gate) ─────────────────────────────────
            // Only persist sections if the column is present in this CSV.
            // Each section definition: sectionKey|label|completionEvent|required|requiresAssignment|tracksItems
            // Multiple sections separated by §
            // If column absent → existing sections are left untouched (safe re-import).
            if (ciSections >= 0) {
                String sectionsRaw = wfGet(row, ciSections);
                workflowStepSectionRepository.deleteByStepId(stepId);
                if (!sectionsRaw.isBlank() && !sectionsRaw.equalsIgnoreCase("NULL")) {
                    String[] sectionDefs = sectionsRaw.split("§");
                    int sectionOrder = 0;
                    for (String def : sectionDefs) {
                        String[] parts = def.trim().split("\\|", -1);
                        if (parts.length < 3) continue;
                        String sKey   = parts[0].trim();
                        String sLabel = parts.length > 1 ? parts[1].trim() : sKey;
                        String sEvent = parts.length > 2 ? parts[2].trim() : sKey + "_DONE";
                        boolean sReq  = parts.length <= 3 || !"false".equalsIgnoreCase(parts[3].trim());
                        boolean sAssign = parts.length > 4 && "true".equalsIgnoreCase(parts[4].trim());
                        boolean sItems  = parts.length > 5 && "true".equalsIgnoreCase(parts[5].trim());
                        if (sKey.isEmpty() || sEvent.isEmpty()) continue;
                        sectionOrder++;
                        workflowStepSectionRepository.save(
                                WorkflowStepSection.builder()
                                        .stepId(stepId)
                                        .sectionKey(sKey)
                                        .sectionOrder(sectionOrder)
                                        .label(sLabel)
                                        .completionEvent(sEvent)
                                        .required(sReq)
                                        .requiresAssignment(sAssign)
                                        .tracksItems(sItems)
                                        .build());
                    }
                    log.info("[CSV-WF] Step {}: {} section(s) persisted", stepId, sectionOrder);
                }
            }

            importLog.add(entry(String.format(
                    "Step %d: \"%s\" [%s/%s/%s] id=%d [%s]",
                    stepOrder, name, sideRaw,
                    stepAction != null ? stepAction.name() : "—",
                    resolution.name(), stepId, created ? "created" : "updated"), "SUCCESS"));
            ok++;
        }

        // ── Delete steps whose stepOrder is NOT in the CSV ────────────────────
        // Collects all stepOrders present in the CSV, then removes any existing
        // steps in this workflow with a different order. This makes re-importing
        // a corrected CSV idempotent — old placeholder steps are cleaned up.
        Set<Integer> csvOrders = dataRows.stream()
                .map(row -> wfInt(wfGet(row, ciOrder), -1))
                .filter(o -> o > 0)
                .collect(java.util.stream.Collectors.toSet());

        List<WorkflowStep> existingSteps = workflowStepRepository
                .findByWorkflowIdOrderByStepOrderAsc(workflowId);
        int deleted = 0;
        for (WorkflowStep s : existingSteps) {
            if (!csvOrders.contains(s.getStepOrder())) {
                workflowStepRoleRepository.deleteByStepId(s.getId());
                workflowStepAssignerRoleRepository.deleteByStepId(s.getId());
                workflowStepObserverRoleRepository.deleteByStepId(s.getId());
                workflowStepRepository.delete(s);
                importLog.add(entry("Removed orphan step (order=" + s.getStepOrder()
                        + " name=\"" + s.getName() + "\") — not in CSV", "INFO"));
                deleted++;
            }
        }
        if (deleted > 0) {
            log.info("[CSV-WF] Removed {} orphan step(s) not present in CSV | workflowId={}", deleted, workflowId);
        }

        String summary = String.format(
                "Workflow step import: %d imported | %d failed | %d removed | %d total rows",
                ok, fail, deleted, dataRows.size());
        log.info("[CSV-WF] {}", summary);

        return result.fatalError(false).summary(summary)
                .totalRows(dataRows.size()).successCount(ok).failureCount(fail)
                .log(importLog).createdEntityId(workflowId).createdEntityType("workflowId").build();
    }

    // ── Workflow import helpers ────────────────────────────────────────────────

    private List<Long> wfResolveRoles(String namesStr, Map<String, Long> roleMap,
                                      List<CsvImportResult.ImportLogEntry> log2, int lineNo, String label) {
        List<Long> ids = new ArrayList<>();
        if (namesStr == null || namesStr.isBlank()) return ids;
        for (String n : namesStr.split(";")) {
            String key = n.trim().toLowerCase().replace(" ", "_");
            if (key.isEmpty()) continue;
            Long id = roleMap.get(key);
            if (id != null) ids.add(id);
            else log2.add(entry("Row " + lineNo + ": unknown " + label + " \"" + n.trim() + "\" — skipped", "WARNING"));
        }
        return ids;
    }

    private String wfGet(String[] row, int idx) {
        return (idx >= 0 && idx < row.length) ? trim(row[idx]) : "";
    }

    private int wfInt(String s, int defaultVal) {
        try { return s.isEmpty() ? defaultVal : Integer.parseInt(s); }
        catch (NumberFormatException e) { return defaultVal; }
    }

    private String wfNorm(String s) {
        return s == null ? "" : s.strip().toLowerCase().replace(" ", "_");
    }

    private int wfFirstOf(Map<String, Integer> ci, String... keys) {
        for (String k : keys) { Integer v = ci.get(k); if (v != null) return v; }
        return -1;
    }

    private List<String[]> readAllCsvRows(MultipartFile file)
            throws IOException, CsvValidationException {
        List<String[]> rows = new ArrayList<>();
        try (CSVReader reader = new CSVReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String[] row;
            while ((row = reader.readNext()) != null) {
                boolean blank = Arrays.stream(row).allMatch(s -> s == null || s.isBlank());
                if (!blank) rows.add(row);
            }
        }
        return rows;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Reads all data rows (skipping header row 1) using OpenCSV.
     * OpenCSV handles quoted fields, embedded commas, multi-line values, and UTF-8 encoding.
     */
    private List<String[]> readCsvRows(MultipartFile file)
            throws IOException, CsvValidationException {
        List<String[]> rows = new ArrayList<>();
        try (CSVReader reader = new CSVReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            reader.readNext(); // skip header
            String[] row;
            while ((row = reader.readNext()) != null) {
                boolean blank = Arrays.stream(row).allMatch(s -> s == null || s.isBlank());
                if (!blank) rows.add(row);
            }
        }
        return rows;
    }

    private CsvImportResult.ImportLogEntry entry(String message, String status) {
        return CsvImportResult.ImportLogEntry.builder().message(message).status(status).build();
    }

    private String trim(String s) {
        return s == null ? "" : s.strip();
    }

    private Double parseDouble(String s) {
        try {
            String t = trim(s);
            return t.isEmpty() ? null : Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}