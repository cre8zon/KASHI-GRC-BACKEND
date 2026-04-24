package com.kashi.grc.common.service;

import com.kashi.grc.assessment.domain.*;
import com.kashi.grc.assessment.repository.*;
import com.kashi.grc.common.dto.CsvImportResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;

/**
 * Imports a full assessment library from a multi-sheet .xlsx file.
 *
 * SHEET STRUCTURE (all sheets must be present, order does not matter):
 *   templates         → name
 *   sections          → name
 *   questions         → question_text, response_type
 *   options           → option_value, score
 *   template_sections → template_name, section_name, order_no
 *   section_questions → section_name, question_text, weight, is_mandatory, order_no
 *   question_options  → question_text, option_value, order_no
 *
 * TWO-PASS STRATEGY:
 *   Pass 1 — entity sheets (templates, sections, questions, options):
 *             find-or-create each library record, build name→ID lookup maps.
 *   Pass 2 — mapping sheets (template_sections, section_questions, question_options):
 *             wire relationships using lookup maps; skip if mapping already exists.
 *
 * WHY TWO PASSES:
 *   Mapping sheets reference entities by name. Processing them in a single pass
 *   would require the entity rows to appear earlier in the file, which is fragile.
 *   Two passes remove all ordering constraints.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class XlsxImportService {

    private static final Set<String> VALID_RESPONSE_TYPES =
            Set.of("SINGLE_CHOICE", "MULTI_CHOICE", "TEXT", "FILE_UPLOAD");

    // ── Sheet names ────────────────────────────────────────────────
    private static final String SHEET_TEMPLATES         = "templates";
    private static final String SHEET_SECTIONS          = "sections";
    private static final String SHEET_QUESTIONS         = "questions";
    private static final String SHEET_OPTIONS           = "options";
    private static final String SHEET_TEMPLATE_SECTIONS = "template_sections";
    private static final String SHEET_SECTION_QUESTIONS = "section_questions";
    private static final String SHEET_QUESTION_OPTIONS  = "question_options";

    private static final Set<String> REQUIRED_SHEETS = Set.of(
            SHEET_TEMPLATES, SHEET_SECTIONS, SHEET_QUESTIONS, SHEET_OPTIONS,
            SHEET_TEMPLATE_SECTIONS, SHEET_SECTION_QUESTIONS, SHEET_QUESTION_OPTIONS);

    // ── Repositories ───────────────────────────────────────────────
    private final AssessmentTemplateRepository       templateRepository;
    private final AssessmentSectionRepository        sectionRepository;
    private final AssessmentQuestionRepository       questionRepository;
    private final AssessmentQuestionOptionRepository optionRepository;
    private final TemplateSectionMappingRepository   templateSectionMappingRepository;
    private final SectionQuestionMappingRepository   sectionQuestionMappingRepository;
    private final QuestionOptionMappingRepository    questionOptionMappingRepository;

    // ─────────────────────────────────────────────────────────────────
    // PUBLIC ENTRY POINT
    // ─────────────────────────────────────────────────────────────────

    @Transactional
    public CsvImportResult importLibrary(MultipartFile file, Long tenantId) {
        log.info("[XLSX-IMPORT] Starting | tenantId={} | file={}", tenantId, file.getOriginalFilename());

        CsvImportResult result = CsvImportResult.builder()
                .totalRows(0).successCount(0).failureCount(0).build();

        // ── Validate file ──────────────────────────────────────────
        if (file == null || file.isEmpty()) {
            return fatal(result, "No file uploaded");
        }
        String filename = Objects.requireNonNullElse(file.getOriginalFilename(), "");
        if (!filename.toLowerCase().endsWith(".xlsx")) {
            return fatal(result, "Only .xlsx files are accepted for import");
        }

        // ── Open workbook ──────────────────────────────────────────
        Workbook workbook;
        try {
            workbook = new XSSFWorkbook(file.getInputStream());
        } catch (IOException e) {
            log.error("[XLSX-IMPORT] Failed to open workbook: {}", e.getMessage());
            return fatal(result, "Failed to open file: " + e.getMessage());
        }

        // ── Validate all required sheets exist ─────────────────────
        Set<String> missing = new HashSet<>(REQUIRED_SHEETS);
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            missing.remove(workbook.getSheetName(i).toLowerCase().trim());
        }
        if (!missing.isEmpty()) {
            return fatal(result, "Missing required sheets: " + missing +
                    ". Required: " + REQUIRED_SHEETS);
        }

        // ── Lookup maps populated during Pass 1 ───────────────────
        // key = name (lowercased for case-insensitive matching)
        Map<String, Long> templateIds = new HashMap<>();
        Map<String, Long> sectionIds  = new HashMap<>();
        // key = questionText|responseType
        Map<String, Long> questionIds = new HashMap<>();
        // key = optionValue|score (score may be "null")
        Map<String, Long> optionIds   = new HashMap<>();

        int successCount = 0;
        int failureCount = 0;
        List<CsvImportResult.ImportLogEntry> logEntries = new ArrayList<>();

        // ══════════════════════════════════════════════════════════
        // PASS 1 — ENTITY SHEETS
        // ══════════════════════════════════════════════════════════

        // ── 1a. Templates ──────────────────────────────────────────
        log.info("[XLSX-IMPORT] Pass 1: templates");
        Sheet tplSheet = getSheet(workbook, SHEET_TEMPLATES);
        for (Row row : tplSheet) {
            if (isHeader(row)) continue;
            String name = cell(row, 0);
            if (name.isEmpty()) {
                addError(logEntries, "templates", row.getRowNum(), "name is empty — skipped");
                failureCount++;
                continue;
            }
            AssessmentTemplate tpl = templateRepository
                    .findByNameAndTenantIdIsNull(name)
                    .orElseGet(() -> templateRepository.save(
                            AssessmentTemplate.builder()
                                    .tenantId(tenantId)
                                    .name(name)
                                    .version(1)
                                    .status("DRAFT")
                                    .build()));
            templateIds.put(name.toLowerCase(), tpl.getId());
            addInfo(logEntries, "Template: \"" + name + "\" (ID: " + tpl.getId() + ")");
            successCount++;
        }

        // ── 1b. Sections ───────────────────────────────────────────
        log.info("[XLSX-IMPORT] Pass 1: sections");
        Sheet secSheet = getSheet(workbook, SHEET_SECTIONS);
        for (Row row : secSheet) {
            if (isHeader(row)) continue;
            String name = cell(row, 0);
            if (name.isEmpty()) {
                addError(logEntries, "sections", row.getRowNum(), "name is empty — skipped");
                failureCount++;
                continue;
            }
            AssessmentSection sec = sectionRepository
                    .findByNameAndTenantIdIsNull(name)
                    .orElseGet(() -> sectionRepository.save(
                            AssessmentSection.builder()
                                    .tenantId(tenantId)
                                    .name(name)
                                    .build()));
            sectionIds.put(name.toLowerCase(), sec.getId());
            addInfo(logEntries, "Section: \"" + name + "\" (ID: " + sec.getId() + ")");
            successCount++;
        }

        // ── 1c. Questions ──────────────────────────────────────────
        log.info("[XLSX-IMPORT] Pass 1: questions");
        Sheet qSheet = getSheet(workbook, SHEET_QUESTIONS);
        for (Row row : qSheet) {
            if (isHeader(row)) continue;
            String text         = cell(row, 0);
            String responseType = cell(row, 1).toUpperCase();
            if (text.isEmpty()) {
                addError(logEntries, "questions", row.getRowNum(), "question_text is empty — skipped");
                failureCount++;
                continue;
            }
            if (!VALID_RESPONSE_TYPES.contains(responseType)) {
                addError(logEntries, "questions", row.getRowNum(),
                        "invalid response_type \"" + responseType + "\" — valid: " + VALID_RESPONSE_TYPES);
                failureCount++;
                continue;
            }
            AssessmentQuestion q = questionRepository
                    .findByQuestionTextAndResponseTypeAndTenantIdIsNull(text, responseType)
                    .orElseGet(() -> questionRepository.save(
                            AssessmentQuestion.builder()
                                    .tenantId(tenantId)
                                    .questionText(text)
                                    .responseType(responseType)
                                    .build()));
            questionIds.put(questionKey(text, responseType), q.getId());
            successCount++;
        }

        // ── 1d. Options ────────────────────────────────────────────
        log.info("[XLSX-IMPORT] Pass 1: options");
        Sheet optSheet = getSheet(workbook, SHEET_OPTIONS);
        for (Row row : optSheet) {
            if (isHeader(row)) continue;
            String optionValue = cell(row, 0);
            Double score       = parseDouble(cell(row, 1));
            if (optionValue.isEmpty()) {
                addError(logEntries, "options", row.getRowNum(), "option_value is empty — skipped");
                failureCount++;
                continue;
            }
            AssessmentQuestionOption opt = (score != null
                    ? optionRepository.findByOptionValueAndScoreAndTenantIdIsNull(optionValue, score)
                    : optionRepository.findByOptionValueAndScoreIsNullAndTenantIdIsNull(optionValue))
                    .orElseGet(() -> optionRepository.save(
                            AssessmentQuestionOption.builder()
                                    .tenantId(tenantId)
                                    .optionValue(optionValue)
                                    .score(score)
                                    .build()));
            optionIds.put(optionKey(optionValue, score), opt.getId());
            successCount++;
        }

        log.info("[XLSX-IMPORT] Pass 1 complete — templates={}, sections={}, questions={}, options={}",
                templateIds.size(), sectionIds.size(), questionIds.size(), optionIds.size());

        // ══════════════════════════════════════════════════════════
        // PASS 2 — MAPPING SHEETS
        // ══════════════════════════════════════════════════════════

        // ── 2a. template_sections ──────────────────────────────────
        log.info("[XLSX-IMPORT] Pass 2: template_sections");
        Sheet tsSheet = getSheet(workbook, SHEET_TEMPLATE_SECTIONS);
        for (Row row : tsSheet) {
            if (isHeader(row)) continue;
            String templateName = cell(row, 0);
            String sectionName  = cell(row, 1);
            int    orderNo      = parseIntOrDefault(cell(row, 2), 0);

            Long templateId = templateIds.get(templateName.toLowerCase());
            Long sectionId  = sectionIds.get(sectionName.toLowerCase());

            if (templateId == null) {
                addError(logEntries, "template_sections", row.getRowNum(),
                        "template \"" + templateName + "\" not found in templates sheet — skipped");
                failureCount++;
                continue;
            }
            if (sectionId == null) {
                addError(logEntries, "template_sections", row.getRowNum(),
                        "section \"" + sectionName + "\" not found in sections sheet — skipped");
                failureCount++;
                continue;
            }
            if (!templateSectionMappingRepository.existsByTemplateIdAndSectionId(templateId, sectionId)) {
                templateSectionMappingRepository.save(TemplateSectionMapping.builder()
                        .templateId(templateId)
                        .sectionId(sectionId)
                        .orderNo(orderNo)
                        .build());
            }
            successCount++;
        }

        // ── 2b. section_questions ──────────────────────────────────
        log.info("[XLSX-IMPORT] Pass 2: section_questions");
        Sheet sqSheet = getSheet(workbook, SHEET_SECTION_QUESTIONS);
        for (Row row : sqSheet) {
            if (isHeader(row)) continue;
            String  sectionName  = cell(row, 0);
            String  questionText = cell(row, 1);
            // response_type needed to resolve the question key
            String  responseType = cell(row, 2).toUpperCase();
            Double  weight       = parseDouble(cell(row, 3));
            boolean mandatory    = "true".equalsIgnoreCase(cell(row, 4));
            int     orderNo      = parseIntOrDefault(cell(row, 5), 0);

            Long sectionId  = sectionIds.get(sectionName.toLowerCase());
            Long questionId = questionIds.get(questionKey(questionText, responseType));

            if (sectionId == null) {
                addError(logEntries, "section_questions", row.getRowNum(),
                        "section \"" + sectionName + "\" not found in sections sheet — skipped");
                failureCount++;
                continue;
            }
            if (questionId == null) {
                addError(logEntries, "section_questions", row.getRowNum(),
                        "question \"" + questionText + "\" [" + responseType + "] not found in questions sheet — skipped");
                failureCount++;
                continue;
            }
            if (!sectionQuestionMappingRepository.existsBySectionIdAndQuestionId(sectionId, questionId)) {
                sectionQuestionMappingRepository.save(SectionQuestionMapping.builder()
                        .sectionId(sectionId)
                        .questionId(questionId)
                        .orderNo(orderNo)
                        .weight(weight)
                        .isMandatory(mandatory)
                        .build());
            }
            successCount++;
        }

        // ── 2c. question_options ───────────────────────────────────
        log.info("[XLSX-IMPORT] Pass 2: question_options");
        Sheet qoSheet = getSheet(workbook, SHEET_QUESTION_OPTIONS);
        for (Row row : qoSheet) {
            if (isHeader(row)) continue;
            String questionText  = cell(row, 0);
            String responseType  = cell(row, 1).toUpperCase();
            String optionValue   = cell(row, 2);
            Double score         = parseDouble(cell(row, 3));
            int    orderNo       = parseIntOrDefault(cell(row, 4), 0);

            Long questionId = questionIds.get(questionKey(questionText, responseType));
            Long optionId   = optionIds.get(optionKey(optionValue, score));

            if (questionId == null) {
                addError(logEntries, "question_options", row.getRowNum(),
                        "question \"" + questionText + "\" [" + responseType + "] not found in questions sheet — skipped");
                failureCount++;
                continue;
            }
            if (optionId == null) {
                addError(logEntries, "question_options", row.getRowNum(),
                        "option \"" + optionValue + "\" (score=" + score + ") not found in options sheet — skipped");
                failureCount++;
                continue;
            }
            if (!questionOptionMappingRepository.existsByQuestionIdAndOptionId(questionId, optionId)) {
                questionOptionMappingRepository.save(QuestionOptionMapping.builder()
                        .questionId(questionId)
                        .optionId(optionId)
                        .orderNo(orderNo)
                        .build());
            }
            successCount++;
        }

        try { workbook.close(); } catch (IOException ignored) {}

        String summary = String.format(
                "XLSX import complete | templates=%d | sections=%d | questions=%d | options=%d | %d succeeded | %d failed",
                templateIds.size(), sectionIds.size(), questionIds.size(), optionIds.size(),
                successCount, failureCount);
        log.info("[XLSX-IMPORT] {}", summary);

        return CsvImportResult.builder()
                .fatalError(false)
                .summary(summary)
                .totalRows(successCount + failureCount)
                .successCount(successCount)
                .failureCount(failureCount)
                .log(logEntries)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────

    private Sheet getSheet(Workbook wb, String name) {
        Sheet s = wb.getSheet(name);
        if (s == null) s = wb.getSheet(name.toLowerCase());
        return s;
    }

    /** Row 0 is the header — skip it */
    private boolean isHeader(Row row) {
        return row.getRowNum() == 0;
    }

    private String cell(Row row, int col) {
        Cell c = row.getCell(col, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (c == null) return "";
        return switch (c.getCellType()) {
            case STRING  -> c.getStringCellValue().strip();
            case NUMERIC -> {
                double d = c.getNumericCellValue();
                yield d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
            }
            case BOOLEAN -> String.valueOf(c.getBooleanCellValue());
            default      -> "";
        };
    }

    private Double parseDouble(String s) {
        try { return s.isEmpty() ? null : Double.parseDouble(s); }
        catch (NumberFormatException e) { return null; }
    }

    private int parseIntOrDefault(String s, int def) {
        try { return s.isEmpty() ? def : Integer.parseInt(s); }
        catch (NumberFormatException e) { return def; }
    }

    /** Composite key for questions — text + responseType must both match */
    private String questionKey(String text, String responseType) {
        return text.strip().toLowerCase() + "|" + responseType.strip().toUpperCase();
    }

    /** Composite key for options — value + score (null score → "null") */
    private String optionKey(String value, Double score) {
        return value.strip().toLowerCase() + "|" + (score != null ? score : "null");
    }

    private CsvImportResult fatal(CsvImportResult r, String msg) {
        log.error("[XLSX-IMPORT] Fatal: {}", msg);
        r.addError(msg);
        return CsvImportResult.builder().fatalError(true).summary(msg).build();
    }

    private void addInfo(List<CsvImportResult.ImportLogEntry> log, String msg) {
        log.add(CsvImportResult.ImportLogEntry.builder().message(msg).status("INFO").build());
    }

    private void addError(List<CsvImportResult.ImportLogEntry> log,
                          String sheet, int rowNum, String msg) {
        String full = "[" + sheet + "] row " + (rowNum + 1) + ": " + msg;
        XlsxImportService.log.warn("[XLSX-IMPORT] {}", full);
        log.add(CsvImportResult.ImportLogEntry.builder().message(full).status("ERROR").build());
    }
}