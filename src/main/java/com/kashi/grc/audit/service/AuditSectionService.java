package com.kashi.grc.audit.service;

import com.kashi.grc.audit.domain.*;
import com.kashi.grc.audit.repository.*;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.jdbc.JdbcBatchInsertHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
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
    private final AuditControlRepository            controlRepository;
    private final AuditSectionControlMappingRepository controlMappingRepository;
    private final com.kashi.grc.ucf.service.TagExpansionService tagExpansionService;
    private final JdbcBatchInsertHelper jdbcBatchInsertHelper;

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
     * Snapshots an entire library section tree (starting from a set of root
     * sections) into instance rows — BFS-batched, level by level, instead of
     * the previous per-node recursion.
     *
     * WHY THIS CHANGED: the old snapshotSectionNode() recursed one section at
     * a time, doing 2 saves (INSERT placeholder + UPDATE path) plus 1 query
     * (fetch children) PER NODE, and called snapshotControlsForSection() once
     * per section for controls (which itself had the same fake-batching
     * problem as the pre-fix assessment instantiation code — see
     * JdbcBatchInsertHelper javadoc: saveAll() does not actually batch
     * INSERTs for IDENTITY-strategy entities, which is every entity here).
     * For a 50-section, 4-level-deep framework that was ~150 round trips.
     *
     * This version:
     *   - Reads the whole tree level-by-level via findByParentIdInOrderByOrderNoAsc
     *     — O(tree depth) queries instead of O(section count).
     *   - Writes each level as ONE JDBC batch INSERT (genuine batching, see
     *     JdbcBatchInsertHelper) + ONE batch UPDATE for the path column
     *     (path needs the row's own generated id, so it's still a two-step
     *     insert-then-fixup — just batched per LEVEL instead of per NODE).
     *   - Snapshots controls for the ENTIRE tree in one batch insert at the
     *     end, instead of one saveAll() per section.
     * Net effect for the same 50-section example: roughly 2×depth + a
     * handful of read queries + 1 control batch insert — typically under 15
     * round trips instead of ~150+.
     *
     * @return total number of section instances created (for the caller's log line)
     */
    @Transactional
    public int snapshotSectionTree(List<AuditSection> rootSections, Long engagementId,
                                   Long templateInstanceId, Long tenantId) {
        if (rootSections.isEmpty()) return 0;

        Timestamp now = Timestamp.valueOf(java.time.LocalDateTime.now());

        // Accumulated across all levels as we go — lib section id -> its instance id/path.
        Map<Long, Long>   libIdToInstanceId   = new HashMap<>();
        Map<Long, String> libIdToInstancePath = new HashMap<>();
        List<AuditSection> allLibSections = new ArrayList<>();

        List<AuditSection> currentLevel = rootSections;
        while (!currentLevel.isEmpty()) {
            List<Object[]> insertRows = new ArrayList<>();
            for (AuditSection sec : currentLevel) {
                Long parentInstanceId = sec.getParentId() == null
                        ? null : libIdToInstanceId.get(sec.getParentId());
                insertRows.add(new Object[]{
                        tenantId, engagementId, templateInstanceId, parentInstanceId,
                        "/PLACEHOLDER/", sec.getDepth(), sec.getId(), sec.getName(),
                        sec.getSectionCode(), sec.getDescription(), sec.getFrameworkRef(),
                        sec.getOrderNo(), now, now
                });
            }
            List<Long> instanceIds = jdbcBatchInsertHelper.batchInsertAndGetIds(
                    "INSERT INTO audit_section_instances " +
                            "(tenant_id, engagement_id, template_instance_id, parent_instance_id, path, depth, " +
                            "original_section_id, section_name_snapshot, section_code_snapshot, " +
                            "description_snapshot, framework_ref_snapshot, order_no, created_at, updated_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    insertRows);

            List<Object[]> pathUpdateRows = new ArrayList<>();
            for (int i = 0; i < currentLevel.size(); i++) {
                AuditSection sec = currentLevel.get(i);
                Long instanceId = instanceIds.get(i);
                String parentPath = sec.getParentId() == null ? null : libIdToInstancePath.get(sec.getParentId());
                String path = parentPath == null ? "/" + instanceId + "/" : parentPath + instanceId + "/";
                libIdToInstanceId.put(sec.getId(), instanceId);
                libIdToInstancePath.put(sec.getId(), path);
                pathUpdateRows.add(new Object[]{path, instanceId});
            }
            jdbcBatchInsertHelper.batchUpdate(
                    "UPDATE audit_section_instances SET path = ? WHERE id = ?", pathUpdateRows);

            allLibSections.addAll(currentLevel);

            List<Long> currentLevelIds = currentLevel.stream().map(AuditSection::getId).toList();
            currentLevel = sectionRepository.findByParentIdInOrderByOrderNoAsc(currentLevelIds);
        }

        snapshotControlsForWholeTree(allLibSections, libIdToInstanceId, libIdToInstancePath,
                engagementId, tenantId, now);

        return allLibSections.size();
    }

    /**
     * Batch-snapshots every control across an entire section tree in one
     * pass — was previously one saveAll() call PER SECTION (see
     * snapshotSectionTree javadoc for why that didn't actually batch at the
     * JDBC level either). Now: 2 read queries total (mappings + controls,
     * both already using existing batch repository methods) and 1 JDBC
     * batch insert for every control instance in the tree.
     */
    private void snapshotControlsForWholeTree(List<AuditSection> allLibSections,
                                              Map<Long, Long> libIdToInstanceId,
                                              Map<Long, String> libIdToInstancePath,
                                              Long engagementId, Long tenantId, Timestamp now) {
        List<Long> allSectionIds = allLibSections.stream().map(AuditSection::getId).toList();
        List<AuditSectionControlMapping> allMappings =
                controlMappingRepository.findBySectionIdInOrderBySectionIdAscOrderNoAsc(allSectionIds);
        if (allMappings.isEmpty()) return;

        List<Long> controlIds = allMappings.stream().map(AuditSectionControlMapping::getControlId).toList();
        Map<Long, AuditControl> controlMap = controlRepository.findAllById(controlIds)
                .stream().collect(java.util.stream.Collectors.toMap(AuditControl::getId, c -> c));
        Map<Long, AuditSection> libSectionsById = allLibSections.stream()
                .collect(java.util.stream.Collectors.toMap(AuditSection::getId, s -> s));

        List<Object[]> rows = new ArrayList<>();
        for (AuditSectionControlMapping mapping : allMappings) {
            AuditControl control = controlMap.get(mapping.getControlId());
            if (control == null) continue;
            Long sectionInstanceId = libIdToInstanceId.get(mapping.getSectionId());
            String sectionPath = libIdToInstancePath.get(mapping.getSectionId());
            if (sectionInstanceId == null) continue; // defensive — shouldn't happen

            AuditSection libSection = libSectionsById.get(mapping.getSectionId());
            String breadcrumb = buildBreadcrumb(libSection.getSectionCode(), libSection.getName());

            rows.add(new Object[]{
                    tenantId, engagementId, sectionInstanceId, sectionPath,
                    control.getId(), control.getName(), control.getControlCode(), control.getDescription(),
                    breadcrumb, control.getTestType().name(), control.getFrameworkRef(),
                    control.getControlTag(), tagExpansionService.expand(control.getControlTag()),
                    mapping.getWeight(), mapping.isMandatory(), mapping.getOrderNo(),
                    AuditControlInstance.TestResult.NOT_TESTED.name(), now, now
            });
        }

        jdbcBatchInsertHelper.batchInsertAndGetIds(
                "INSERT INTO audit_control_instances " +
                        "(tenant_id, engagement_id, section_instance_id, section_path, original_control_id, " +
                        "control_name_snapshot, control_code_snapshot, description_snapshot, " +
                        "section_breadcrumb_snapshot, test_type_snapshot, framework_ref_snapshot, " +
                        "control_tag_snapshot, matched_tags_snapshot, weight, is_mandatory, order_no, " +
                        "test_result, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                rows);
    }

    // ══════════════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════════════

    private String buildBreadcrumb(String sectionCode, String sectionName) {
        // Simple: use code if present, else name
        return sectionCode != null && !sectionCode.isBlank() ? sectionCode : sectionName;
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