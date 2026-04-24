package com.kashi.grc.assessment.controller;

import com.kashi.grc.assessment.domain.*;
import com.kashi.grc.assessment.dto.request.*;
import com.kashi.grc.assessment.dto.response.*;
import com.kashi.grc.assessment.repository.*;
import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.dto.CsvImportResult;
import com.kashi.grc.common.dto.PaginatedResponse;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.repository.DbRepository;
import com.kashi.grc.common.service.CsvImportService;
import com.kashi.grc.common.util.UtilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.criteria.Predicate;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Library management: options, questions, sections.
 *
 * All entities here are pure reusable library items.
 * There are NO foreign keys (question_id on option, section_id on question, etc.).
 * Relationships are stored in join tables:
 *   option ↔ question  via question_option_mappings
 *   question ↔ section via section_question_mappings
 *   section ↔ template via template_section_mappings
 *
 * Zero duplication: one option row can map to many questions.
 * One question row can map to many sections.
 */
@Slf4j
@RestController
@RequestMapping("/v1/assessment-library")
@Tag(name = "Assessment Library", description = "Reusable options, questions, and sections")
@RequiredArgsConstructor
public class AssessmentLibraryController {

    private final AssessmentQuestionOptionRepository optionRepository;
    private final AssessmentQuestionRepository       questionRepository;
    private final AssessmentSectionRepository        sectionRepository;
    private final QuestionOptionMappingRepository    questionOptionMappingRepository;
    private final SectionQuestionMappingRepository   sectionQuestionMappingRepository;
    private final TemplateSectionMappingRepository   templateSectionMappingRepository;
    private final DbRepository                       dbRepository;
    private final UtilityService                     utilityService;
    private final CsvImportService                   csvImportService;

    // ═══════════════════════════════════════════════════════════
    // OPTIONS
    // ═══════════════════════════════════════════════════════════

