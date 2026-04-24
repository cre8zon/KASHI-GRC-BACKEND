package com.kashi.grc.common.service;

import com.kashi.grc.assessment.domain.*;
import com.kashi.grc.assessment.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Exports the full global assessment library as a multi-sheet .xlsx file.
 *
 * SHEET STRUCTURE (mirrors XlsxImportService — directly re-importable):
 *
 *   templates         → name
 *   sections          → name
 *   questions         → question_text, response_type
 *   options           → option_value, score
 *   template_sections → template_name, section_name, order_no
 *   section_questions → section_name, question_text, response_type, weight, is_mandatory, order_no
 *   question_options  → question_text, response_type, option_value, score, order_no
 *
 * ENTITY SHEETS contain unique records only (no duplicates).
 * MAPPING SHEETS contain all relationships with their context fields (weight, order, etc.).
 *
 * WHY MULTI-SHEET FOR XLSX:
 *   - Entity sheets give analysts a clean deduplicated view of the library
 *   - Mapping sheets show all relationships with context fields clearly separated
 *   - Mirrors the DB design exactly — easier to understand for GRC teams
 *   - Directly re-importable via XlsxImportService
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class XlsxExportService {

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

    /**
     * Exports the entire global library to an xlsx byte array.
     * The caller writes this to the HTTP response with Content-Disposition: attachment.
     */
    public byte[] exportLibrary() throws IOException {
        log.info("[XLSX-EXPORT] Starting full library export");

        try (Workbook workbook = new XSSFWorkbook()) {

            CellStyle headerStyle = buildHeaderStyle(workbook);

            // ── Entity sheets ──────────────────────────────────────
            exportTemplates(workbook, headerStyle);
            exportSections(workbook, headerStyle);
            exportQuestions(workbook, headerStyle);
            exportOptions(workbook, headerStyle);

            // ── Mapping sheets ─────────────────────────────────────
            exportTemplateSections(workbook, headerStyle);
            exportSectionQuestions(workbook, headerStyle);
            exportQuestionOptions(workbook, headerStyle);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            byte[] xlsx = out.toByteArray();
            log.info("[XLSX-EXPORT] Export complete | {} bytes", xlsx.length);
            return xlsx;
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // ENTITY SHEETS
    // ─────────────────────────────────────────────────────────────────

    private void exportTemplates(Workbook wb, CellStyle headerStyle) {
        Sheet sheet = wb.createSheet("templates");
        writeHeader(sheet, headerStyle, "name");

        List<AssessmentTemplate> templates = templateRepository.findAll()
                .stream().filter(t -> t.getTenantId() == null)
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .toList();

        int rowNum = 1;
        for (AssessmentTemplate t : templates) {
            Row row = sheet.createRow(rowNum++);
            cell(row, 0, t.getName());
        }
        autoSize(sheet, 1);
        log.info("[XLSX-EXPORT] templates sheet: {} rows", templates.size());
    }

    private void exportSections(Workbook wb, CellStyle headerStyle) {
        Sheet sheet = wb.createSheet("sections");
        writeHeader(sheet, headerStyle, "name");

        List<AssessmentSection> sections = sectionRepository.findAll()
                .stream().filter(s -> s.getTenantId() == null)
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .toList();

        int rowNum = 1;
        for (AssessmentSection s : sections) {
            Row row = sheet.createRow(rowNum++);
            cell(row, 0, s.getName());
        }
        autoSize(sheet, 1);
        log.info("[XLSX-EXPORT] sections sheet: {} rows", sections.size());
    }

    private void exportQuestions(Workbook wb, CellStyle headerStyle) {
        Sheet sheet = wb.createSheet("questions");
        writeHeader(sheet, headerStyle, "question_text", "response_type");

        List<AssessmentQuestion> questions = questionRepository.findAll()
                .stream().filter(q -> q.getTenantId() == null)
                .sorted((a, b) -> a.getQuestionText().compareToIgnoreCase(b.getQuestionText()))
                .toList();

        int rowNum = 1;
        for (AssessmentQuestion q : questions) {
            Row row = sheet.createRow(rowNum++);
            cell(row, 0, q.getQuestionText());
            cell(row, 1, q.getResponseType());
        }
        autoSize(sheet, 2);
        log.info("[XLSX-EXPORT] questions sheet: {} rows", questions.size());
    }

    private void exportOptions(Workbook wb, CellStyle headerStyle) {
        Sheet sheet = wb.createSheet("options");
        writeHeader(sheet, headerStyle, "option_value", "score");

        List<AssessmentQuestionOption> options = optionRepository.findAll()
                .stream().filter(o -> o.getTenantId() == null)
                .sorted((a, b) -> a.getOptionValue().compareToIgnoreCase(b.getOptionValue()))
                .toList();

        int rowNum = 1;
        for (AssessmentQuestionOption o : options) {
            Row row = sheet.createRow(rowNum++);
            cell(row, 0, o.getOptionValue());
            cellDouble(row, 1, o.getScore());
        }
        autoSize(sheet, 2);
        log.info("[XLSX-EXPORT] options sheet: {} rows", options.size());
    }

    // ─────────────────────────────────────────────────────────────────
    // MAPPING SHEETS
    // ─────────────────────────────────────────────────────────────────

    private void exportTemplateSections(Workbook wb, CellStyle headerStyle) {
        Sheet sheet = wb.createSheet("template_sections");
        writeHeader(sheet, headerStyle, "template_name", "section_name", "order_no");

        List<AssessmentTemplate> templates = templateRepository.findAll()
                .stream().filter(t -> t.getTenantId() == null)
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .toList();

        int rowNum = 1;
        for (AssessmentTemplate template : templates) {
            List<TemplateSectionMapping> mappings =
                    templateSectionMappingRepository.findByTemplateIdOrderByOrderNo(template.getId());
            for (TemplateSectionMapping tsm : mappings) {
                AssessmentSection section = sectionRepository.findById(tsm.getSectionId()).orElse(null);
                if (section == null) continue;
                Row row = sheet.createRow(rowNum++);
                cell(row, 0, template.getName());
                cell(row, 1, section.getName());
                cellInt(row, 2, tsm.getOrderNo());
            }
        }
        autoSize(sheet, 3);
        log.info("[XLSX-EXPORT] template_sections sheet: {} rows", rowNum - 1);
    }

    private void exportSectionQuestions(Workbook wb, CellStyle headerStyle) {
        Sheet sheet = wb.createSheet("section_questions");
        writeHeader(sheet, headerStyle,
                "section_name", "question_text", "response_type", "weight", "is_mandatory", "order_no");

        int rowNum = 1;
        List<AssessmentSection> sections = sectionRepository.findAll()
                .stream().filter(s -> s.getTenantId() == null)
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .toList();

        for (AssessmentSection section : sections) {
            List<SectionQuestionMapping> mappings =
                    sectionQuestionMappingRepository.findBySectionIdOrderByOrderNo(section.getId());
            for (SectionQuestionMapping sqm : mappings) {
                AssessmentQuestion q = questionRepository.findById(sqm.getQuestionId()).orElse(null);
                if (q == null) continue;
                Row row = sheet.createRow(rowNum++);
                cell(row, 0, section.getName());
                cell(row, 1, q.getQuestionText());
                cell(row, 2, q.getResponseType());
                cellDouble(row, 3, sqm.getWeight());
                cellBool(row, 4, sqm.isMandatory());
                cellInt(row, 5, sqm.getOrderNo());
            }
        }
        autoSize(sheet, 6);
        log.info("[XLSX-EXPORT] section_questions sheet: {} rows", rowNum - 1);
    }

    private void exportQuestionOptions(Workbook wb, CellStyle headerStyle) {
        Sheet sheet = wb.createSheet("question_options");
        writeHeader(sheet, headerStyle,
                "question_text", "response_type", "option_value", "score", "order_no");

        int rowNum = 1;
        List<AssessmentQuestion> questions = questionRepository.findAll()
                .stream().filter(q -> q.getTenantId() == null)
                .sorted((a, b) -> a.getQuestionText().compareToIgnoreCase(b.getQuestionText()))
                .toList();

        for (AssessmentQuestion question : questions) {
            List<QuestionOptionMapping> mappings =
                    questionOptionMappingRepository.findByQuestionIdOrderByOrderNo(question.getId());
            for (QuestionOptionMapping qom : mappings) {
                AssessmentQuestionOption opt = optionRepository.findById(qom.getOptionId()).orElse(null);
                if (opt == null) continue;
                Row row = sheet.createRow(rowNum++);
                cell(row, 0, question.getQuestionText());
                cell(row, 1, question.getResponseType());
                cell(row, 2, opt.getOptionValue());
                cellDouble(row, 3, opt.getScore());
                cellInt(row, 4, qom.getOrderNo());
            }
        }
        autoSize(sheet, 5);
        log.info("[XLSX-EXPORT] question_options sheet: {} rows", rowNum - 1);
    }

    // ─────────────────────────────────────────────────────────────────
    // POI HELPERS
    // ─────────────────────────────────────────────────────────────────

    private void writeHeader(Sheet sheet, CellStyle style, String... headers) {
        Row row = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell c = row.createCell(i);
            c.setCellValue(headers[i]);
            c.setCellStyle(style);
        }
    }

    private void cell(Row row, int col, String value) {
        row.createCell(col).setCellValue(value != null ? value : "");
    }

    private void cellDouble(Row row, int col, Double value) {
        Cell c = row.createCell(col);
        if (value != null) c.setCellValue(value);
        else c.setCellValue("");
    }

    private void cellInt(Row row, int col, Integer value) {
        Cell c = row.createCell(col);
        if (value != null) c.setCellValue(value);
        else c.setCellValue("");
    }

    private void cellBool(Row row, int col, boolean value) {
        row.createCell(col).setCellValue(value);
    }

    private void autoSize(Sheet sheet, int colCount) {
        for (int i = 0; i < colCount; i++) sheet.autoSizeColumn(i);
    }

    private CellStyle buildHeaderStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }
}