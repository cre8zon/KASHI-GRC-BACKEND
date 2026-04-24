package com.kashi.grc.common.service;

import com.kashi.grc.assessment.domain.*;
import com.kashi.grc.assessment.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Exports the full global assessment library as a flat .csv file.
 *
 * OUTPUT FORMAT (same columns as CsvImportService — directly re-importable):
 *   type, name_or_text, response_type, weight, is_mandatory, option_value, score
 *
 * ROW ORDER:
 *   For each template (alphabetical):
 *     TEMPLATE row
 *     For each section in template (by orderNo):
 *       SECTION row
 *       For each question in section (by orderNo):
 *         QUESTION row
 *         For each option on that question (by orderNo):
 *           OPTION row
 *
 * WHY FLAT:
 *   - Compatible with data analysis tools (pandas, Excel pivot, Power BI)
 *   - Directly re-importable via CsvImportService (round-trippable)
 *   - Human-readable without needing to cross-reference multiple sheets
 *
 * SCOPE: Global library only (tenant_id IS NULL).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CsvExportService {

    private static final String[] HEADERS = {
            "type", "name_or_text", "response_type", "weight", "is_mandatory", "option_value", "score"
    };

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
     * Exports the entire global library to a UTF-8 CSV byte array.
     * The caller writes this to the HTTP response with Content-Disposition: attachment.
     */
    public byte[] exportLibrary() throws IOException {
        log.info("[CSV-EXPORT] Starting full library export");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);

        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader(HEADERS)
                .setSkipHeaderRecord(false)
                .build();

        try (CSVPrinter printer = new CSVPrinter(writer, format)) {

            // Global templates — tenant_id IS NULL
            List<AssessmentTemplate> templates = templateRepository.findAll()
                    .stream()
                    .filter(t -> t.getTenantId() == null)
                    .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                    .toList();

            log.info("[CSV-EXPORT] Exporting {} templates", templates.size());

            for (AssessmentTemplate template : templates) {

                // TEMPLATE row
                printer.printRecord(
                        "TEMPLATE", template.getName(), "", "", "", "", "");

                // Sections in this template, ordered by orderNo
                List<TemplateSectionMapping> sectionMappings =
                        templateSectionMappingRepository.findByTemplateIdOrderByOrderNo(template.getId());

                for (TemplateSectionMapping tsm : sectionMappings) {
                    AssessmentSection section = sectionRepository.findById(tsm.getSectionId())
                            .orElse(null);
                    if (section == null) {
                        log.warn("[CSV-EXPORT] Section ID {} not found — skipping", tsm.getSectionId());
                        continue;
                    }

                    // SECTION row
                    printer.printRecord(
                            "SECTION", section.getName(), "", "", "", "", "");

                    // Questions in this section, ordered by orderNo
                    List<SectionQuestionMapping> questionMappings =
                            sectionQuestionMappingRepository.findBySectionIdOrderByOrderNo(section.getId());

                    for (SectionQuestionMapping sqm : questionMappings) {
                        AssessmentQuestion question = questionRepository.findById(sqm.getQuestionId())
                                .orElse(null);
                        if (question == null) {
                            log.warn("[CSV-EXPORT] Question ID {} not found — skipping", sqm.getQuestionId());
                            continue;
                        }

                        // QUESTION row — weight/mandatory come from mapping, not question entity
                        printer.printRecord(
                                "QUESTION",
                                question.getQuestionText(),
                                question.getResponseType(),
                                sqm.getWeight() != null ? sqm.getWeight() : "",
                                sqm.isMandatory(),
                                "",
                                "");

                        // Options for this question, ordered by orderNo
                        List<QuestionOptionMapping> optionMappings =
                                questionOptionMappingRepository.findByQuestionIdOrderByOrderNo(question.getId());

                        for (QuestionOptionMapping qom : optionMappings) {
                            AssessmentQuestionOption option = optionRepository.findById(qom.getOptionId())
                                    .orElse(null);
                            if (option == null) {
                                log.warn("[CSV-EXPORT] Option ID {} not found — skipping", qom.getOptionId());
                                continue;
                            }

                            // OPTION row
                            printer.printRecord(
                                    "OPTION",
                                    "",
                                    "",
                                    "",
                                    "",
                                    option.getOptionValue(),
                                    option.getScore() != null ? option.getScore() : "");
                        }
                    }
                }
            }

            printer.flush();
        }

        byte[] csv = out.toByteArray();
        log.info("[CSV-EXPORT] Export complete | {} bytes", csv.length);
        return csv;
    }
}