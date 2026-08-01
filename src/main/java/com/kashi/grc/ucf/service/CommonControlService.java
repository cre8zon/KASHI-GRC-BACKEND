package com.kashi.grc.ucf.service;

import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.ucf.domain.CommonControl;
import com.kashi.grc.ucf.domain.CommonControlMapping;
import com.kashi.grc.ucf.dto.CommonControlDtos.*;
import com.kashi.grc.ucf.repository.CommonControlMappingRepository;
import com.kashi.grc.ucf.repository.CommonControlRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * The common control catalogue — read, edit, and the picker that feeds the
 * audit library control form.
 *
 * Reads are unscoped by tenant on purpose: the catalogue is global reference
 * data (tenant_id NULL) that every organisation shares. Tenant-private entries
 * are supported by the schema but filtered in at query time when that lands.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommonControlService {

    private final CommonControlRepository        controlRepository;
    private final CommonControlMappingRepository mappingRepository;

    @PersistenceContext
    private EntityManager em;

    // ── Tree ────────────────────────────────────────────────────────────────

    /**
     * Full catalogue as a DOMAIN -> FAMILY -> CONTROL tree, with library usage
     * counts attached to each leaf so the admin page can show at a glance which
     * entries are actually earning their place.
     */
    @Transactional(readOnly = true)
    public List<NodeResponse> tree() {
        List<CommonControl> all = controlRepository.findByActiveTrueOrderBySortOrderAsc();
        Map<String, Long> ctrlUsage = usageCounts("audit_controls");
        Map<String, Long> testUsage = usageCounts("audit_tests");
        Map<String, Integer> fwCounts = frameworkCounts();

        Map<String, NodeResponse> byCode = new LinkedHashMap<>();
        for (CommonControl c : all) {
            NodeResponse n = toNode(c);
            n.setLibraryControls(ctrlUsage.getOrDefault(c.getCode(), 0L));
            n.setLibraryTests(testUsage.getOrDefault(c.getCode(), 0L));
            n.setFrameworkCount(fwCounts.getOrDefault(c.getCode(), 0));
            n.setChildren(new ArrayList<>());
            byCode.put(c.getCode(), n);
        }

        List<NodeResponse> roots = new ArrayList<>();
        for (NodeResponse n : byCode.values()) {
            if (n.getParentCode() == null || !byCode.containsKey(n.getParentCode())) {
                roots.add(n);
            } else {
                byCode.get(n.getParentCode()).getChildren().add(n);
            }
        }
        return roots;
    }

    // ── Picker ──────────────────────────────────────────────────────────────

    /**
     * Leaf-level options for the audit library tag picker.
     *
     * Searching matches code, title AND legacy_tag, so someone who still thinks
     * in the old vocabulary can type 'MFA_ADMIN' and land on IAM-02.3. That
     * matters more than it sounds — a picker people cannot search is a picker
     * they route around, and routing around it is how drift restarts.
     */
    @Transactional(readOnly = true)
    public List<PickerOption> picker(String query, String domainCode, int limit) {
        List<CommonControl> leaves = controlRepository.searchSelectable(query, domainCode, limit);
        if (leaves.isEmpty()) return List.of();

        // Family titles for the secondary line in the dropdown
        List<String> parents = leaves.stream()
                .map(CommonControl::getParentCode)
                .filter(Objects::nonNull).distinct().collect(Collectors.toList());
        Map<String, CommonControl> familyByCode = parents.isEmpty() ? Map.of()
                : controlRepository.findByCodeIn(parents).stream()
                  .collect(Collectors.toMap(CommonControl::getCode, c -> c));

        return leaves.stream().map(c -> {
            CommonControl fam = familyByCode.get(c.getParentCode());
            return PickerOption.builder()
                    .code(c.getCode())
                    .title(c.getTitle())
                    .domainCode(c.getDomainCode())
                    .familyLabel(fam != null ? fam.getCode() + " " + fam.getTitle() : c.getDomainCode())
                    .legacyTag(c.getLegacyTag())
                    .build();
        }).collect(Collectors.toList());
    }

    // ── Detail ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public DetailResponse detail(String code) {
        CommonControl node = controlRepository.findByCode(code)
                .orElseThrow(() -> new ResourceNotFoundException("CommonControl", "code", code));

        List<NodeResponse> ancestry = controlRepository.findAncestryChain(code)
                .stream().map(this::toNode).collect(Collectors.toList());

        List<MappingResponse> mappings = mappingRepository
                .findByCommonControlCodeAndActiveTrue(code)
                .stream().map(this::toMapping).collect(Collectors.toList());

        return DetailResponse.builder()
                .node(toNode(node))
                .ancestry(ancestry)
                .mappings(mappings)
                .usedBy(libraryUsage(code))
                .build();
    }

    // ── Ancestry, for Phase 3 ───────────────────────────────────────────────

    /**
     * The expanded tag set for a leaf: its own code plus every ancestor,
     * uppercased and comma-joined. This is exactly what Phase 3 freezes into
     * matched_tags_snapshot at instantiation.
     *
     *   expandedTagSet("IAM-02.3")  ->  "IAM-02.3,IAM-02,IAM"
     *
     * Ancestors only, never descendants: a control sitting on a coarse node
     * must not be satisfied by evidence for one narrow child of it.
     */
    @Transactional(readOnly = true)
    public String expandedTagSet(String code) {
        List<CommonControl> chain = controlRepository.findAncestryChain(code);
        if (chain.isEmpty()) return null;
        return chain.stream()
                .map(c -> c.getCode().toUpperCase())
                .collect(Collectors.joining(","));
    }

    // ── Mutations ───────────────────────────────────────────────────────────

    @Transactional
    public NodeResponse createNode(NodeRequest req) {
        String code = req.getCode().toUpperCase().trim();
        if (controlRepository.existsByCode(code)) {
            throw new IllegalArgumentException("Common control already exists: " + code);
        }
        if (req.getParentCode() != null && !req.getParentCode().isBlank()
                && controlRepository.findByCode(req.getParentCode()).isEmpty()) {
            throw new IllegalArgumentException("Parent not found: " + req.getParentCode());
        }

        CommonControl c = CommonControl.builder()
                .code(code)
                .parentCode(req.getParentCode())
                .nodeLevel(req.getNodeLevel())
                .domainCode(req.getDomainCode().toUpperCase().trim())
                .title(req.getTitle())
                .description(req.getDescription())
                .legacyTag(req.getLegacyTag() != null ? req.getLegacyTag().toUpperCase().trim() : null)
                .sortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0)
                .active(req.getActive() == null || req.getActive())
                .source("KASHI")
                .build();

        controlRepository.save(c);
        log.info("[UCF] Created common control {} ({})", code, req.getNodeLevel());
        return toNode(c);
    }

    @Transactional
    public NodeResponse updateNode(String code, NodeRequest req) {
        CommonControl c = controlRepository.findByCode(code)
                .orElseThrow(() -> new ResourceNotFoundException("CommonControl", "code", code));

        c.setTitle(req.getTitle());
        c.setDescription(req.getDescription());
        c.setLegacyTag(req.getLegacyTag() != null ? req.getLegacyTag().toUpperCase().trim() : null);
        if (req.getSortOrder() != null) c.setSortOrder(req.getSortOrder());
        if (req.getActive() != null)    c.setActive(req.getActive());

        // code, parentCode and nodeLevel are immutable: audit_controls and any
        // frozen matched_tags_snapshot already reference them.
        controlRepository.save(c);
        return toNode(c);
    }

    /**
     * Deactivate rather than delete. A code may already be frozen into an
     * engagement's matched_tags_snapshot, and deleting it would leave that
     * snapshot pointing at nothing.
     */
    @Transactional
    public void deactivateNode(String code) {
        CommonControl c = controlRepository.findByCode(code)
                .orElseThrow(() -> new ResourceNotFoundException("CommonControl", "code", code));

        long inUse = usageCounts("audit_controls").getOrDefault(code, 0L)
                + usageCounts("audit_tests").getOrDefault(code, 0L);
        if (inUse > 0) {
            throw new IllegalStateException(
                    "Cannot deactivate " + code + " — " + inUse + " library rows still reference it");
        }
        c.setActive(false);
        controlRepository.save(c);
        log.info("[UCF] Deactivated common control {}", code);
    }

    @Transactional
    public MappingResponse createMapping(MappingRequest req) {
        String code = req.getCommonControlCode().toUpperCase().trim();
        controlRepository.findByCode(code)
                .orElseThrow(() -> new ResourceNotFoundException("CommonControl", "code", code));

        if (mappingRepository.existsByCommonControlCodeAndFrameworkRefAndCitation(
                code, req.getFrameworkRef(), req.getCitation())) {
            throw new IllegalArgumentException("Mapping already exists for "
                    + req.getFrameworkRef() + " " + req.getCitation());
        }

        CommonControlMapping m = CommonControlMapping.builder()
                .commonControlCode(code)
                .frameworkRef(req.getFrameworkRef().trim())
                .citation(req.getCitation().trim())
                .citationTitle(req.getCitationTitle())
                .relationship(req.getRelationship())
                .notes(req.getNotes())
                .source("KASHI")
                .active(true)
                .build();

        mappingRepository.save(m);
        return toMapping(m);
    }

    @Transactional
    public MappingResponse updateMapping(Long id, MappingRequest req) {
        CommonControlMapping m = mappingRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CommonControlMapping", id));
        m.setRelationship(req.getRelationship());
        m.setCitationTitle(req.getCitationTitle());
        m.setNotes(req.getNotes());
        // A DERIVED row that a human has reviewed is no longer derived.
        if ("DERIVED".equals(m.getSource())) m.setSource("KASHI");
        mappingRepository.save(m);
        return toMapping(m);
    }

    @Transactional
    public void deleteMapping(Long id) {
        CommonControlMapping m = mappingRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CommonControlMapping", id));
        m.setActive(false);
        mappingRepository.save(m);
    }

    // ── Coverage ────────────────────────────────────────────────────────────

    /** Per-framework citation coverage: how much of it the catalogue reaches. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> frameworkCoverage() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createQuery(
                        "SELECT m.frameworkRef, COUNT(DISTINCT m.citation), COUNT(m.id) "
                                + "FROM CommonControlMapping m WHERE m.active = true GROUP BY m.frameworkRef")
                .getResultList();

        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("frameworkRef",  r[0]);
            m.put("citations",     ((Number) r[1]).longValue());
            m.put("mappings",      ((Number) r[2]).longValue());
            out.add(m);
        }
        out.sort((a, b) -> Long.compare((Long) b.get("citations"), (Long) a.get("citations")));
        return out;
    }

    // ── Internals ───────────────────────────────────────────────────────────

    /**
     * How many library rows point at each common control.
     *
     * JPQL over the audit entities would couple this package to the audit
     * module's domain classes for a pure count, so a scalar query on the
     * column is used instead. Table name is a fixed literal, never user input.
     */
    private Map<String, Long> usageCounts(String table) {
        Map<String, Long> out = new HashMap<>();
        String entity = "audit_controls".equals(table)
                ? "com.kashi.grc.audit.domain.AuditControl"
                : "com.kashi.grc.audit.domain.AuditTest";
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createQuery(
                        "SELECT e.commonControlCode, COUNT(e) FROM " + entity + " e "
                                + "WHERE e.commonControlCode IS NOT NULL GROUP BY e.commonControlCode")
                .getResultList();
        for (Object[] r : rows) out.put((String) r[0], ((Number) r[1]).longValue());
        return out;
    }

    /** Distinct frameworks reachable per common control, via the crosswalk. */
    private Map<String, Integer> frameworkCounts() {
        Map<String, Integer> out = new HashMap<>();
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createQuery(
                        "SELECT m.commonControlCode, COUNT(DISTINCT m.frameworkRef) "
                                + "FROM CommonControlMapping m WHERE m.active = true "
                                + "GROUP BY m.commonControlCode")
                .getResultList();
        for (Object[] r : rows) out.put((String) r[0], ((Number) r[1]).intValue());
        return out;
    }

    private List<LibraryUsage> libraryUsage(String code) {
        List<LibraryUsage> out = new ArrayList<>();

        @SuppressWarnings("unchecked")
        List<Object[]> controls = em.createQuery(
                        "SELECT c.id, c.frameworkRef, c.controlCode, c.name "
                                + "FROM com.kashi.grc.audit.domain.AuditControl c "
                                + "WHERE c.commonControlCode = :code ORDER BY c.frameworkRef, c.controlCode")
                .setParameter("code", code).getResultList();
        for (Object[] r : controls) {
            out.add(LibraryUsage.builder().entityType("AUDIT_CONTROL")
                    .entityId((Long) r[0]).frameworkRef((String) r[1])
                    .controlCode((String) r[2]).name((String) r[3]).build());
        }

        @SuppressWarnings("unchecked")
        List<Object[]> tests = em.createQuery(
                        "SELECT t.id, t.frameworkRef, t.testRef, t.name "
                                + "FROM com.kashi.grc.audit.domain.AuditTest t "
                                + "WHERE t.commonControlCode = :code ORDER BY t.frameworkRef, t.testRef")
                .setParameter("code", code).getResultList();
        for (Object[] r : tests) {
            out.add(LibraryUsage.builder().entityType("AUDIT_TEST")
                    .entityId((Long) r[0]).frameworkRef((String) r[1])
                    .controlCode((String) r[2]).name((String) r[3]).build());
        }
        return out;
    }

    private NodeResponse toNode(CommonControl c) {
        return NodeResponse.builder()
                .id(c.getId())
                .code(c.getCode())
                .parentCode(c.getParentCode())
                .nodeLevel(c.getNodeLevel() != null ? c.getNodeLevel().name() : null)
                .domainCode(c.getDomainCode())
                .title(c.getTitle())
                .description(c.getDescription())
                .legacyTag(c.getLegacyTag())
                .sortOrder(c.getSortOrder())
                .active(c.getActive())
                .source(c.getSource())
                .build();
    }

    private MappingResponse toMapping(CommonControlMapping m) {
        return MappingResponse.builder()
                .id(m.getId())
                .commonControlCode(m.getCommonControlCode())
                .frameworkRef(m.getFrameworkRef())
                .citation(m.getCitation())
                .citationTitle(m.getCitationTitle())
                .relationship(m.getRelationship() != null ? m.getRelationship().name() : null)
                .fullySatisfies(m.getRelationship() != null && m.getRelationship().fullySatisfies())
                .notes(m.getNotes())
                .source(m.getSource())
                .build();
    }
}