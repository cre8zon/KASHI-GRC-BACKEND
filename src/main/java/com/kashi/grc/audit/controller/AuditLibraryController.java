package com.kashi.grc.audit.controller;

import com.kashi.grc.audit.csv.AuditCsvImportService;
import com.kashi.grc.audit.domain.*;
import com.kashi.grc.audit.dto.request.*;
import com.kashi.grc.audit.repository.*;
import com.kashi.grc.audit.service.AuditReferenceListCacheService;
import com.kashi.grc.audit.service.AuditSectionService;
import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.dto.CsvImportResult;
import com.kashi.grc.common.dto.PaginatedResponse;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.repository.DbRepository;
import com.kashi.grc.common.util.UtilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.kashi.grc.audit.csv.AuditCsvImportExtension;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * AuditLibraryController — /v1/audit/library/**
 *
 * Platform Admin manages the entire reusable library:
 *
 *   Controls      — leaf-level test items (global or tenant-private)
 *   Sections      — hierarchical tree nodes (global or tenant-private)
 *   Templates     — containers linking root sections (global or tenant-private)
 *   Projects      — global audit programmes (tenantId=null, visible to all orgs)
 *                   Org projects live in AuditEngagementController at /v1/audit/projects
 *
 * ── WHO USES WHAT ────────────────────────────────────────────────────────────
 *
 *   AuditLibraryController (/v1/audit/library/**)
 *     → Platform Admin creates global controls, sections, templates, projects
 *     → Pre-maps templates to global projects before any org starts them
 *     → Publishes/unpublishes templates
 *     → CSV bulk import
 *
 *   AuditEngagementController (/v1/audit/**)
 *     → Org creates their own tenant-scoped projects
 *     → Org views global + own projects
 *     → Org adds templates to a project plan (global or own project)
 *     → Org starts engagement → triggers snapshot → 100% isolated instances
 *
 * ── ISOLATION CONTRACT ────────────────────────────────────────────────────────
 *   At engagement creation AuditEngagementService.create() fires:
 *     AuditProject              → AuditProjectInstance              (frozen once per project)
 *     AuditTemplate             → AuditEngagementTemplateInstance   (frozen per engagement)
 *     AuditSection tree         → AuditSectionInstance tree         (frozen)
 *     AuditSectionControlMapping→ AuditControlInstance              (frozen)
 *   After snapshot: ZERO FK between library and instance tables.
 *
 * ── SECURITY ─────────────────────────────────────────────────────────────────
 *   SecurityConfig: .anyRequest().authenticated() — all /v1/** requires JWT.
 *   isSystemUser() gates Platform Admin operations (global entities, tenantId=null).
 *   Org users read PUBLISHED templates only; edit their own private ones only.
 */
@Slf4j
@RestController
@RequestMapping("/v1/audit/library")
@Tag(name = "Audit Library", description = "Platform Admin — controls, sections, templates, global projects")
@RequiredArgsConstructor
public class AuditLibraryController {

    private final AuditControlRepository                controlRepository;
    private final com.kashi.grc.audit.service.AuditLibraryCacheService libraryCache;
    private final AuditSectionRepository                sectionRepository;
    private final AuditTemplateRepository               templateRepository;
    private final AuditProjectRepository                projectRepository;
    private final AuditProjectTemplateRepository        projectTemplateRepository;
    private final AuditProjectInstanceRepository        projectInstanceRepository;
    private final AuditTemplateSectionMappingRepository templateSectionMappingRepository;
    private final AuditSectionControlMappingRepository  sectionControlMappingRepository;
    private final AuditSectionService                   sectionService;
    private final AuditCsvImportService                 csvImportService;
    private final AuditCsvImportExtension               csvImportExtension;   // FIX: for tests-policies CSV endpoints
    private final DbRepository                          dbRepository;
    private final UtilityService                        utilityService;
    private final AuditReferenceListCacheService auditReferenceListCacheService;

    // ══════════════════════════════════════════════════════════════════════════
    // CONTROLS
    // ══════════════════════════════════════════════════════════════════════════

    @GetMapping("/controls")
    @Operation(summary = "List controls — global (tenantId=null) + caller's tenant-private")
    public ResponseEntity<ApiResponse<PaginatedResponse<Map<String, Object>>>> listControls(
            @RequestParam Map<String, String> allParams) {

        var ctx  = utilityService.getLoggedInDataContext();
        Long tid = ctx.getTenantId();

        return ResponseEntity.ok(ApiResponse.success(dbRepository.findAll(
                AuditControl.class,
                utilityService.getpageDetails(allParams),
                (cb, root) -> {
                    List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
                    predicates.add(cb.or(
                            cb.isNull(root.get("tenantId")),
                            cb.equal(root.get("tenantId"), tid)
                    ));
                    if (allParams.containsKey("testtype"))
                        predicates.add(cb.equal(root.get("testType"),
                                AuditControl.TestType.valueOf(allParams.get("testtype").toUpperCase())));
                    if (allParams.containsKey("frameworkref"))
                        predicates.add(cb.equal(root.get("frameworkRef"), allParams.get("frameworkref")));
                    return predicates;
                },
                (cb, root) -> Map.of(
                        "name",         root.get("name"),
                        "controlCode",  root.get("controlCode"),
                        "frameworkRef", root.get("frameworkRef"),
                        "testType",     root.get("testType"),
                        "controlTag",   root.get("controlTag")
                ),
                this::buildControlMap
        )));
    }

    @PostMapping("/controls")
    @Operation(summary = "Create a control — Platform Admin → global (tenantId=null), org → private")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createControl(
            @Valid @RequestBody AuditControlRequest req) {

        boolean isSystem = utilityService.isSystemUser();
        var ctx          = utilityService.getLoggedInDataContext();
        Long tenantId    = isSystem ? null : ctx.getTenantId();

        AuditControl control = AuditControl.builder()
                .name(req.getName())
                .description(req.getDescription())
                .controlCode(req.getControlCode())
                .frameworkRef(req.getFrameworkRef())
                .testType(req.getTestType() != null ? req.getTestType() : AuditControl.TestType.DOCUMENT_REVIEW)
                .controlTag(req.getControlTag() != null ? req.getControlTag().toUpperCase().trim() : null)
                .evidenceGuidance(blankToNull(req.getEvidenceGuidance()))
                .tenantId(tenantId)
                .createdBy(ctx.getId())
                .build();

        controlRepository.save(control);
        log.info("[AUDIT-LIBRARY] Control created | id={} | name=\"{}\" | tenantId={}",
                control.getId(), control.getName(), tenantId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(buildControlMap(control)));
    }

