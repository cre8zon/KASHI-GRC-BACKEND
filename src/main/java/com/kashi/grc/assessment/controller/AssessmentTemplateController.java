package com.kashi.grc.assessment.controller;

import com.kashi.grc.assessment.domain.*;
import com.kashi.grc.assessment.dto.request.*;
import com.kashi.grc.assessment.dto.response.*;
import com.kashi.grc.assessment.repository.*;
import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.dto.CsvImportResult;
import com.kashi.grc.common.dto.PaginatedResponse;
import com.kashi.grc.common.exception.BusinessException;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Template management: create/edit/delete templates, map sections into templates,
 * map questions into sections, publish/unpublish, and bulk CSV import.
 *
 * All relationships are managed via join tables:
 *   template ↔ section  via TemplateSectionMapping  (order_no here)
 *   section  ↔ question via SectionQuestionMapping   (weight, is_mandatory, order_no here)
 *
 * Platform Admin rules:
 *   - Can create, edit, delete, publish, UNPUBLISH any template (including PUBLISHED)
 *   - tenantId=null means global template visible to all orgs
 *
 * Org user rules:
 *   - Can read PUBLISHED templates only
 *   - Cannot modify templates they don't own
 */
@Slf4j
@RestController
@Tag(name = "Assessment Templates", description = "Template creation, mapping, publish, and CSV import")
@RequiredArgsConstructor
public class AssessmentTemplateController {

    private final AssessmentTemplateRepository      templateRepository;
    private final AssessmentSectionRepository       sectionRepository;
    private final AssessmentQuestionRepository      questionRepository;
    private final AssessmentQuestionOptionRepository optionRepository;
    private final TemplateSectionMappingRepository  templateSectionMappingRepository;
    private final SectionQuestionMappingRepository  sectionQuestionMappingRepository;
    private final QuestionOptionMappingRepository   questionOptionMappingRepository;
    private final DbRepository                      dbRepository;
    private final UtilityService                    utilityService;
    private final CsvImportService                  csvImportService;

    // ═══════════════════════════════════════════════════════════
    // TEMPLATE CRUD
    // ═══════════════════════════════════════════════════════════

