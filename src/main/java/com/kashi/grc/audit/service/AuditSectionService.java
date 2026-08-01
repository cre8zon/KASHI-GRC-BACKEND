package com.kashi.grc.audit.service;

import com.kashi.grc.audit.domain.*;
import com.kashi.grc.audit.repository.*;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * AuditSectionService — all tree operations for library sections and instance trees.
 *
 * ── LIBRARY TREE ─────────────────────────────────────────────────────────────
 * createSection()      — create a section at any depth, auto-computes path + depth
 * moveSection()        — move a section under a new parent, updates path for entire subtree
 * getFullTree()        — returns the full section tree rooted at a given section
 * getTemplateTree()    — returns the full tree for all root sections in a template
 *
 * ── INSTANCE TREE ────────────────────────────────────────────────────────────
 * snapshotSectionTree() — called by AuditEngagementService.snapshotTemplate()
 *                         Recursively snapshots the library tree into instance tree
 *                         Preserves parentInstanceId + path using instance IDs
 *
 * ── PATH FORMAT ──────────────────────────────────────────────────────────────
 * Library path:  "/" + id + "/"  for root,  "/parentId/.../id/" for children
 * Instance path: "/" + instanceId + "/"  for root,  "/parentInstId/.../instId/" for children
 *
 * Both use the same algorithm — just different ID spaces.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditSectionService {

    private final AuditSectionRepository            sectionRepository;
    private final AuditSectionInstanceRepository    sectionInstanceRepository;
    private final AuditControlRepository            controlRepository;
    private final AuditSectionControlMappingRepository controlMappingRepository;
    private final AuditControlInstanceRepository    controlInstanceRepository;
    private final com.kashi.grc.ucf.service.TagExpansionService tagExpansionService;

    // ══════════════════════════════════════════════════════════════════════
    // LIBRARY SECTION TREE
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Create a new section at any depth.
     * parentId=null → root section
     * parentId=X    → child of section X
     * Auto-computes path and depth from parent.
     */
    @Transactional
    public AuditSection createSection(String name, String description, String sectionCode,
                                      String frameworkRef, Integer orderNo,
                                      Long parentId, Long tenantId, Long createdBy) {
        AuditSection parent = null;
        String parentPath  = "/";
        int    depth       = 0;

        if (parentId != null) {
            parent = sectionRepository.findById(parentId)
                    .orElseThrow(() -> new ResourceNotFoundException("AuditSection", parentId));
            parentPath = parent.getPath();
            depth      = parent.getDepth() + 1;
        }

        // Save first to get the generated ID
        AuditSection section = sectionRepository.save(
                AuditSection.builder()
                        .name(name).description(description)
                        .sectionCode(sectionCode).frameworkRef(frameworkRef)
                        .parentId(parentId)
                        .orderNo(orderNo != null ? orderNo : nextOrderNo(parentId, tenantId))
                        .depth(depth)
                        .path("/PLACEHOLDER/")   // temp — updated below
                        .tenantId(tenantId).createdBy(createdBy)
                        .build()
        );

        // Build path using the real ID
        String path = parentPath.equals("/")
                ? "/" + section.getId() + "/"
                : parentPath + section.getId() + "/";

        section.setPath(path);
        return sectionRepository.save(section);
    }

    /**
     * Move a section (and its entire subtree) under a new parent.
     * Updates path for the moved node and all its descendants.
     */
    @Transactional
    public void moveSection(Long sectionId, Long newParentId, Long tenantId) {
        AuditSection section = sectionRepository.findById(sectionId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditSection", sectionId));

        String oldPath = section.getPath();

        // Compute new path and depth
        String newParentPath = "/";
        int    newDepth      = 0;
        if (newParentId != null) {
            AuditSection newParent = sectionRepository.findById(newParentId)
                    .orElseThrow(() -> new ResourceNotFoundException("AuditSection", newParentId));

            // Guard: cannot move into own subtree
            if (newParent.getPath().startsWith(oldPath))
                throw new BusinessException("INVALID_MOVE", "Cannot move section into its own subtree");

            newParentPath = newParent.getPath();
            newDepth      = newParent.getDepth() + 1;
        }

        String newPath = newParentPath + sectionId + "/";
        int depthDelta = newDepth - section.getDepth();

        section.setParentId(newParentId);
        section.setPath(newPath);
        section.setDepth(newDepth);
        sectionRepository.save(section);

        // Update all descendants — replace oldPath prefix with newPath
        List<AuditSection> descendants = sectionRepository.findAllDescendants(sectionId, oldPath);
        for (AuditSection desc : descendants) {
            desc.setPath(newPath + desc.getPath().substring(oldPath.length()));
            desc.setDepth(desc.getDepth() + depthDelta);
            sectionRepository.save(desc);
        }

        log.info("[AUDIT-SECTION] Moved sectionId={} → newParentId={} | descendants updated={}",
                sectionId, newParentId, descendants.size());
    }

    /**
     * Returns a structured tree map for a list of root section IDs.
     * Used by the template full-structure view (/templates/{id}/full).
     *
     * FIX: previously issued N+1 queries (findById + findAllDescendants per root).
     * Now loads ALL root sections in one findAllById call, then for each root
     * fetches its entire subtree with a single path-LIKE query — one query per root.
     * A template with N root sections does N+1 queries total (N subtree + 1 roots),
     * rather than 2N+1.  Controls are loaded by the caller via a single bulk query.
     *
     * Returns: List of root SectionNode, each with nested children recursively.
     */
    public List<SectionNode> getTemplateTree(List<Long> rootSectionIds, Long tenantId) {
        if (rootSectionIds.isEmpty()) return List.of();

        // Load ALL root sections in one query instead of N findById calls
        List<AuditSection> roots = sectionRepository.findAllById(rootSectionIds);

        // For each root, load its entire subtree in a single path-LIKE query.
        // Total: roots.size() queries — far fewer than 2*roots.size() previously.
        List<AuditSection> allSections = new ArrayList<>(roots);
        for (AuditSection root : roots) {
            // findAllDescendants uses path LIKE — one indexed query for the whole subtree
            allSections.addAll(sectionRepository.findAllDescendants(root.getId(), root.getPath()));
        }

        return buildTree(allSections, null, rootSectionIds);
    }

    // ══════════════════════════════════════════════════════════════════════
    // INSTANCE TREE — called during engagement template instantiation
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Recursively snapshots a library section subtree into instance rows.
     * Called by AuditEngagementService.snapshotTemplate() for each root section.
     *
     * @param libSection        library section being snapshotted
     * @param parentInstanceId  instance ID of already-snapshotted parent (null for roots)
     * @param parentPath        path of already-snapshotted parent (null for roots)
     * @param engagementId      target engagement
     * @param templateInstanceId  FK to the engagement template instance
     * @return the created AuditSectionInstance (used by caller to recurse into children)
     */
    @Transactional
    public AuditSectionInstance snapshotSectionNode(
            AuditSection libSection,
            Long parentInstanceId,
            String parentPath,
            Long engagementId,
            Long templateInstanceId,
            Long tenantId) {

        int depth = libSection.getDepth();  // preserved from library

        // Save with placeholder path — updated once we have the instance ID
        AuditSectionInstance instance = sectionInstanceRepository.save(
                AuditSectionInstance.builder()
                        .tenantId(tenantId)
                        .engagementId(engagementId)
                        .templateInstanceId(templateInstanceId)
                        .parentInstanceId(parentInstanceId)
                        .originalSectionId(libSection.getId())
                        .sectionNameSnapshot(libSection.getName())
                        .sectionCodeSnapshot(libSection.getSectionCode())
                        .descriptionSnapshot(libSection.getDescription())
                        .frameworkRefSnapshot(libSection.getFrameworkRef())
                        .orderNo(libSection.getOrderNo())
                        .depth(depth)
                        .path("/PLACEHOLDER/")
                        .build()
        );

        // Build instance path — requires the generated ID, so one update save needed
        String path = (parentPath == null || parentPath.equals("/"))
                ? "/" + instance.getId() + "/"
                : parentPath + instance.getId() + "/";

        instance.setPath(path);
        sectionInstanceRepository.save(instance);

        // Snapshot controls attached to this section in the library
        snapshotControlsForSection(libSection, instance, engagementId, tenantId);

        // Recurse into library children
        List<AuditSection> children = sectionRepository.findByParentIdOrderByOrderNoAsc(libSection.getId());
        for (AuditSection child : children) {
            snapshotSectionNode(child, instance.getId(), path, engagementId, templateInstanceId, tenantId);
        }

        return instance;
    }

    /**
     * Snapshots all controls attached to a library section into AuditControlInstance rows.
     * Controls belong to the leaf section where they are mapped.
     */
    private void snapshotControlsForSection(AuditSection libSection,
                                            AuditSectionInstance sectionInstance,
                                            Long engagementId,
                                            Long tenantId) {
        List<AuditSectionControlMapping> mappings =
                controlMappingRepository.findBySectionIdOrderByOrderNoAsc(libSection.getId());

        if (mappings.isEmpty()) return;

        // Batch all control lookups and builds, then saveAll in one round-trip
        List<Long> controlIds = mappings.stream()
                .map(AuditSectionControlMapping::getControlId).toList();
        Map<Long, AuditControl> controlMap = controlRepository.findAllById(controlIds)
                .stream().collect(java.util.stream.Collectors.toMap(AuditControl::getId, c -> c));

        List<AuditControlInstance> toSave = new ArrayList<>();
        for (AuditSectionControlMapping mapping : mappings) {
            AuditControl control = controlMap.get(mapping.getControlId());
            if (control == null) continue;

            toSave.add(AuditControlInstance.builder()
                    .tenantId(tenantId)
                    .engagementId(engagementId)
                    .sectionInstanceId(sectionInstance.getId())
                    .sectionPath(sectionInstance.getPath())
                    .originalControlId(control.getId())
                    .controlNameSnapshot(control.getName())
                    .controlCodeSnapshot(control.getControlCode())
                    .descriptionSnapshot(control.getDescription())
                    .testTypeSnapshot(control.getTestType().name())
                    .frameworkRefSnapshot(control.getFrameworkRef())
                    .controlTagSnapshot(control.getControlTag())
                    // Phase 3: freeze the expanded ancestry chain. UCF matching
                    // reads this; control_tag_snapshot is kept for the legacy path.
                    .matchedTagsSnapshot(tagExpansionService.expand(control.getControlTag()))
                    .sectionBreadcrumbSnapshot(buildBreadcrumb(sectionInstance))
                    .weight(mapping.getWeight())
                    .isMandatory(mapping.isMandatory())
                    .orderNo(mapping.getOrderNo())
                    .testResult(AuditControlInstance.TestResult.NOT_TESTED)
                    .build());
        }

        controlInstanceRepository.saveAll(toSave);
    }

    // ══════════════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════════════

    private String buildBreadcrumb(AuditSectionInstance sectionInstance) {
        // Simple: use code if present, else name
        String code = sectionInstance.getSectionCodeSnapshot();
        return code != null && !code.isBlank() ? code : sectionInstance.getSectionNameSnapshot();
    }

    private int nextOrderNo(Long parentId, Long tenantId) {
        return (int) sectionRepository.findByParentIdOrderByOrderNoAsc(
                parentId).stream().count();
    }

    private List<SectionNode> buildTree(List<AuditSection> all, Long parentId, List<Long> rootIds) {
        List<SectionNode> result = new ArrayList<>();
        for (AuditSection s : all) {
            boolean isRoot = parentId == null && rootIds.contains(s.getId()) && s.getParentId() == null;
            boolean isChild = parentId != null && parentId.equals(s.getParentId());
            if (isRoot || isChild) {
                List<SectionNode> children = buildTree(all, s.getId(), rootIds);
                result.add(new SectionNode(s, children));
            }
        }
        result.sort(Comparator.comparingInt(n -> n.section().getOrderNo()));
        return result;
    }

    /** Tree node for API responses */
    public record SectionNode(AuditSection section, List<SectionNode> children) {}
}