    @PostMapping("/options")
    @Operation(summary = "Create a reusable response option")
    public ResponseEntity<ApiResponse<OptionResponse>> createOption(
            @Valid @RequestBody OptionCreateRequest req) {

        boolean isSystem = utilityService.isSystemUser();
        Long tenantId    = isSystem ? null : utilityService.getLoggedInDataContext().getTenantId();
        log.info("[LIBRARY] Creating option | tenantId={} | value=\"{}\" | score={}",
                tenantId, req.getOptionValue(), req.getScore());

        AssessmentQuestionOption opt = AssessmentQuestionOption.builder()
                .tenantId(tenantId).optionValue(req.getOptionValue()).score(req.getScore())
                .build();
        optionRepository.save(opt);
        log.info("[LIBRARY] Option created | id={}", opt.getId());

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(toOptionResponse(opt)));
    }

    @PutMapping("/options/{optionId}")
    @Operation(summary = "Update a library option value or score")
    public ResponseEntity<ApiResponse<OptionResponse>> updateOption(
            @PathVariable Long optionId, @RequestBody OptionCreateRequest req) {

        log.info("[LIBRARY] Updating option | id={}", optionId);
        AssessmentQuestionOption opt = optionRepository.findById(optionId)
                .orElseThrow(() -> new ResourceNotFoundException("Option", optionId));

        if (req.getOptionValue() != null && !req.getOptionValue().isBlank()) opt.setOptionValue(req.getOptionValue());
        if (req.getScore() != null) opt.setScore(req.getScore());
        optionRepository.save(opt);
        log.info("[LIBRARY] Option updated | id={}", optionId);

        return ResponseEntity.ok(ApiResponse.success(toOptionResponse(opt)));
    }

    @DeleteMapping("/options/{optionId}")
    @Transactional
    @Operation(summary = "Delete a library option — removes all question-option mappings")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteOption(@PathVariable Long optionId) {

        log.info("[LIBRARY] Deleting option | id={}", optionId);
        optionRepository.findById(optionId)
                .orElseThrow(() -> new ResourceNotFoundException("Option", optionId));

        int mappings = questionOptionMappingRepository.findByOptionId(optionId).size();
        questionOptionMappingRepository.deleteByOptionId(optionId);
        log.info("[LIBRARY] Removed {} question-option mappings for option id={}", mappings, optionId);

        optionRepository.deleteById(optionId);
        log.info("[LIBRARY] Option deleted | id={}", optionId);

        return ResponseEntity.ok(ApiResponse.success(Map.of("deleted", true, "optionId", optionId)));
    }

    @GetMapping("/options")
    @Operation(summary = "List library options — paginated, searchable")
    public ResponseEntity<ApiResponse<PaginatedResponse<OptionResponse>>> listOptions(
            @RequestParam Map<String, String> allParams) {

        boolean isSystem = utilityService.isSystemUser();
        Long tenantId    = isSystem ? null : utilityService.getLoggedInDataContext().getTenantId();
        log.debug("[LIBRARY] Listing options | isSystem={} | tenantId={}", isSystem, tenantId);

        return ResponseEntity.ok(ApiResponse.success(dbRepository.findAll(
                AssessmentQuestionOption.class, utilityService.getpageDetails(allParams),
                (cb, root) -> {
                    List<Predicate> preds = new ArrayList<>();
                    if (!isSystem) preds.add(cb.or(
                            cb.isNull(root.get("tenantId")),
                            cb.equal(root.get("tenantId"), tenantId)));
                    return preds;
                },
                (cb, root) -> Map.of("optionvalue", root.get("optionValue")),
                this::toOptionResponse)));
    }

    // ═══════════════════════════════════════════════════════════
    // QUESTIONS
    // ═══════════════════════════════════════════════════════════

    @PostMapping("/questions")
    @Transactional
    @Operation(summary = "Create a library question and link options via join table")
    public ResponseEntity<ApiResponse<LibraryQuestionResponse>> createQuestion(
            @Valid @RequestBody LibraryQuestionCreateRequest req) {

        boolean isSystem = utilityService.isSystemUser();
        Long tenantId    = isSystem ? null : utilityService.getLoggedInDataContext().getTenantId();
        log.info("[LIBRARY] Creating question | tenantId={} | type={}", tenantId, req.getResponseType());

        AssessmentQuestion q = AssessmentQuestion.builder()
                .tenantId(tenantId).questionText(req.getQuestionText())
                .responseType(req.getResponseType())
                // questionTag is the KashiGuard category label — null = untagged, guard skips it
                .questionTag(req.getQuestionTag())
                .build();
        questionRepository.save(q);
        log.info("[LIBRARY] Question saved | id={}", q.getId());

        int linked = linkOptionsToQuestion(q.getId(), req.getOptionIds());
        log.info("[LIBRARY] Linked {} options to question id={}", linked, q.getId());

        return ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.success(toLibraryQuestionResponse(q, linked)));
    }

    @PutMapping("/questions/{questionId}")
    @Transactional
    @Operation(summary = "Update a library question — re-links options if provided")
    public ResponseEntity<ApiResponse<LibraryQuestionResponse>> updateQuestion(
            @PathVariable Long questionId, @RequestBody LibraryQuestionCreateRequest req) {

        log.info("[LIBRARY] Updating question | id={}", questionId);
        AssessmentQuestion q = questionRepository.findById(questionId)
                .orElseThrow(() -> new ResourceNotFoundException("AssessmentQuestion", questionId));

        if (req.getQuestionText() != null && !req.getQuestionText().isBlank()) q.setQuestionText(req.getQuestionText());
        if (req.getResponseType() != null && !req.getResponseType().isBlank()) q.setResponseType(req.getResponseType());
        // Allow explicit null to clear the tag (untagging the question removes it from guard evaluation)
        // Allow a new tag string to re-tag the question for a different category
        // NOTE: changing the tag here does NOT affect already-instantiated assessment instances —
        // those have their own questionTagSnapshot which is frozen at instantiation time.
        if (req.getQuestionTag() != null || q.getQuestionTag() != null) {
            q.setQuestionTag(req.getQuestionTag());  // null clears the tag intentionally
        }
        questionRepository.save(q);

        int linked = questionOptionMappingRepository.findByQuestionIdOrderByOrderNo(questionId).size();
        if (req.getOptionIds() != null) {
            log.info("[LIBRARY] Re-linking options for question id={}", questionId);
            questionOptionMappingRepository.deleteByQuestionId(questionId);
            linked = linkOptionsToQuestion(questionId, req.getOptionIds());
        }

        log.info("[LIBRARY] Question updated | id={} | optionsLinked={}", questionId, linked);
        return ResponseEntity.ok(ApiResponse.success(toLibraryQuestionResponse(q, linked)));
    }

    @DeleteMapping("/questions/{questionId}")
    @Transactional
    @Operation(summary = "Delete a library question — removes all its mappings")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteQuestion(@PathVariable Long questionId) {

        log.info("[LIBRARY] Deleting question | id={}", questionId);
        questionRepository.findById(questionId)
                .orElseThrow(() -> new ResourceNotFoundException("AssessmentQuestion", questionId));

        int optMappings = questionOptionMappingRepository.findByQuestionIdOrderByOrderNo(questionId).size();
        int secMappings = sectionQuestionMappingRepository.findByQuestionId(questionId).size();
        questionOptionMappingRepository.deleteByQuestionId(questionId);
        sectionQuestionMappingRepository.deleteByQuestionId(questionId);
        log.info("[LIBRARY] Removed {} option-mappings and {} section-mappings for question id={}",
                optMappings, secMappings, questionId);

        questionRepository.deleteById(questionId);
        log.info("[LIBRARY] Question deleted | id={}", questionId);

        return ResponseEntity.ok(ApiResponse.success(Map.of("deleted", true, "questionId", questionId)));
    }

    @GetMapping("/questions")
    @Operation(summary = "List library questions — paginated, filterable by response type")
    public ResponseEntity<ApiResponse<PaginatedResponse<LibraryQuestionResponse>>> listQuestions(
            @RequestParam Map<String, String> allParams) {

        boolean isSystem = utilityService.isSystemUser();
        Long tenantId    = isSystem ? null : utilityService.getLoggedInDataContext().getTenantId();
        log.debug("[LIBRARY] Listing questions | isSystem={} | tenantId={}", isSystem, tenantId);

        return ResponseEntity.ok(ApiResponse.success(dbRepository.findAll(
                AssessmentQuestion.class, utilityService.getpageDetails(allParams),
                (cb, root) -> {
                    List<Predicate> preds = new ArrayList<>();
                    if (!isSystem) preds.add(cb.or(
                            cb.isNull(root.get("tenantId")),
                            cb.equal(root.get("tenantId"), tenantId)));
                    return preds;
                },
                (cb, root) -> Map.of("questiontext", root.get("questionText"),
                        "responsetype", root.get("responseType")),
                q -> toLibraryQuestionResponse(q,
                        questionOptionMappingRepository.findByQuestionIdOrderByOrderNo(q.getId()).size()))));
    }

    // ── Add these methods inside AssessmentLibraryController ─────────────────

    // ── GET linked options for a question ─────────────────────────
    @GetMapping("/questions/{questionId}/options")
    @Operation(summary = "Get all options linked to a specific library question")
    public ResponseEntity<ApiResponse<List<OptionResponse>>> getQuestionOptions(
            @PathVariable Long questionId) {

        log.debug("[LIBRARY] Fetching linked options for question id={}", questionId);
        questionRepository.findById(questionId)
                .orElseThrow(() -> new ResourceNotFoundException("AssessmentQuestion", questionId));

        List<OptionResponse> options = questionOptionMappingRepository
                .findByQuestionIdOrderByOrderNo(questionId).stream()
                .flatMap(qom -> optionRepository.findById(qom.getOptionId())
                        .map(o -> OptionResponse.builder()
                                .optionId(o.getId())
                                .optionValue(o.getOptionValue())
                                .score(o.getScore())
                                .build())
                        .stream())
                .toList();

        log.debug("[LIBRARY] Found {} linked options for question id={}", options.size(), questionId);
        return ResponseEntity.ok(ApiResponse.success(options));
    }

    // ── Bulk delete questions ──────────────────────────────────────
    @DeleteMapping("/questions/bulk")
    @Transactional
    @Operation(summary = "Delete multiple library questions by ID list")
    public ResponseEntity<ApiResponse<Map<String, Object>>> bulkDeleteQuestions(
            @RequestBody List<Long> questionIds) {

        log.info("[LIBRARY] Bulk deleting {} questions | ids={}", questionIds.size(), questionIds);
        int deleted = 0, skipped = 0;

        for (Long questionId : questionIds) {
            if (!questionRepository.existsById(questionId)) {
                log.warn("[LIBRARY] Question id={} not found — skipping", questionId);
                skipped++;
                continue;
            }
            int optMappings = questionOptionMappingRepository.findByQuestionIdOrderByOrderNo(questionId).size();
            int secMappings = sectionQuestionMappingRepository.findByQuestionId(questionId).size();
            questionOptionMappingRepository.deleteByQuestionId(questionId);
            sectionQuestionMappingRepository.deleteByQuestionId(questionId);
            questionRepository.deleteById(questionId);
            log.info("[LIBRARY] Deleted question id={} | removed {} opt-mappings {} sec-mappings",
                    questionId, optMappings, secMappings);
            deleted++;
        }

        log.info("[LIBRARY] Bulk delete complete | deleted={} | skipped={}", deleted, skipped);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "deleted", deleted, "skipped", skipped, "requested", questionIds.size())));
    }

    // ── Bulk delete options ────────────────────────────────────────
    @DeleteMapping("/options/bulk")
    @Transactional
    @Operation(summary = "Delete multiple library options by ID list")
    public ResponseEntity<ApiResponse<Map<String, Object>>> bulkDeleteOptions(
            @RequestBody List<Long> optionIds) {

        log.info("[LIBRARY] Bulk deleting {} options | ids={}", optionIds.size(), optionIds);
        int deleted = 0, skipped = 0;

        for (Long optionId : optionIds) {
            if (!optionRepository.existsById(optionId)) {
                log.warn("[LIBRARY] Option id={} not found — skipping", optionId);
                skipped++;
                continue;
            }
            int mappings = questionOptionMappingRepository.findByOptionId(optionId).size();
            questionOptionMappingRepository.deleteByOptionId(optionId);
            optionRepository.deleteById(optionId);
            log.info("[LIBRARY] Deleted option id={} | removed {} question-mappings", optionId, mappings);
            deleted++;
        }

        log.info("[LIBRARY] Bulk delete options complete | deleted={} | skipped={}", deleted, skipped);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "deleted", deleted, "skipped", skipped, "requested", optionIds.size())));
    }

    // ── Bulk delete sections ───────────────────────────────────────
    @DeleteMapping("/sections/bulk")
    @Transactional
    @Operation(summary = "Delete multiple library sections by ID list")
    public ResponseEntity<ApiResponse<Map<String, Object>>> bulkDeleteSections(
            @RequestBody List<Long> sectionIds) {

        log.info("[LIBRARY] Bulk deleting {} sections | ids={}", sectionIds.size(), sectionIds);
        int deleted = 0, skipped = 0;

        for (Long sectionId : sectionIds) {
            if (!sectionRepository.existsById(sectionId)) {
                log.warn("[LIBRARY] Section id={} not found — skipping", sectionId);
                skipped++;
                continue;
            }
            long qMappings = sectionQuestionMappingRepository.countBySectionId(sectionId);
            long tMappings = templateSectionMappingRepository.findBySectionId(sectionId).size();
            sectionQuestionMappingRepository.deleteBySectionId(sectionId);
            templateSectionMappingRepository.deleteBySectionId(sectionId);
            sectionRepository.deleteById(sectionId);
            log.info("[LIBRARY] Deleted section id={} | removed {} question-mappings {} template-mappings",
                    sectionId, qMappings, tMappings);
            deleted++;
        }

        log.info("[LIBRARY] Bulk delete sections complete | deleted={} | skipped={}", deleted, skipped);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "deleted", deleted, "skipped", skipped, "requested", sectionIds.size())));
    }

    // ── Library CSV import (questions only) ───────────────────────
    @PostMapping("/questions/import")
    @Operation(summary = "Import questions and options from a flat CSV — server-side OpenCSV")
    public ResponseEntity<ApiResponse<CsvImportResult>> importLibraryQuestions(
            @RequestParam("file") MultipartFile file) {

        boolean isSystem = utilityService.isSystemUser();
        Long tenantId    = isSystem ? null : utilityService.getLoggedInDataContext().getTenantId();
        log.info("[LIBRARY] CSV import | tenantId={} | file={} | size={}",
                tenantId, file.getOriginalFilename(), file.getSize());

        CsvImportResult result = csvImportService.importLibraryQuestions(file, tenantId);
        log.info("[LIBRARY] CSV import complete | success={} | failed={} | fatal={}",
                result.getSuccessCount(), result.getFailureCount(), result.isFatalError());

        HttpStatus status = result.isFatalError() ? HttpStatus.BAD_REQUEST
                : (result.hasErrors() ? HttpStatus.MULTI_STATUS : HttpStatus.CREATED);
        return ResponseEntity.status(status).body(ApiResponse.success(result));
    }

    // ═══════════════════════════════════════════════════════════
    // SECTIONS (standalone library sections)
    // ═══════════════════════════════════════════════════════════

    @PostMapping("/sections")
    @Operation(summary = "Create a standalone reusable section")
    public ResponseEntity<ApiResponse<SectionResponse>> createSection(
            @Valid @RequestBody SectionCreateRequest req) {

        boolean isSystem = utilityService.isSystemUser();
        Long tenantId    = isSystem ? null : utilityService.getLoggedInDataContext().getTenantId();
        log.info("[LIBRARY] Creating section | tenantId={} | name=\"{}\"", tenantId, req.getName());

        AssessmentSection section = AssessmentSection.builder()
                .tenantId(tenantId).name(req.getName()).build();
        sectionRepository.save(section);
        log.info("[LIBRARY] Section created | id={}", section.getId());

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                SectionResponse.builder().sectionId(section.getId()).name(section.getName()).build()));
    }

    @PutMapping("/sections/{sectionId}")
    @Operation(summary = "Rename a library section")
    public ResponseEntity<ApiResponse<SectionResponse>> updateSection(
            @PathVariable Long sectionId, @RequestBody SectionCreateRequest req) {

        log.info("[LIBRARY] Updating section | id={}", sectionId);
        AssessmentSection section = sectionRepository.findById(sectionId)
                .orElseThrow(() -> new ResourceNotFoundException("AssessmentSection", sectionId));

        if (req.getName() != null && !req.getName().isBlank()) section.setName(req.getName());
        sectionRepository.save(section);
        log.info("[LIBRARY] Section updated | id={}", sectionId);

        return ResponseEntity.ok(ApiResponse.success(
                SectionResponse.builder().sectionId(section.getId()).name(section.getName()).build()));
    }

    @DeleteMapping("/sections/{sectionId}")
    @Transactional
    @Operation(summary = "Delete a library section — removes all template and question mappings")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteSection(@PathVariable Long sectionId) {

        log.info("[LIBRARY] Deleting section | id={}", sectionId);
        sectionRepository.findById(sectionId)
                .orElseThrow(() -> new ResourceNotFoundException("AssessmentSection", sectionId));

        long qMappings = sectionQuestionMappingRepository.countBySectionId(sectionId);
        long tMappings = templateSectionMappingRepository.findBySectionId(sectionId).size();
        sectionQuestionMappingRepository.deleteBySectionId(sectionId);
        templateSectionMappingRepository.deleteBySectionId(sectionId);
        log.info("[LIBRARY] Removed {} question-mappings and {} template-mappings for section id={}",
                qMappings, tMappings, sectionId);

        sectionRepository.deleteById(sectionId);
        log.info("[LIBRARY] Section deleted | id={}", sectionId);

        return ResponseEntity.ok(ApiResponse.success(Map.of("deleted", true, "sectionId", sectionId)));
    }

    @GetMapping("/sections")
    @Operation(summary = "List library sections — paginated")
    public ResponseEntity<ApiResponse<PaginatedResponse<SectionResponse>>> listSections(
            @RequestParam Map<String, String> allParams) {

        boolean isSystem = utilityService.isSystemUser();
        Long tenantId    = isSystem ? null : utilityService.getLoggedInDataContext().getTenantId();

        return ResponseEntity.ok(ApiResponse.success(dbRepository.findAll(
                AssessmentSection.class, utilityService.getpageDetails(allParams),
                (cb, root) -> {
                    List<Predicate> preds = new ArrayList<>();
                    if (!isSystem) preds.add(cb.or(
                            cb.isNull(root.get("tenantId")),
                            cb.equal(root.get("tenantId"), tenantId)));
                    return preds;
                },
                (cb, root) -> Map.of("name", root.get("name")),
                s -> SectionResponse.builder().sectionId(s.getId()).name(s.getName()).build())));
    }

    // ═══════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════

    private int linkOptionsToQuestion(Long questionId, List<Long> optionIds) {
        if (optionIds == null || optionIds.isEmpty()) return 0;
        int count = 0;
        for (int i = 0; i < optionIds.size(); i++) {
            Long optId = optionIds.get(i);
            if (!optionRepository.existsById(optId)) {
                log.warn("[LIBRARY] Option id={} not found — skipping link", optId);
                continue;
            }
            if (questionOptionMappingRepository.existsByQuestionIdAndOptionId(questionId, optId)) {
                log.debug("[LIBRARY] Option id={} already linked to question id={}", optId, questionId);
                continue;
            }
            questionOptionMappingRepository.save(QuestionOptionMapping.builder()
                    .questionId(questionId).optionId(optId).orderNo(i + 1).build());
            count++;
        }
        return count;
    }

    private OptionResponse toOptionResponse(AssessmentQuestionOption o) {
        return OptionResponse.builder()
                .optionId(o.getId()).optionValue(o.getOptionValue()).score(o.getScore()).build();
    }

    private LibraryQuestionResponse toLibraryQuestionResponse(AssessmentQuestion q, int linked) {
        return LibraryQuestionResponse.builder()
                .questionId(q.getId()).questionText(q.getQuestionText())
                .responseType(q.getResponseType())
                .questionTag(q.getQuestionTag())   // null = untagged, guard system skips
                .optionsLinked(linked).build();
    }
}