    @GetMapping("/controls/{controlId}")
    @Operation(summary = "Get a single library control by ID")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getControl(
            @PathVariable Long controlId) {
        AuditControl control = controlRepository.findById(controlId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditControl", controlId));
        return ResponseEntity.ok(ApiResponse.success(buildControlMap(control)));
    }

    @PutMapping("/controls/{controlId}")
    @Operation(summary = "Update a control")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateControl(
            @PathVariable Long controlId,
            @Valid @RequestBody AuditControlRequest req) {

        boolean isSystem = utilityService.isSystemUser();
        var ctx          = utilityService.getLoggedInDataContext();

        AuditControl control = controlRepository.findById(controlId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditControl", controlId));

        if (!isSystem && !Objects.equals(control.getTenantId(), ctx.getTenantId()))
            throw new BusinessException("CONTROL_ACCESS_DENIED",
                    "You can only edit controls belonging to your organisation");

        if (req.getName()         != null) control.setName(req.getName());
        if (req.getDescription()  != null) control.setDescription(req.getDescription());
        if (req.getControlCode()  != null) control.setControlCode(req.getControlCode());
        if (req.getFrameworkRef() != null) control.setFrameworkRef(req.getFrameworkRef());
        if (req.getTestType()     != null) control.setTestType(req.getTestType());
        if (req.getControlTag()   != null) control.setControlTag(req.getControlTag().toUpperCase().trim());
        // Unlike the fields above, an empty string here means "remove the guidance".
        // Treating blank as "no change" would make the field impossible to clear from
        // the UI once set, because the form always posts a string.
        if (req.getEvidenceGuidance() != null) control.setEvidenceGuidance(blankToNull(req.getEvidenceGuidance()));

        controlRepository.save(control);
        log.info("[AUDIT-LIBRARY] Control updated | id={}", controlId);
        return ResponseEntity.ok(ApiResponse.success(buildControlMap(control)));
    }

    @DeleteMapping("/controls/{controlId}")
    @Transactional
    @Operation(summary = "Delete a control — also removes all section mappings referencing it")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteControl(
            @PathVariable Long controlId) {

        boolean isSystem = utilityService.isSystemUser();
        var ctx          = utilityService.getLoggedInDataContext();

        AuditControl control = controlRepository.findById(controlId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditControl", controlId));

        if (!isSystem && !Objects.equals(control.getTenantId(), ctx.getTenantId()))
            throw new BusinessException("CONTROL_ACCESS_DENIED",
                    "You can only delete controls belonging to your organisation");

        sectionControlMappingRepository.deleteByControlId(controlId);
        controlRepository.delete(control);

        log.info("[AUDIT-LIBRARY] Control deleted | id={} | name=\"{}\"", controlId, control.getName());
        return ResponseEntity.ok(ApiResponse.success(Map.of("deleted", controlId)));
    }

    @DeleteMapping("/controls")
    @Transactional
    @Operation(summary = "Bulk delete controls by ID list — also removes all section mappings")
    public ResponseEntity<ApiResponse<Map<String, Object>>> bulkDeleteControls(
            @RequestParam List<Long> ids) {
        boolean isSystem = utilityService.isSystemUser();
        var ctx          = utilityService.getLoggedInDataContext();
        int deleted = 0;
        for (Long id : ids) {
            controlRepository.findById(id).ifPresent(c -> {
                if (isSystem || Objects.equals(c.getTenantId(), ctx.getTenantId())) {
                    sectionControlMappingRepository.deleteByControlId(id);
                    controlRepository.delete(c);
                }
            });
            deleted++;
        }
        log.info("[AUDIT-LIBRARY] Bulk deleted {} controls", deleted);
        return ResponseEntity.ok(ApiResponse.success(Map.of("deleted", deleted)));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SECTIONS — tree-aware CRUD
    // ══════════════════════════════════════════════════════════════════════════

    @GetMapping("/sections/roots")
    @Operation(summary = "List root sections (depth=0) — global + tenant-private, paginated")
    public ResponseEntity<ApiResponse<PaginatedResponse<Map<String, Object>>>> listRootSections(
            @RequestParam Map<String, String> allParams) {

        var ctx  = utilityService.getLoggedInDataContext();
        Long tid = ctx.getTenantId();

        return ResponseEntity.ok(ApiResponse.success(dbRepository.findAll(
                AuditSection.class,
                utilityService.getpageDetails(allParams),
                (cb, root) -> {
                    List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
                    predicates.add(cb.isNull(root.get("parentId")));
                    predicates.add(cb.or(
                            cb.isNull(root.get("tenantId")),
                            cb.equal(root.get("tenantId"), tid)
                    ));
                    if (allParams.containsKey("frameworkref"))
                        predicates.add(cb.equal(root.get("frameworkRef"), allParams.get("frameworkref")));
                    return predicates;
                },
                (cb, root) -> Map.of(
                        "name",        root.get("name"),
                        "sectionCode", root.get("sectionCode"),
                        "orderNo",     root.get("orderNo")
                ),
                this::buildSectionMap
        )));
    }

    @GetMapping("/sections/{parentId}/children")
    @Operation(summary = "Direct children of a section — used by tree view on expand")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listChildren(
            @PathVariable Long parentId) {

        List<Map<String, Object>> children = sectionRepository
                .findByParentIdOrderByOrderNoAsc(parentId)
                .stream().map(this::buildSectionMap).toList();
        return ResponseEntity.ok(ApiResponse.success(children));
    }

    @PostMapping("/sections")
    @Operation(summary = "Create a section — parentId=null for root, parentId=X for child at any depth")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createSection(
            @Valid @RequestBody AuditSectionRequest req) {

        boolean isSystem = utilityService.isSystemUser();
        var ctx          = utilityService.getLoggedInDataContext();
        Long tenantId    = isSystem ? null : ctx.getTenantId();

        AuditSection section = sectionService.createSection(
                req.getName(), req.getDescription(),
                req.getSectionCode(), req.getFrameworkRef(),
                req.getOrderNo(), req.getParentId(),
                tenantId, ctx.getId()
        );

        log.info("[AUDIT-LIBRARY] Section created | id={} | depth={} | parentId={}",
                section.getId(), section.getDepth(), req.getParentId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(buildSectionMap(section)));
    }

    @PutMapping("/sections/{sectionId}")
    @Operation(summary = "Update section metadata — name, code, description, frameworkRef")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateSection(
            @PathVariable Long sectionId,
            @Valid @RequestBody AuditSectionRequest req) {

        boolean isSystem = utilityService.isSystemUser();
        var ctx          = utilityService.getLoggedInDataContext();

        AuditSection section = sectionRepository.findById(sectionId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditSection", sectionId));

        if (!isSystem && !Objects.equals(section.getTenantId(), ctx.getTenantId()))
            throw new BusinessException("SECTION_ACCESS_DENIED",
                    "You can only edit sections belonging to your organisation");

        if (req.getName()         != null) section.setName(req.getName());
        if (req.getDescription()  != null) section.setDescription(req.getDescription());
        if (req.getSectionCode()  != null) section.setSectionCode(req.getSectionCode());
        if (req.getFrameworkRef() != null) section.setFrameworkRef(req.getFrameworkRef());
        sectionRepository.save(section);

        log.info("[AUDIT-LIBRARY] Section updated | id={}", sectionId);
        return ResponseEntity.ok(ApiResponse.success(buildSectionMap(section)));
    }

    @PostMapping("/sections/{sectionId}/move")
    @Operation(summary = "Move section under a new parent — updates path for entire subtree")
    public ResponseEntity<ApiResponse<Map<String, Object>>> moveSection(
            @PathVariable Long sectionId,
            @RequestParam(required = false) Long newParentId) {

        var ctx = utilityService.getLoggedInDataContext();

        // The tenantId below is the CALLER's, not the section's — passing it in
        // was never a check that the section belonged to them.
        requireOwnedSection(sectionRepository.findById(sectionId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditSection", sectionId)));

        sectionService.moveSection(sectionId, newParentId, ctx.getTenantId());

        AuditSection moved = sectionRepository.findById(sectionId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditSection", sectionId));

        log.info("[AUDIT-LIBRARY] Section moved | id={} → newParentId={}", sectionId, newParentId);
        return ResponseEntity.ok(ApiResponse.success(buildSectionMap(moved)));
    }

    @DeleteMapping("/sections/{sectionId}")
    @Transactional
    @Operation(summary = "Delete section and entire subtree — removes control and template mappings")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteSection(
            @PathVariable Long sectionId) {

        boolean isSystem = utilityService.isSystemUser();
        var ctx          = utilityService.getLoggedInDataContext();

        AuditSection section = sectionRepository.findById(sectionId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditSection", sectionId));

        if (!isSystem && !Objects.equals(section.getTenantId(), ctx.getTenantId()))
            throw new BusinessException("SECTION_ACCESS_DENIED",
                    "You can only delete sections belonging to your organisation");

        List<AuditSection> subtree = sectionRepository.findSubtree(section.getPath());
        Set<Long> subtreeIds = new HashSet<>();
        subtree.forEach(s -> subtreeIds.add(s.getId()));

        subtreeIds.forEach(id ->
                sectionControlMappingRepository.findBySectionIdOrderByOrderNoAsc(id)
                        .forEach(sectionControlMappingRepository::delete));

        templateSectionMappingRepository.findAll().stream()
                .filter(m -> subtreeIds.contains(m.getSectionId()))
                .forEach(templateSectionMappingRepository::delete);

        subtree.stream()
                .sorted(Comparator.comparingInt(AuditSection::getDepth).reversed())
                .forEach(sectionRepository::delete);

        log.info("[AUDIT-LIBRARY] Section deleted | id={} | subtree={}", sectionId, subtree.size());
        return ResponseEntity.ok(ApiResponse.success(Map.of("deleted", sectionId, "subtreeSize", subtree.size())));
    }

    // ── Section ↔ Control mappings ───────────────────────────────────────────

    @GetMapping("/sections/{sectionId}/controls")
    @Operation(summary = "List controls mapped to a section")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listSectionControls(
            @PathVariable Long sectionId) {

        List<Map<String, Object>> result =
                sectionControlMappingRepository.findBySectionIdOrderByOrderNoAsc(sectionId)
                        .stream().map(m -> buildMappingMap(m, sectionId)).toList();
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/sections/{sectionId}/controls/{controlId}")
    @Operation(summary = "Map a control into a section — weight and mandatory stored on the mapping")
    public ResponseEntity<ApiResponse<Map<String, Object>>> addControlToSection(
            @PathVariable Long sectionId,
            @PathVariable Long controlId,
            @RequestParam(defaultValue = "1.0")   Double  weight,
            @RequestParam(defaultValue = "false")  boolean mandatory,
            @RequestParam(required = false)        Integer orderNo) {

        AuditSection targetSection = sectionRepository.findById(sectionId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditSection", sectionId));
        controlRepository.findById(controlId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditControl", controlId));

        // audit_section_control_mappings has no tenant column, so a row written
        // against a global section is visible to every tenant on the instance.
        // Until that column exists this is the only thing standing between one
        // org tailoring their programme and them silently editing everyone's.
        requireOwnedSection(targetSection);

        AuditSectionControlMapping mapping = sectionControlMappingRepository
                .findBySectionIdAndControlId(sectionId, controlId)
                .orElseGet(() -> AuditSectionControlMapping.builder()
                        .sectionId(sectionId).controlId(controlId).build());

        int nextOrder = orderNo != null ? orderNo :
                sectionControlMappingRepository.findBySectionIdOrderByOrderNoAsc(sectionId).size();
        mapping.setWeight(weight);
        mapping.setMandatory(mandatory);
        mapping.setOrderNo(nextOrder);
        sectionControlMappingRepository.save(mapping);

        log.info("[AUDIT-LIBRARY] Control mapped | sectionId={} controlId={}", sectionId, controlId);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "sectionId", sectionId, "controlId", controlId,
                "weight", weight, "mandatory", mandatory, "orderNo", nextOrder
        )));
    }

    @DeleteMapping("/sections")
    @Transactional
    @Operation(summary = "Bulk delete sections (and their full subtrees) by ID list")
    public ResponseEntity<ApiResponse<Map<String, Object>>> bulkDeleteSections(
            @RequestParam List<Long> ids) {
        boolean isSystem = utilityService.isSystemUser();
        var ctx          = utilityService.getLoggedInDataContext();
        int deleted = 0;
        for (Long id : ids) {
            sectionRepository.findById(id).ifPresent(section -> {
                if (!isSystem && !Objects.equals(section.getTenantId(), ctx.getTenantId())) return;
                List<AuditSection> subtree = sectionRepository.findSubtree(section.getPath());
                Set<Long> subtreeIds = new HashSet<>();
                subtree.forEach(s -> subtreeIds.add(s.getId()));
                subtreeIds.forEach(sid ->
                        sectionControlMappingRepository.findBySectionIdOrderByOrderNoAsc(sid)
                                .forEach(sectionControlMappingRepository::delete));
                templateSectionMappingRepository.findAll().stream()
                        .filter(m -> subtreeIds.contains(m.getSectionId()))
                        .forEach(templateSectionMappingRepository::delete);
                subtree.stream().sorted((a, b) -> b.getPath().compareTo(a.getPath()))
                        .forEach(sectionRepository::delete);
            });
            deleted++;
        }
        log.info("[AUDIT-LIBRARY] Bulk deleted {} sections (with subtrees)", deleted);
        return ResponseEntity.ok(ApiResponse.success(Map.of("deleted", deleted)));
    }

    @DeleteMapping("/sections/{sectionId}/controls/{controlId}")
    @Operation(summary = "Remove a control from a section — does NOT delete the control itself")
    public ResponseEntity<ApiResponse<Map<String, Object>>> removeControlFromSection(
            @PathVariable Long sectionId,
            @PathVariable Long controlId) {

        requireOwnedSection(sectionRepository.findById(sectionId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditSection", sectionId)));

        sectionControlMappingRepository
                .findBySectionIdAndControlId(sectionId, controlId)
                .orElseThrow(() -> new BusinessException("MAPPING_NOT_FOUND",
                        "Control " + controlId + " is not mapped to section " + sectionId));

        sectionControlMappingRepository.deleteBySectionIdAndControlId(sectionId, controlId);

        log.info("[AUDIT-LIBRARY] Control unmapped | sectionId={} controlId={}", sectionId, controlId);
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("sectionId", sectionId, "controlId", controlId, "removed", true)));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEMPLATES
    // ══════════════════════════════════════════════════════════════════════════

    @GetMapping("/templates")
    @Operation(summary = "List templates — Platform Admin sees all; org users see PUBLISHED only")
    public ResponseEntity<ApiResponse<PaginatedResponse<Map<String, Object>>>> listTemplates(
            @RequestParam Map<String, String> allParams) {

        boolean isSystem = utilityService.isSystemUser();
        var ctx          = utilityService.getLoggedInDataContext();
        Long tid         = ctx.getTenantId();

        return ResponseEntity.ok(ApiResponse.success(
                auditReferenceListCacheService.listTemplates(
                        isSystem, tid, utilityService.getpageDetails(allParams), allParams)));
    }

    @GetMapping("/templates/{templateId}")
    @Operation(summary = "Get a template with its mapped root sections list")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getTemplate(
            @PathVariable Long templateId) {

        AuditTemplate template = templateRepository.findById(templateId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditTemplate", templateId));

        Map<String, Object> result = buildTemplateMap(template);

        List<AuditTemplateSectionMapping> sectionMappings =
                templateSectionMappingRepository.findByTemplateIdOrderByOrderNoAsc(templateId);

        // Bulk-load all mapped root sections in one query
        Set<Long> sectionIds = sectionMappings.stream()
                .map(AuditTemplateSectionMapping::getSectionId)
                .collect(Collectors.toSet());
        Map<Long, AuditSection> sectionsById = sectionRepository.findAllById(sectionIds).stream()
                .collect(Collectors.toMap(AuditSection::getId, s -> s));

        List<Map<String, Object>> rootSections = sectionMappings.stream().map(m -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("mappingId", m.getId());
            row.put("sectionId", m.getSectionId());
            row.put("orderNo",   m.getOrderNo());
            AuditSection s = sectionsById.get(m.getSectionId());
            if (s != null) {
                row.put("name",        s.getName());
                row.put("sectionCode", s.getSectionCode());
                row.put("depth",       s.getDepth());
            }
            return row;
        }).toList();

        result.put("rootSections", rootSections);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/templates/{templateId}/full")
    @Operation(summary = "Full recursive tree — template + sections (all depths) + controls")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getFullTemplate(
            @PathVariable Long templateId) {

        AuditTemplate template = templateRepository.findById(templateId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditTemplate", templateId));

        List<Long> rootIds = templateSectionMappingRepository
                .findByTemplateIdOrderByOrderNoAsc(templateId)
                .stream().map(AuditTemplateSectionMapping::getSectionId).toList();

        // Build the full section tree (optimised: one path-LIKE query per root)
        List<AuditSectionService.SectionNode> nodes = sectionService.getTemplateTree(rootIds, null);

        // ── PRE-LOAD all control mappings and controls in 2 bulk queries ──────
        // Collect every section ID in the whole tree so we can fetch in one shot.
        Set<Long> allSectionIds = new HashSet<>();
        collectSectionIds(nodes, allSectionIds);

        // 1 query: all mappings for every section in the tree
        List<AuditSectionControlMapping> allMappings = allSectionIds.isEmpty()
                ? List.of()
                : sectionControlMappingRepository.findBySectionIdInOrderBySectionIdAscOrderNoAsc(allSectionIds);

        // 1 query: all controls referenced by those mappings
        Set<Long> controlIds = allMappings.stream()
                .map(AuditSectionControlMapping::getControlId)
                .collect(java.util.stream.Collectors.toSet());
        Map<Long, AuditControl> controlById = controlIds.isEmpty()
                ? Map.of()
                : controlRepository.findAllById(controlIds).stream()
                  .collect(java.util.stream.Collectors.toMap(AuditControl::getId, c -> c));

        // Group mappings by sectionId for O(1) lookup during tree serialisation
        Map<Long, List<AuditSectionControlMapping>> mappingsBySectionId = allMappings.stream()
                .collect(java.util.stream.Collectors.groupingBy(AuditSectionControlMapping::getSectionId));

        // Serialise tree using the pre-loaded maps — zero extra DB queries
        List<Map<String, Object>> tree = nodes.stream()
                .map(n -> buildSectionNodeMap(n, mappingsBySectionId, controlById))
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("template",     buildTemplateMap(template));
        result.put("rootSections", tree);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /** Recursively collect all section IDs in a tree for bulk querying. */
    private void collectSectionIds(List<AuditSectionService.SectionNode> nodes, Set<Long> ids) {
        for (AuditSectionService.SectionNode node : nodes) {
            ids.add(node.section().getId());
            collectSectionIds(node.children(), ids);
        }
    }

    @PostMapping("/templates")
    @Operation(summary = "Create a DRAFT template — Platform Admin → global, org → private")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createTemplate(
            @Valid @RequestBody AuditTemplateRequest req) {

        boolean isSystem = utilityService.isSystemUser();
        var ctx          = utilityService.getLoggedInDataContext();
        Long tenantId    = isSystem ? null : ctx.getTenantId();

        String effectiveName = req.getEffectiveName();
        if (effectiveName == null || effectiveName.isBlank())
            throw new BusinessException("VALIDATION_ERROR", "Template name is required");

        AuditTemplate template = AuditTemplate.builder()
                .templateName(effectiveName)  // primary — maps to template_name column
                .name(effectiveName)          // kept in sync for any legacy reads
                .description(req.getDescription())
                .frameworkRef(req.getFrameworkRef())
                .auditType(req.getAuditType() != null ? req.getAuditType() : AuditTemplate.AuditType.INTERNAL)
                .version(1)
                .status("DRAFT")
                .tenantId(tenantId)
                .createdBy(ctx.getId())
                .build();

        templateRepository.save(template);
        log.info("[AUDIT-LIBRARY] Template created | id={} | name=\"{}\" | tenantId={}",
                template.getId(), template.getName(), tenantId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(buildTemplateMap(template)));
    }

    @PutMapping("/templates/{templateId}")
    @Operation(summary = "Update template — DRAFT only (Platform Admin can also update PUBLISHED)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateTemplate(
            @PathVariable Long templateId,
            @Valid @RequestBody AuditTemplateRequest req) {

        boolean isSystem = utilityService.isSystemUser();
        var ctx          = utilityService.getLoggedInDataContext();

        AuditTemplate template = templateRepository.findById(templateId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditTemplate", templateId));

        if ("PUBLISHED".equals(template.getStatus()) && !isSystem)
            throw new BusinessException("TEMPLATE_PUBLISHED",
                    "Only Platform Admin can edit a published template. Unpublish it first.");

        if (!isSystem && !Objects.equals(template.getTenantId(), ctx.getTenantId()))
            throw new BusinessException("TEMPLATE_ACCESS_DENIED",
                    "You can only edit templates belonging to your organisation");

        String updName = req.getEffectiveName();
        if (updName != null && !updName.isBlank()) {
            template.setTemplateName(updName);  // primary
            template.setName(updName);           // keep in sync
        }
        if (req.getDescription()  != null) template.setDescription(req.getDescription());
        if (req.getFrameworkRef() != null) template.setFrameworkRef(req.getFrameworkRef());
        if (req.getAuditType()    != null) template.setAuditType(req.getAuditType());

        templateRepository.save(template);
        log.info("[AUDIT-LIBRARY] Template updated | id={}", templateId);
        return ResponseEntity.ok(ApiResponse.success(buildTemplateMap(template)));
    }

    @DeleteMapping("/templates/{templateId}")
    @Transactional
    @Operation(summary = "Delete template — removes section mappings; Platform Admin can delete PUBLISHED")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteTemplate(
            @PathVariable Long templateId) {

        boolean isSystem = utilityService.isSystemUser();
        var ctx          = utilityService.getLoggedInDataContext();

        AuditTemplate template = templateRepository.findById(templateId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditTemplate", templateId));

        if ("PUBLISHED".equals(template.getStatus()) && !isSystem)
            throw new BusinessException("TEMPLATE_PUBLISHED",
                    "Cannot delete a published template. Unpublish it first.");

        if (!isSystem && !Objects.equals(template.getTenantId(), ctx.getTenantId()))
            throw new BusinessException("TEMPLATE_ACCESS_DENIED",
                    "You can only delete templates belonging to your organisation");

        templateSectionMappingRepository.deleteByTemplateId(templateId);
        // Remove links from project plans — FK on audit_project_templates.template_id
        projectTemplateRepository.findAll().stream()
                .filter(pt -> templateId.equals(pt.getTemplateId()))
                .forEach(projectTemplateRepository::delete);
        templateRepository.delete(template);

        log.info("[AUDIT-LIBRARY] Template deleted | id={} | name=\"{}\"", templateId, template.getName());
        return ResponseEntity.ok(ApiResponse.success(Map.of("deleted", templateId)));
    }

    @DeleteMapping("/templates")
    @Transactional
    @Operation(summary = "Bulk delete templates by ID list — removes section mappings")
    public ResponseEntity<ApiResponse<Map<String, Object>>> bulkDeleteTemplates(
            @RequestParam List<Long> ids) {
        boolean isSystem = utilityService.isSystemUser();
        var ctx          = utilityService.getLoggedInDataContext();
        int deleted = 0;
        for (Long id : ids) {
            templateRepository.findById(id).ifPresent(t -> {
                if (!isSystem && !Objects.equals(t.getTenantId(), ctx.getTenantId())) return;
                if ("PUBLISHED".equals(t.getStatus()) && !isSystem) return; // skip published for non-system
                templateSectionMappingRepository.deleteByTemplateId(id);
                projectTemplateRepository.findAll().stream()
                        .filter(pt -> id.equals(pt.getTemplateId()))
                        .forEach(projectTemplateRepository::delete);
                templateRepository.delete(t);
            });
            deleted++;
        }
        log.info("[AUDIT-LIBRARY] Bulk deleted {} templates", deleted);
        return ResponseEntity.ok(ApiResponse.success(Map.of("deleted", deleted)));
    }

    @PostMapping("/templates/{templateId}/publish")
    @Operation(summary = "Publish — makes template available for project planning and engagement creation")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publishTemplate(
            @PathVariable Long templateId) {

        AuditTemplate template = templateRepository.findById(templateId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditTemplate", templateId));

        requireOwnedTemplate(template);

        if ("PUBLISHED".equals(template.getStatus()))
            throw new BusinessException("TEMPLATE_ALREADY_PUBLISHED", "Template is already published");

        if (templateSectionMappingRepository.findByTemplateIdOrderByOrderNoAsc(templateId).isEmpty())
            throw new BusinessException("TEMPLATE_EMPTY",
                    "Cannot publish an empty template. Add at least one root section first.");

        template.setStatus("PUBLISHED");
        template.setPublishedAt(LocalDateTime.now());
        template.setUnpublishedAt(null);
        templateRepository.save(template);

        log.info("[AUDIT-LIBRARY] Template published | id={}", templateId);
        return ResponseEntity.ok(ApiResponse.success(buildTemplateMap(template)));
    }

    @PostMapping("/templates/{templateId}/unpublish")
    @Operation(summary = "Unpublish — reverts to DRAFT; Platform Admin only")
    public ResponseEntity<ApiResponse<Map<String, Object>>> unpublishTemplate(
            @PathVariable Long templateId) {

        if (!utilityService.isSystemUser())
            throw new BusinessException("FORBIDDEN", "Only Platform Admin can unpublish templates");

        AuditTemplate template = templateRepository.findById(templateId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditTemplate", templateId));

        if ("DRAFT".equals(template.getStatus()))
            throw new BusinessException("TEMPLATE_ALREADY_DRAFT", "Template is already in DRAFT");

        template.setStatus("DRAFT");
        template.setUnpublishedAt(LocalDateTime.now());
        templateRepository.save(template);

        log.info("[AUDIT-LIBRARY] Template unpublished | id={}", templateId);
        return ResponseEntity.ok(ApiResponse.success(buildTemplateMap(template)));
    }

    @PostMapping("/templates/{templateId}/sections/{sectionId}")
    @Operation(summary = "Map a root section into a template — subtree included implicitly")
    public ResponseEntity<ApiResponse<Map<String, Object>>> addSectionToTemplate(
            @PathVariable Long templateId,
            @PathVariable Long sectionId,
            @RequestParam(required = false) Integer orderNo) {

        AuditTemplate template = templateRepository.findById(templateId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditTemplate", templateId));

        // Ownership, not just status. The PUBLISHED check below is a workflow
        // guard — it stops edits to a live template, and it happens to block most
        // global ones because global templates are normally PUBLISHED. That is
        // protection by coincidence: a global template sitting in DRAFT was
        // writable by any org admin, and audit_template_section_mappings has no
        // tenant column, so the added row would have been visible to every tenant.
        requireOwnedTemplate(template);

        if ("PUBLISHED".equals(template.getStatus()) && !utilityService.isSystemUser())
            throw new BusinessException("TEMPLATE_PUBLISHED",
                    "Cannot modify a published template. Unpublish it first.");

        AuditSection section = sectionRepository.findById(sectionId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditSection", sectionId));

        if (section.getParentId() != null)
            throw new BusinessException("SECTION_NOT_ROOT",
                    "Only root sections (parentId=null) can be mapped to templates.");

        AuditTemplateSectionMapping mapping = templateSectionMappingRepository
                .findByTemplateIdAndSectionId(templateId, sectionId)
                .orElseGet(() -> AuditTemplateSectionMapping.builder()
                        .templateId(templateId).sectionId(sectionId).build());

        int nextOrder = orderNo != null ? orderNo :
                templateSectionMappingRepository.findByTemplateIdOrderByOrderNoAsc(templateId).size();
        mapping.setOrderNo(nextOrder);
        templateSectionMappingRepository.save(mapping);

        log.info("[AUDIT-LIBRARY] Section → template | templateId={} sectionId={}", templateId, sectionId);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "templateId", templateId, "sectionId", sectionId, "orderNo", nextOrder)));
    }

    @DeleteMapping("/templates/{templateId}/sections/{sectionId}")
    @Operation(summary = "Remove a section from a template — does NOT delete the section")
    public ResponseEntity<ApiResponse<Map<String, Object>>> removeSectionFromTemplate(
            @PathVariable Long templateId,
            @PathVariable Long sectionId) {

        AuditTemplate template = templateRepository.findById(templateId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditTemplate", templateId));

        // Ownership, not just status. The PUBLISHED check below is a workflow
        // guard — it stops edits to a live template, and it happens to block most
        // global ones because global templates are normally PUBLISHED. That is
        // protection by coincidence: a global template sitting in DRAFT was
        // writable by any org admin, and audit_template_section_mappings has no
        // tenant column, so the added row would have been visible to every tenant.
        requireOwnedTemplate(template);

        if ("PUBLISHED".equals(template.getStatus()) && !utilityService.isSystemUser())
            throw new BusinessException("TEMPLATE_PUBLISHED",
                    "Cannot modify a published template. Unpublish it first.");

        templateSectionMappingRepository.deleteByTemplateIdAndSectionId(templateId, sectionId);

        log.info("[AUDIT-LIBRARY] Section ← template removed | templateId={} sectionId={}",
                templateId, sectionId);
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("templateId", templateId, "sectionId", sectionId, "removed", true)));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // GLOBAL PROJECTS (Platform Admin pre-builds programmes for orgs to adopt)
    //
    // These are AuditProject entities with tenantId=null.
    // Any org can see them and add templates to their own instance of the project.
    // Org-scoped projects live in AuditEngagementController at /v1/audit/projects.
    // ══════════════════════════════════════════════════════════════════════════

    @GetMapping("/projects")
    @Operation(summary = "List global projects — Platform Admin sees all; org users see global only")
    public ResponseEntity<ApiResponse<PaginatedResponse<Map<String, Object>>>> listGlobalProjects(
            @RequestParam Map<String, String> allParams) {

        return ResponseEntity.ok(ApiResponse.success(dbRepository.findAll(
                AuditProject.class,
                utilityService.getpageDetails(allParams),
                (cb, root) -> {
                    List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
                    // This endpoint always shows only global (tenantId=null) projects
                    predicates.add(cb.isNull(root.get("tenantId")));
                    if (allParams.containsKey("status"))
                        predicates.add(cb.equal(root.get("status"),
                                AuditProject.Status.valueOf(allParams.get("status").toUpperCase())));
                    return predicates;
                },
                (cb, root) -> Map.of(
                        "name",      root.get("name"),
                        "status",    root.get("status"),
                        "createdAt", root.get("createdAt")
                ),
                this::buildProjectMap
        )));
    }

    @GetMapping("/projects/{projectId}")
    @Operation(summary = "Get a global project with its planned templates")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getGlobalProject(
            @PathVariable Long projectId) {

        AuditProject project = projectRepository.findById(projectId)
                .filter(p -> p.getTenantId() == null)
                .orElseThrow(() -> new ResourceNotFoundException("AuditProject (global)", projectId));

        Map<String, Object> result = buildProjectMap(project);

        // Attach planned templates — bulk-load to avoid N+1
        List<AuditProjectTemplate> projectTemplates =
                projectTemplateRepository.findByProjectIdOrderByOrderNoAsc(projectId);

        Set<Long> templateIds = projectTemplates.stream()
                .map(AuditProjectTemplate::getTemplateId)
                .collect(Collectors.toSet());
        Map<Long, AuditTemplate> templatesById = templateRepository.findAllById(templateIds).stream()
                .collect(Collectors.toMap(AuditTemplate::getId, t -> t));

        List<Map<String, Object>> templates = projectTemplates.stream().map(pt -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id",         pt.getId());
            row.put("templateId", pt.getTemplateId());
            row.put("orderNo",    pt.getOrderNo());
            row.put("note",       pt.getNote());
            AuditTemplate t = templatesById.get(pt.getTemplateId());
            if (t != null) {
                row.put("templateName",   t.getName());
                row.put("templateStatus", t.getStatus());
                row.put("frameworkRef",   t.getFrameworkRef());
                row.put("auditType",      t.getAuditType());
            }
            return row;
        }).toList();

        result.put("plannedTemplates", templates);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/projects")
    @Operation(summary = "Create a global audit project — Platform Admin only (tenantId=null)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createGlobalProject(
            @Valid @RequestBody AuditProjectRequest req) {

        if (!utilityService.isSystemUser())
            throw new BusinessException("FORBIDDEN",
                    "Only Platform Admin can create global projects. " +
                            "Org users create their own projects at POST /v1/audit/projects");

        var ctx = utilityService.getLoggedInDataContext();

        // Use MAX on ref to get a globally unique sequence — avoids collisions from count-based approach
        int year = java.time.LocalDateTime.now().getYear();
        String prefix = "PROJ-" + year + "-";
        long seq = projectRepository.findAll().stream()
                .map(p -> p.getProjectRef())
                .filter(r -> r != null && r.startsWith(prefix))
                .mapToLong(r -> { try { return Long.parseLong(r.substring(prefix.length())); } catch (Exception e) { return 0L; } })
                .max().orElse(0L) + 1;
        String ref = String.format("PROJ-%d-%04d", year, seq);

        AuditProject project = AuditProject.builder()
                .projectRef(ref)
                .tenantId(null)             // global — visible to all orgs
                .name(req.getName())
                .description(req.getDescription())
                .ownerId(req.getOwnerId() != null ? req.getOwnerId() : ctx.getId())
                .createdBy(ctx.getId())
                .plannedStart(req.getPlannedStart())
                .plannedEnd(req.getPlannedEnd())
                .status(AuditProject.Status.PLANNING)
                .build();

        projectRepository.save(project);
        log.info("[AUDIT-LIBRARY] Global project created | ref={} | name=\"{}\"",
                ref, project.getName());

        Map<String, Object> result = buildProjectMap(project);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(result));
    }

    @PutMapping("/projects/{projectId}")
    @Operation(summary = "Update a global project — Platform Admin only")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateGlobalProject(
            @PathVariable Long projectId,
            @Valid @RequestBody AuditProjectRequest req) {

        if (!utilityService.isSystemUser())
            throw new BusinessException("FORBIDDEN", "Only Platform Admin can update global projects");

        AuditProject project = projectRepository.findById(projectId)
                .filter(p -> p.getTenantId() == null)
                .orElseThrow(() -> new ResourceNotFoundException("AuditProject (global)", projectId));

        if (req.getName()        != null) project.setName(req.getName());
        if (req.getDescription() != null) project.setDescription(req.getDescription());
        if (req.getOwnerId()     != null) project.setOwnerId(req.getOwnerId());
        if (req.getPlannedStart()!= null) project.setPlannedStart(req.getPlannedStart());
        if (req.getPlannedEnd()  != null) project.setPlannedEnd(req.getPlannedEnd());
        projectRepository.save(project);

        log.info("[AUDIT-LIBRARY] Global project updated | id={}", projectId);
        return ResponseEntity.ok(ApiResponse.success(buildProjectMap(project)));
    }

    @DeleteMapping("/projects/{projectId}")
    @Transactional
    @Operation(summary = "Delete a global project — Platform Admin only; blocked if any org has started it")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteGlobalProject(
            @PathVariable Long projectId) {

        if (!utilityService.isSystemUser())
            throw new BusinessException("FORBIDDEN", "Only Platform Admin can delete global projects");

        AuditProject project = projectRepository.findById(projectId)
                .filter(p -> p.getTenantId() == null)
                .orElseThrow(() -> new ResourceNotFoundException("AuditProject (global)", projectId));

        // Block deletion if any org has already snapshotted (started) this project
        if (projectInstanceRepository.existsByOriginalProjectId(projectId))
            throw new BusinessException("PROJECT_IN_USE",
                    "This global project has been started by one or more orgs and cannot be deleted. " +
                            "Archive it instead.");

        // Remove all template plans for this project
        projectTemplateRepository.findByProjectIdOrderByOrderNoAsc(projectId)
                .forEach(projectTemplateRepository::delete);

        projectRepository.delete(project);
        log.info("[AUDIT-LIBRARY] Global project deleted | id={} | name=\"{}\"",
                projectId, project.getName());
        return ResponseEntity.ok(ApiResponse.success(Map.of("deleted", projectId)));
    }

    // ── Global project ↔ Template planning mappings ──────────────────────────

    @PostMapping("/projects/{projectId}/templates/{templateId}")
    @Operation(summary = "Add a PUBLISHED template to a global project plan — Platform Admin only")
    public ResponseEntity<ApiResponse<Map<String, Object>>> addTemplateToGlobalProject(
            @PathVariable Long projectId,
            @PathVariable Long templateId,
            @RequestParam(required = false) String  note,
            @RequestParam(required = false) Integer orderNo) {

        if (!utilityService.isSystemUser())
            throw new BusinessException("FORBIDDEN",
                    "Only Platform Admin can pre-map templates to global projects. " +
                            "Orgs add templates to their own projects at POST /v1/audit/projects/{id}/templates/{id}");

        projectRepository.findById(projectId)
                .filter(p -> p.getTenantId() == null)
                .orElseThrow(() -> new ResourceNotFoundException("AuditProject (global)", projectId));

        AuditTemplate template = templateRepository.findById(templateId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditTemplate", templateId));

        if (!"PUBLISHED".equals(template.getStatus()))
            throw new BusinessException("TEMPLATE_NOT_PUBLISHED",
                    "Only published templates can be added to a project plan");

        if (projectTemplateRepository.existsByProjectIdAndTemplateId(projectId, templateId))
            throw new BusinessException("TEMPLATE_ALREADY_PLANNED",
                    "Template is already in this project's plan");

        int nextOrder = orderNo != null ? orderNo :
                projectTemplateRepository.findByProjectIdOrderByOrderNoAsc(projectId).size();

        AuditProjectTemplate pt = projectTemplateRepository.save(
                AuditProjectTemplate.builder()
                        .projectId(projectId)
                        .templateId(templateId)
                        .orderNo(nextOrder)
                        .note(note)
                        .build()
        );

        log.info("[AUDIT-LIBRARY] Template → global project | projectId={} templateId={}",
                projectId, templateId);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "id",         pt.getId(),
                "projectId",  projectId,
                "templateId", templateId,
                "orderNo",    nextOrder,
                "note",       note != null ? note : ""
        )));
    }

    @DeleteMapping("/projects/{projectId}/templates/{templateId}")
    @Operation(summary = "Remove a template from a global project plan — Platform Admin only")
    public ResponseEntity<ApiResponse<Map<String, Object>>> removeTemplateFromGlobalProject(
            @PathVariable Long projectId,
            @PathVariable Long templateId) {

        if (!utilityService.isSystemUser())
            throw new BusinessException("FORBIDDEN",
                    "Only Platform Admin can modify global project plans");

        projectRepository.findById(projectId)
                .filter(p -> p.getTenantId() == null)
                .orElseThrow(() -> new ResourceNotFoundException("AuditProject (global)", projectId));

        AuditProjectTemplate pt = projectTemplateRepository
                .findByProjectIdAndTemplateId(projectId, templateId)
                .orElseThrow(() -> new BusinessException("PLAN_NOT_FOUND",
                        "Template is not in this project's plan"));

        if (pt.getEngagementId() != null)
            throw new BusinessException("PLAN_ALREADY_STARTED",
                    "An org has already started an engagement from this plan entry. " +
                            "It cannot be removed — the engagement continues independently.");

        projectTemplateRepository.deleteByProjectIdAndTemplateId(projectId, templateId);
        log.info("[AUDIT-LIBRARY] Template ← global project removed | projectId={} templateId={}",
                projectId, templateId);
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("projectId", projectId, "templateId", templateId, "removed", true)));
    }

    // ── CSV Import ────────────────────────────────────────────────────────────

    @PostMapping(value = "/templates/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Import template + sections + controls from CSV — TEMPLATE/SECTION/CONTROL rows, idempotent")
    public ResponseEntity<ApiResponse<CsvImportResult>> importCsv(
            @RequestParam("file") MultipartFile file) {

        if (file.isEmpty() || !Objects.requireNonNull(file.getOriginalFilename()).endsWith(".csv"))
            throw new BusinessException("INVALID_FILE", "Only .csv files are supported");

        boolean isSystem = utilityService.isSystemUser();
        var ctx          = utilityService.getLoggedInDataContext();
        Long tenantId    = isSystem ? null : ctx.getTenantId();

        log.info("[AUDIT-LIBRARY] CSV import | file={} | tenantId={}", file.getOriginalFilename(), tenantId);
        CsvImportResult result = csvImportService.importLibraryCsv(file, tenantId, ctx.getId());
        log.info("[AUDIT-LIBRARY] CSV import complete | {}", result.getSummary());

        if (result.isFatalError())
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("IMPORT_FAILED", result.getSummary()));

        HttpStatus status = result.hasErrors() ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.success(result));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Private helpers
    // ══════════════════════════════════════════════════════════════════════════

    // ══════════════════════════════════════════════════════════════════════
    // GLOBAL STRUCTURE IS READ-ONLY TO TENANTS
    //
    // Templates, sections and their control mappings are framework structure,
    // authored by the platform. Most handlers here already check ownership;
    // these helpers cover the ones that did not, where an org admin could
    // reshape the shared library for every tenant on the instance.
    // ══════════════════════════════════════════════════════════════════════

    private void requireOwnedSection(AuditSection section) {
        if (utilityService.isSystemUser()) return;
        Long caller = utilityService.getLoggedInDataContext().getTenantId();
        if (java.util.Objects.equals(section.getTenantId(), caller)) return;

        log.warn("[AUDIT-LIBRARY] Refused write to section {} (tenant {}) by tenant {}",
                section.getId(), section.getTenantId(), caller);
        throw new BusinessException("SECTION_ACCESS_DENIED",
                section.getTenantId() == null
                        ? "This section belongs to the global library and cannot be modified"
                        : "You can only modify sections belonging to your organisation",
                HttpStatus.FORBIDDEN);
    }

    private void requireOwnedTemplate(AuditTemplate template) {
        if (utilityService.isSystemUser()) return;
        Long caller = utilityService.getLoggedInDataContext().getTenantId();
        if (java.util.Objects.equals(template.getTenantId(), caller)) return;

        log.warn("[AUDIT-LIBRARY] Refused write to template {} (tenant {}) by tenant {}",
                template.getId(), template.getTenantId(), caller);
        throw new BusinessException("TEMPLATE_ACCESS_DENIED",
                template.getTenantId() == null
                        ? "This template belongs to the global library and cannot be modified"
                        : "You can only modify templates belonging to your organisation",
                HttpStatus.FORBIDDEN);
    }
    /** Empty textarea posts "" — stored as NULL so blank and absent behave alike. */
    private static String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    private Map<String, Object> buildControlMap(AuditControl c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",           c.getId());
        m.put("name",         c.getName());
        m.put("description",  c.getDescription());
        m.put("controlCode",  c.getControlCode());
        m.put("frameworkRef", c.getFrameworkRef());
        m.put("testType",     c.getTestType() != null ? c.getTestType().name() : null);
        m.put("controlTag",   c.getControlTag());
        m.put("evidenceGuidance", c.getEvidenceGuidance());
        m.put("tenantId",     c.getTenantId());
        // origin/editable let the UI show a Global badge and hide Edit/Delete on rows
        // the server would refuse anyway. The guards are the boundary; this exists so
        // the interface stops offering actions that end in a 403.
        boolean globalC = c.getTenantId() == null;
        m.put("origin",   globalC ? "GLOBAL" : "ORG");
        m.put("editable", !globalC || utilityService.isSystemUser());
        m.put("createdAt",    c.getCreatedAt());
        return m;
    }

    private Map<String, Object> buildSectionMap(AuditSection s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",           s.getId());
        m.put("name",         s.getName());
        m.put("description",  s.getDescription());
        m.put("sectionCode",  s.getSectionCode());
        m.put("frameworkRef", s.getFrameworkRef());
        m.put("parentId",     s.getParentId());
        m.put("path",         s.getPath());
        m.put("depth",        s.getDepth());
        m.put("orderNo",      s.getOrderNo());
        m.put("tenantId",     s.getTenantId());
        return m;
    }

    private Map<String, Object> buildTemplateMap(AuditTemplate t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",           t.getId());
        // Return both for frontend compatibility — prefer templateName
        String displayName = t.getTemplateName() != null ? t.getTemplateName() : t.getName();
        m.put("name",         displayName);
        m.put("templateName", displayName);
        m.put("description",  t.getDescription());
        m.put("frameworkRef", t.getFrameworkRef());
        m.put("auditType",    t.getAuditType() != null ? t.getAuditType().name() : null);
        m.put("version",      t.getVersion());
        m.put("status",       t.getStatus());
        m.put("publishedAt",  t.getPublishedAt());
        m.put("tenantId",     t.getTenantId());
        m.put("createdAt",    t.getCreatedAt());
        return m;
    }

    private Map<String, Object> buildProjectMap(AuditProject p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",          p.getId());
        m.put("projectRef",  p.getProjectRef());
        m.put("name",        p.getName());
        m.put("description", p.getDescription());
        m.put("status",      p.getStatus() != null ? p.getStatus().name() : null);
        m.put("ownerId",     p.getOwnerId());
        m.put("tenantId",    p.getTenantId());
        m.put("global",      p.getTenantId() == null);
        m.put("plannedStart",p.getPlannedStart());
        m.put("plannedEnd",  p.getPlannedEnd());
        m.put("createdAt",   p.getCreatedAt());
        return m;
    }

    private Map<String, Object> buildMappingMap(AuditSectionControlMapping m, Long sectionId) {
        // Used by listSectionControls (per-section, on-demand click). Fine to query per-mapping here.
        Map<String, Object> row = buildMappingMapBase(m, sectionId);
        controlRepository.findById(m.getControlId()).ifPresent(c -> populateControlFields(row, c));
        return row;
    }

    /** Overload used by getFullTemplate — control already pre-loaded, zero extra queries. */
    private Map<String, Object> buildMappingMap(AuditSectionControlMapping m, Long sectionId,
                                                Map<Long, AuditControl> controlById) {
        Map<String, Object> row = buildMappingMapBase(m, sectionId);
        AuditControl c = controlById.get(m.getControlId());
        if (c != null) populateControlFields(row, c);
        return row;
    }

    private Map<String, Object> buildMappingMapBase(AuditSectionControlMapping m, Long sectionId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("mappingId", m.getId());
        row.put("controlId", m.getControlId());
        row.put("sectionId", sectionId);
        row.put("orderNo",   m.getOrderNo());
        row.put("weight",    m.getWeight());
        row.put("mandatory", m.isMandatory());
        return row;
    }

    private void populateControlFields(Map<String, Object> row, AuditControl c) {
        row.put("name",         c.getName());
        row.put("description",  c.getDescription());
        row.put("controlCode",  c.getControlCode());
        row.put("testType",     c.getTestType());
        row.put("controlTag",   c.getControlTag());
        row.put("frameworkRef", c.getFrameworkRef());
        // The template builder's Edit Control modal hydrates from this map, not
        // from buildControlMap — so a field added only there saves correctly and
        // then comes back empty on reopen. Both serializers have to agree.
        row.put("evidenceGuidance", c.getEvidenceGuidance());
    }

    /** Original — still used by any callers that need per-node control loading. */
    private Map<String, Object> buildSectionNodeMap(AuditSectionService.SectionNode node) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("section",  buildSectionMap(node.section()));
        m.put("controls", sectionControlMappingRepository
                .findBySectionIdOrderByOrderNoAsc(node.section().getId())
                .stream().map(mapping -> buildMappingMap(mapping, node.section().getId()))
                .toList());
        m.put("children", node.children().stream().map(this::buildSectionNodeMap).toList());
        return m;
    }

    /**
     * Bulk-preloaded overload — called by getFullTemplate.
     * mappingsBySectionId and controlById are pre-loaded once for the whole tree,
     * so this method issues ZERO additional DB queries.
     */
    private Map<String, Object> buildSectionNodeMap(
            AuditSectionService.SectionNode node,
            Map<Long, List<AuditSectionControlMapping>> mappingsBySectionId,
            Map<Long, AuditControl> controlById) {

        Long sectionId = node.section().getId();
        List<AuditSectionControlMapping> mappings =
                mappingsBySectionId.getOrDefault(sectionId, List.of());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("section",  buildSectionMap(node.section()));
        m.put("controls", mappings.stream()
                .map(mapping -> buildMappingMap(mapping, sectionId, controlById))
                .toList());
        m.put("children", node.children().stream()
                .map(child -> buildSectionNodeMap(child, mappingsBySectionId, controlById))
                .toList());
        return m;
    }
    // ══════════════════════════════════════════════════════════════════════════
    // TESTS & POLICIES CSV IMPORT  (from INTEGRATION_NOTES.md)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Import TEST, POLICY, CONTROL_TEST_MAPPING, POLICY_CONTROL_MAPPING rows from CSV.
     * Extends the existing TEMPLATE/SECTION/CONTROL CSV format with new row types.
     * POST /v1/audit/library/tests-policies/import
     */
    // Class-level @RequestMapping is already "/v1/audit/library", so a path
    // starting "/library" registered at /v1/audit/library/LIBRARY/... and no
    // request ever reached it — Spring fell through to static-resource lookup:
    //   NoResourceFoundException: No static resource v1/audit/library/tests-policies/import
    // The javadoc above always documented the correct URL.
    @PostMapping("/tests-policies/import")
    @Operation(summary = "Import tests, policies, and their control mappings from extended CSV")
    public ResponseEntity<ApiResponse<CsvImportResult>> importTestsPoliciesCsv(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        var ctx = utilityService.getLoggedInDataContext();
        CsvImportResult result = csvImportExtension.importTestsPoliciesAndMappings(
                file, ctx.getTenantId(), ctx.getId());

        // A CSV import writes tests, policies AND their mappings in one pass, so
        // both cached lists are stale afterwards. This is the mutation most likely
        // to be forgotten, because it lives in a different controller from the
        // endpoints that serve those lists.
        libraryCache.evictLibraryLists();

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * Download a worked example CSV showing all row types including TEST, POLICY, and MAPPING rows.
     * GET /v1/audit/library/tests-policies/csv-example
     */
    // Same double-prefix as the import endpoint above.
    @GetMapping("/tests-policies/csv-example")
    @Operation(summary = "Download an example CSV showing all test/policy/mapping row types")
    public ResponseEntity<byte[]> downloadTestsPoliciesCsvExample() {
        byte[] bytes = AuditCsvImportExtension.EXAMPLE_CSV_CONTENT
                .getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header("Content-Disposition",
                        "attachment; filename=\"audit_tests_policies_example.csv\"")
                .header("Content-Type", "text/csv")
                .body(bytes);
    }


}