    @PostMapping("/v1/assessment-templates")
    @Operation(summary = "Create a DRAFT assessment template")
    public ResponseEntity<ApiResponse<TemplateResponse>> createTemplate(
            @Valid @RequestBody TemplateCreateRequest req) {

        boolean isSystem = utilityService.isSystemUser();
        Long tenantId    = isSystem ? null : utilityService.getLoggedInDataContext().getTenantId();
        log.info("[TEMPLATE] Creating template | tenantId={} | name=\"{}\"", tenantId, req.getName());

        AssessmentTemplate t = AssessmentTemplate.builder()
                .tenantId(tenantId).name(req.getName())
                .version(req.getVersion() != null ? req.getVersion() : 1)
                .status("DRAFT").build();
        templateRepository.save(t);
        log.info("[TEMPLATE] Template created | id={} | name=\"{}\"", t.getId(), t.getName());

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(toTemplateResponse(t)));
    }

    @PutMapping("/v1/assessment-templates/{templateId}")
    @Operation(summary = "Update template name/version — DRAFT only (Platform Admin can also update PUBLISHED)")
    public ResponseEntity<ApiResponse<TemplateResponse>> updateTemplate(
            @PathVariable Long templateId, @Valid @RequestBody TemplateCreateRequest req) {

        boolean isSystem = utilityService.isSystemUser();
        Long tenantId    = isSystem ? null : utilityService.getLoggedInDataContext().getTenantId();
        log.info("[TEMPLATE] Updating template | id={} | isSystem={}", templateId, isSystem);

        AssessmentTemplate t = findTemplate(templateId, isSystem, tenantId);
        if ("PUBLISHED".equals(t.getStatus()) && !isSystem) {
            throw new BusinessException("TEMPLATE_PUBLISHED",
                    "Only Platform Admin can edit a published template. Unpublish it first.");
        }

        if (req.getName() != null && !req.getName().isBlank()) t.setName(req.getName());
        if (req.getVersion() != null) t.setVersion(req.getVersion());
        templateRepository.save(t);
        log.info("[TEMPLATE] Template updated | id={} | name=\"{}\"", t.getId(), t.getName());

        return ResponseEntity.ok(ApiResponse.success(toTemplateResponse(t)));
    }

    @DeleteMapping("/v1/assessment-templates/{templateId}")
    @Transactional
    @Operation(summary = "Delete a template — Platform Admin can delete even PUBLISHED templates")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteTemplate(
            @PathVariable Long templateId) {

        boolean isSystem = utilityService.isSystemUser();
        Long tenantId    = isSystem ? null : utilityService.getLoggedInDataContext().getTenantId();
        log.info("[TEMPLATE] Deleting template | id={} | isSystem={}", templateId, isSystem);

        AssessmentTemplate t = findTemplate(templateId, isSystem, tenantId);
        if ("PUBLISHED".equals(t.getStatus()) && !isSystem) {
            throw new BusinessException("TEMPLATE_PUBLISHED",
                    "Only Platform Admin can delete a published template. Unpublish it first.");
        }

        // Remove all template→section mappings
        // Sections themselves stay in the library for reuse
        long sectionMappings = templateSectionMappingRepository.countByTemplateId(templateId);
        templateSectionMappingRepository.deleteByTemplateId(templateId);
        log.info("[TEMPLATE] Removed {} section mappings for template id={}", sectionMappings, templateId);

        templateRepository.deleteById(templateId);
        log.info("[TEMPLATE] Template deleted | id={}", templateId);

        return ResponseEntity.ok(ApiResponse.success(Map.of("deleted", true, "templateId", templateId)));
    }

    @PutMapping("/v1/assessment-templates/{templateId}/publish")
    @Transactional
    @Operation(summary = "Publish a DRAFT template — makes it available for vendor assessments")
    public ResponseEntity<ApiResponse<TemplateResponse>> publishTemplate(
            @PathVariable Long templateId) {

        boolean isSystem = utilityService.isSystemUser();
        Long tenantId    = isSystem ? null : utilityService.getLoggedInDataContext().getTenantId();
        log.info("[TEMPLATE] Publishing template | id={}", templateId);

        AssessmentTemplate t = findTemplate(templateId, isSystem, tenantId);
        if ("PUBLISHED".equals(t.getStatus())) {
            throw new BusinessException("ALREADY_PUBLISHED", "Template is already published");
        }

        long sectionCount   = templateSectionMappingRepository.countByTemplateId(templateId);
        long questionCount  = templateSectionMappingRepository.findByTemplateIdOrderByOrderNo(templateId)
                .stream()
                .mapToLong(tsm -> sectionQuestionMappingRepository.countBySectionId(tsm.getSectionId()))
                .sum();

        if (sectionCount == 0) {
            throw new BusinessException("TEMPLATE_EMPTY", "Cannot publish a template with no sections");
        }
        if (questionCount == 0) {
            throw new BusinessException("TEMPLATE_NO_QUESTIONS",
                    "Cannot publish a template with no questions in any section");
        }

        t.setStatus("PUBLISHED");
        t.setPublishedAt(LocalDateTime.now());
        t.setUnpublishedAt(null);
        templateRepository.save(t);
        log.info("[TEMPLATE] Template published | id={} | sections={} | questions={}",
                templateId, sectionCount, questionCount);

        return ResponseEntity.ok(ApiResponse.success(toTemplateResponse(t)));
    }

    @PutMapping("/v1/assessment-templates/{templateId}/unpublish")
    @Transactional
    @Operation(summary = "Unpublish a template — Platform Admin only, reverts to DRAFT for editing")
    public ResponseEntity<ApiResponse<TemplateResponse>> unpublishTemplate(
            @PathVariable Long templateId) {

        boolean isSystem = utilityService.isSystemUser();
        if (!isSystem) {
            throw new BusinessException("FORBIDDEN",
                    "Only Platform Admin can unpublish templates",
                    org.springframework.http.HttpStatus.FORBIDDEN);
        }
        log.info("[TEMPLATE] Unpublishing template | id={}", templateId);

        AssessmentTemplate t = templateRepository.findById(templateId)
                .orElseThrow(() -> new ResourceNotFoundException("AssessmentTemplate", templateId));
        if ("DRAFT".equals(t.getStatus())) {
            throw new BusinessException("ALREADY_DRAFT", "Template is already in DRAFT status");
        }

        t.setStatus("DRAFT");
        t.setUnpublishedAt(LocalDateTime.now());
        templateRepository.save(t);
        log.info("[TEMPLATE] Template unpublished | id={} — now DRAFT, editable again", templateId);

        return ResponseEntity.ok(ApiResponse.success(toTemplateResponse(t)));
    }

    // ═══════════════════════════════════════════════════════════
    // LIST + GET
    // ═══════════════════════════════════════════════════════════

    @GetMapping("/v1/assessment-templates")
    @Operation(summary = "List templates — Platform Admin sees all, orgs see only PUBLISHED")
    public ResponseEntity<ApiResponse<PaginatedResponse<TemplateResponse>>> listTemplates(
            @RequestParam Map<String, String> allParams) {

        boolean isSystem = utilityService.isSystemUser();
        Long tenantId    = isSystem ? null : utilityService.getLoggedInDataContext().getTenantId();
        log.debug("[TEMPLATE] Listing templates | isSystem={} | tenantId={}", isSystem, tenantId);

        return ResponseEntity.ok(ApiResponse.success(dbRepository.findAll(
                AssessmentTemplate.class, utilityService.getpageDetails(allParams),
                (cb, root) -> {
                    List<Predicate> preds = new ArrayList<>();
                    if (!isSystem) {
                        // Org users see global (null tenantId) + own, PUBLISHED only
                        preds.add(cb.or(
                                cb.isNull(root.get("tenantId")),
                                cb.equal(root.get("tenantId"), tenantId)));
                        String statusParam = allParams.get("status");
                        preds.add(cb.equal(root.get("status"),
                                statusParam != null ? statusParam : "PUBLISHED"));
                    }
                    return preds;
                },
                (cb, root) -> Map.of("name", root.get("name"), "status", root.get("status")),
                this::toTemplateResponse)));
    }

    @GetMapping("/v1/assessment-templates/{templateId}/full")
    @Operation(summary = "Get full template structure with sections, questions, and options")
    public ResponseEntity<ApiResponse<TemplateResponse>> getFull(@PathVariable Long templateId) {

        boolean isSystem = utilityService.isSystemUser();
        Long tenantId    = isSystem ? null : utilityService.getLoggedInDataContext().getTenantId();
        log.debug("[TEMPLATE] Getting full template | id={}", templateId);

        AssessmentTemplate t = findTemplate(templateId, isSystem, tenantId);

        List<SectionResponse> sections = templateSectionMappingRepository
                .findByTemplateIdOrderByOrderNo(templateId).stream()
                .map(tsm -> {
                    AssessmentSection section = sectionRepository.findById(tsm.getSectionId())
                            .orElseThrow(() -> new ResourceNotFoundException("Section", tsm.getSectionId()));

                    List<QuestionResponse> questions = sectionQuestionMappingRepository
                            .findBySectionIdOrderByOrderNo(tsm.getSectionId()).stream()
                            .map(sqm -> {
                                AssessmentQuestion q = questionRepository.findById(sqm.getQuestionId())
                                        .orElseThrow(() -> new ResourceNotFoundException("Question", sqm.getQuestionId()));

                                List<OptionResponse> options = questionOptionMappingRepository
                                        .findByQuestionIdOrderByOrderNo(sqm.getQuestionId()).stream()
                                        .map(qom -> optionRepository.findById(qom.getOptionId())
                                                .map(o -> OptionResponse.builder()
                                                        .optionId(o.getId())
                                                        .optionValue(o.getOptionValue())
                                                        .score(o.getScore()).build())
                                                .orElse(null))
                                        .filter(o -> o != null)
                                        .toList();

                                return QuestionResponse.builder()
                                        .questionId(q.getId())
                                        .questionText(q.getQuestionText())
                                        .responseType(q.getResponseType())
                                        .weight(sqm.getWeight())
                                        .isMandatory(sqm.isMandatory())
                                        .orderNo(sqm.getOrderNo())
                                        .options(options).build();
                            }).toList();

                    return SectionResponse.builder()
                            .sectionId(section.getId())
                            .name(section.getName())
                            .orderNo(tsm.getOrderNo())
                            .questions(questions).build();
                }).toList();

        TemplateResponse response = TemplateResponse.builder()
                .templateId(t.getId()).name(t.getName()).version(t.getVersion())
                .status(t.getStatus()).publishedAt(t.getPublishedAt())
                .createdAt(t.getCreatedAt()).sections(sections).build();

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ═══════════════════════════════════════════════════════════
    // SECTION MAPPING INTO TEMPLATE
    // ═══════════════════════════════════════════════════════════

    @PostMapping("/v1/assessment-templates/{templateId}/sections/{sectionId}")
    @Operation(summary = "Map an existing library section into a template")
    public ResponseEntity<ApiResponse<Map<String, Object>>> mapSectionToTemplate(
            @PathVariable Long templateId,
            @PathVariable Long sectionId,
            @RequestParam(defaultValue = "0") int orderNo) {

        boolean isSystem = utilityService.isSystemUser();
        Long tenantId    = isSystem ? null : utilityService.getLoggedInDataContext().getTenantId();
        log.info("[TEMPLATE] Mapping section id={} into template id={} | orderNo={}",
                sectionId, templateId, orderNo);

        AssessmentTemplate t = findTemplate(templateId, isSystem, tenantId);
        guardDraft(t, isSystem);

        sectionRepository.findById(sectionId)
                .orElseThrow(() -> new ResourceNotFoundException("AssessmentSection", sectionId));

        if (templateSectionMappingRepository.existsByTemplateIdAndSectionId(templateId, sectionId)) {
            throw new BusinessException("ALREADY_MAPPED",
                    "Section " + sectionId + " is already mapped to this template");
        }

        // Auto-assign order if not provided
        int effectiveOrder = orderNo > 0 ? orderNo
                : (int) templateSectionMappingRepository.countByTemplateId(templateId) + 1;

        templateSectionMappingRepository.save(TemplateSectionMapping.builder()
                .templateId(templateId).sectionId(sectionId).orderNo(effectiveOrder).build());

        log.info("[TEMPLATE] Section id={} mapped to template id={} at order={}", sectionId, templateId, effectiveOrder);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                Map.of("templateId", templateId, "sectionId", sectionId, "orderNo", effectiveOrder)));
    }

    @DeleteMapping("/v1/assessment-templates/{templateId}/sections/{sectionId}")
    @Transactional
    @Operation(summary = "Remove a section from a template (section stays in library)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> unmapSectionFromTemplate(
            @PathVariable Long templateId, @PathVariable Long sectionId) {

        boolean isSystem = utilityService.isSystemUser();
        Long tenantId    = isSystem ? null : utilityService.getLoggedInDataContext().getTenantId();
        log.info("[TEMPLATE] Unmapping section id={} from template id={}", sectionId, templateId);

        AssessmentTemplate t = findTemplate(templateId, isSystem, tenantId);
        guardDraft(t, isSystem);

        templateSectionMappingRepository.deleteByTemplateIdAndSectionId(templateId, sectionId);
        log.info("[TEMPLATE] Section id={} unmapped from template id={}", sectionId, templateId);

        return ResponseEntity.ok(ApiResponse.success(
                Map.of("templateId", templateId, "sectionId", sectionId, "unmapped", true)));
    }

    // ═══════════════════════════════════════════════════════════
    // QUESTION MAPPING INTO SECTION (within a template context)
    // ═══════════════════════════════════════════════════════════

    @PostMapping("/v1/assessment-sections/{sectionId}/questions/{questionId}")
    @Operation(summary = "Map a library question into a section with weight/mandatory/order")
    public ResponseEntity<ApiResponse<Map<String, Object>>> mapQuestionToSection(
            @PathVariable Long sectionId,
            @PathVariable Long questionId,
            @RequestBody SectionQuestionRequest req) {

        log.info("[TEMPLATE] Mapping question id={} into section id={} | weight={} | mandatory={}",
                questionId, sectionId, req.getWeight(), req.getIsMandatory());

        sectionRepository.findById(sectionId)
                .orElseThrow(() -> new ResourceNotFoundException("AssessmentSection", sectionId));
        questionRepository.findById(questionId)
                .orElseThrow(() -> new ResourceNotFoundException("AssessmentQuestion", questionId));

        if (sectionQuestionMappingRepository.existsBySectionIdAndQuestionId(sectionId, questionId)) {
            throw new BusinessException("ALREADY_MAPPED",
                    "Question " + questionId + " is already mapped to section " + sectionId);
        }

        int effectiveOrder = req.getOrderNo() != null && req.getOrderNo() > 0
                ? req.getOrderNo()
                : (int) sectionQuestionMappingRepository.countBySectionId(sectionId) + 1;

        sectionQuestionMappingRepository.save(SectionQuestionMapping.builder()
                .sectionId(sectionId).questionId(questionId)
                .orderNo(effectiveOrder)
                .weight(req.getWeight())
                .isMandatory(Boolean.TRUE.equals(req.getIsMandatory()))
                .build());

        log.info("[TEMPLATE] Question id={} mapped to section id={} at order={}", questionId, sectionId, effectiveOrder);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(Map.of(
                "sectionId", sectionId, "questionId", questionId,
                "orderNo", effectiveOrder,
                "weight", req.getWeight() != null ? req.getWeight() : 0,
                "isMandatory", Boolean.TRUE.equals(req.getIsMandatory()))));
    }

    @PutMapping("/v1/assessment-sections/{sectionId}/questions/{questionId}")
    @Operation(summary = "Update weight/mandatory/order for a question-section mapping")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateQuestionMapping(
            @PathVariable Long sectionId,
            @PathVariable Long questionId,
            @RequestBody SectionQuestionRequest req) {

        log.info("[TEMPLATE] Updating question mapping | sectionId={} | questionId={}", sectionId, questionId);

        SectionQuestionMapping sqm = sectionQuestionMappingRepository
                .findBySectionIdAndQuestionId(sectionId, questionId)
                .orElseThrow(() -> new BusinessException("MAPPING_NOT_FOUND",
                        "Question " + questionId + " is not mapped to section " + sectionId));

        if (req.getWeight() != null)      sqm.setWeight(req.getWeight());
        if (req.getIsMandatory() != null) sqm.setMandatory(Boolean.TRUE.equals(req.getIsMandatory()));
        if (req.getOrderNo() != null)     sqm.setOrderNo(req.getOrderNo());
        sectionQuestionMappingRepository.save(sqm);

        log.info("[TEMPLATE] Question mapping updated | sectionId={} | questionId={}", sectionId, questionId);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "sectionId", sectionId, "questionId", questionId,
                "weight", sqm.getWeight() != null ? sqm.getWeight() : 0,
                "isMandatory", sqm.isMandatory(), "orderNo", sqm.getOrderNo())));
    }

    @DeleteMapping("/v1/assessment-sections/{sectionId}/questions/{questionId}")
    @Transactional
    @Operation(summary = "Remove a question from a section (question stays in library)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> unmapQuestionFromSection(
            @PathVariable Long sectionId, @PathVariable Long questionId) {

        log.info("[TEMPLATE] Unmapping question id={} from section id={}", questionId, sectionId);
        sectionQuestionMappingRepository.deleteBySectionIdAndQuestionId(sectionId, questionId);
        log.info("[TEMPLATE] Question id={} unmapped from section id={}", questionId, sectionId);

        return ResponseEntity.ok(ApiResponse.success(
                Map.of("sectionId", sectionId, "questionId", questionId, "unmapped", true)));
    }

    // ═══════════════════════════════════════════════════════════
    // CSV BULK IMPORT (backend OpenCSV)
    // ═══════════════════════════════════════════════════════════

    @PostMapping("/v1/assessment-templates/import")
    @Operation(summary = "Import a full template from CSV — parsed server-side using OpenCSV")
    public ResponseEntity<ApiResponse<CsvImportResult>> importTemplate(
            @RequestParam("file") MultipartFile file) {

        boolean isSystem = utilityService.isSystemUser();
        Long tenantId    = isSystem ? null : utilityService.getLoggedInDataContext().getTenantId();
        log.info("[TEMPLATE] CSV import requested | tenantId={} | file={} | size={}",
                tenantId, file.getOriginalFilename(), file.getSize());

        CsvImportResult result = csvImportService.importAssessmentTemplate(file, tenantId);

        log.info("[TEMPLATE] CSV import complete | templateId={} | success={} | failed={} | fatal={}",
                result.getCreatedEntityId(), result.getSuccessCount(),
                result.getFailureCount(), result.isFatalError());

        HttpStatus status = result.isFatalError()
                ? HttpStatus.BAD_REQUEST
                : (result.hasErrors() ? HttpStatus.MULTI_STATUS : HttpStatus.CREATED);

        return ResponseEntity.status(status).body(ApiResponse.success(result));
    }

    // ═══════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════

    private AssessmentTemplate findTemplate(Long templateId, boolean isSystem, Long callerTenantId) {
        return templateRepository.findById(templateId)
                .filter(t -> isSystem
                        || t.getTenantId() == null
                        || t.getTenantId().equals(callerTenantId))
                .orElseThrow(() -> new ResourceNotFoundException("AssessmentTemplate", templateId));
    }

    /**
     * Org users cannot modify published templates.
     * Platform Admin can modify published templates directly (they can also unpublish first).
     */
    private void guardDraft(AssessmentTemplate t, boolean isSystem) {
        if ("PUBLISHED".equals(t.getStatus()) && !isSystem) {
            throw new BusinessException("TEMPLATE_PUBLISHED",
                    "Cannot modify a published template. Only Platform Admin can edit published templates.");
        }
    }

    private TemplateResponse toTemplateResponse(AssessmentTemplate t) {
        return TemplateResponse.builder()
                .templateId(t.getId()).name(t.getName()).version(t.getVersion())
                .status(t.getStatus()).publishedAt(t.getPublishedAt())
                .createdAt(t.getCreatedAt()).build();
    }
}