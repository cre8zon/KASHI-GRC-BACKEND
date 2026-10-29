package com.kashi.grc.workflow.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.document.service.StorageService;
import com.kashi.grc.usermanagement.domain.Role;
import com.kashi.grc.usermanagement.domain.User;
import com.kashi.grc.usermanagement.repository.RoleRepository;
import com.kashi.grc.workflow.domain.Workflow;
import com.kashi.grc.workflow.domain.WorkflowStep;
import com.kashi.grc.workflow.domain.WorkflowStepRole;
import com.kashi.grc.workflow.repository.WorkflowInstanceRepository;
import com.kashi.grc.workflow.repository.WorkflowRepository;
import com.kashi.grc.workflow.repository.WorkflowStepRepository;
import com.kashi.grc.workflow.repository.WorkflowStepRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The workflow catalogue — what a customer sees before starting work in a
 * module: every workflow that module can run, its steps in order, which side
 * and roles each step needs, whether the tenant has people in those roles,
 * how much the tenant already uses it, and the walkthrough video.
 *
 * Read-only for tenants. The only writes are Platform Admin's demo-video
 * settings, guarded by isSystemUser() exactly like WorkflowController.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowCatalogueService {

    private final WorkflowRepository         workflowRepository;
    private final WorkflowStepRepository     stepRepository;
    private final WorkflowStepRoleRepository stepRoleRepository;
    private final WorkflowInstanceRepository instanceRepository;
    private final RoleRepository             roleRepository;
    private final StorageService             storageService;
    private final UtilityService             utilityService;
    private final ObjectMapper               objectMapper;
    private final com.kashi.grc.module.repository.ModuleBlueprintRepository blueprintRepository;

    /** Side order used for the sides summary, so every card lists them the same way. */
    private static final List<String> SIDE_ORDER =
            List.of("SYSTEM", "ORGANIZATION", "VENDOR", "AUDITOR", "AUDITEE");

    // ══════════════════════ READ ═════════════════════════════════════════════

    /** Every active workflow for an entity type the caller's tenant can run. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String entityType) {
        if (entityType == null || entityType.isBlank())
            throw new BusinessException("ENTITY_TYPE_REQUIRED", "entityType is required", HttpStatus.BAD_REQUEST);
        User user = utilityService.getLoggedInDataContext();
        // A module runs its own workflows AND the ones its blueprint is eligible
        // for: Vendor Assessment has none of its own — it runs the VENDOR
        // workflow (workflow_eligibility = 'VENDOR'). Without this, vendor users
        // (who reach Vendor Assessments, not Vendors) found no workflow, no
        // steps and no video.
        String et = entityType.trim().toUpperCase();
        List<Workflow> workflows = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (String type : workflowTypesFor(et, user.getTenantId())) {
            for (Workflow w : visibleWorkflows(type, user.getTenantId())) if (seen.add(w.getId())) workflows.add(w);
        }
        return describe(workflows, user.getTenantId(), false);
    }

    /** One workflow, with role coverage for the caller's tenant. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(Long workflowId) {
        User user = utilityService.getLoggedInDataContext();
        Workflow wf = requireVisible(workflowId, user.getTenantId());
        return describe(List.of(wf), user.getTenantId(), true).get(0);
    }

    /**
     * Where to play the walkthrough. An uploaded file gets a fresh presigned
     * URL each time (never stored); an external link is returned as is.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> demo(Long workflowId) {
        User user = utilityService.getLoggedInDataContext();
        Workflow wf = requireVisible(workflowId, user.getTenantId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("workflowId", wf.getId());
        if (notBlank(wf.getDemoVideoKey())) {
            out.put("kind", "FILE");
            out.put("url", storageService.generateDownloadUrl(wf.getDemoVideoKey(), false, null));
        } else if (notBlank(wf.getDemoVideoUrl())) {
            out.put("kind", "LINK");
            out.put("url", wf.getDemoVideoUrl());
        } else {
            out.put("kind", "NONE");
            out.put("url", null);
        }
        out.put("chapters", chapters(wf.getId()));
        return out;
    }

    // ══════════════════════ PLATFORM ADMIN: DEMO VIDEO ═══════════════════════

    /** Presigned PUT for a walkthrough video, stored under the platform's own key space. */
    @Transactional(readOnly = true)
    public Map<String, Object> demoUploadUrl(Long workflowId, String fileName, String mimeType, Long sizeBytes) {
        guardPlatformAdmin();
        workflowRepository.findById(workflowId)
                .orElseThrow(() -> new ResourceNotFoundException("Workflow", workflowId));
        User user = utilityService.getLoggedInDataContext();
        StorageService.PresignedUploadResult r = storageService.generateUploadUrl(
                user.getTenantId(), user.getId(), fileName, mimeType, sizeBytes,
                "WORKFLOW_DEMO_VIDEO", "WORKFLOW", workflowId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("presignedUrl", r.getPresignedUrl());
        out.put("s3Key", r.getS3Key());
        out.put("mimeType", r.getEffectiveMimeType());
        out.put("expiresAt", String.valueOf(r.getExpiresAt()));
        return out;
    }

    /**
     * Saves the video source and chapter times. Keys that are absent are left
     * alone; a blank string clears the value; chapters replaces every step's
     * start time that it names (null clears one).
     */
    @Transactional
    public Map<String, Object> saveDemo(Long workflowId, Map<String, Object> body) {
        guardPlatformAdmin();
        Workflow wf = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new ResourceNotFoundException("Workflow", workflowId));
        if (body.containsKey("demoVideoKey")) wf.setDemoVideoKey(blankToNull(body.get("demoVideoKey")));
        if (body.containsKey("demoVideoUrl")) {
            String url = blankToNull(body.get("demoVideoUrl"));
            if (url != null && !url.startsWith("https://"))
                throw new BusinessException("DEMO_URL_INVALID", "The video link must start with https://", HttpStatus.BAD_REQUEST);
            wf.setDemoVideoUrl(url);
        }
        workflowRepository.save(wf);

        Object raw = body.get("chapters");
        if (raw instanceof Map<?, ?> chapters) {
            Map<Long, WorkflowStep> steps = stepRepository.findByWorkflowIdOrderByStepOrderAsc(workflowId).stream()
                    .collect(Collectors.toMap(WorkflowStep::getId, Function.identity()));
            chapters.forEach((k, v) -> {
                Long stepId;
                try { stepId = Long.valueOf(String.valueOf(k)); } catch (NumberFormatException ex) { return; }
                WorkflowStep step = steps.get(stepId);
                if (step == null) return;     // not a step of this workflow — ignored, never cross-written
                Integer seconds = null;
                if (v != null && !String.valueOf(v).isBlank()) {
                    try { seconds = Math.max(0, Integer.parseInt(String.valueOf(v).trim())); }
                    catch (NumberFormatException ex) {
                        throw new BusinessException("DEMO_CHAPTER_INVALID",
                                "Chapter times are whole seconds (step " + stepId + ")", HttpStatus.BAD_REQUEST);
                    }
                }
                step.setDemoStartSeconds(seconds);
                stepRepository.save(step);
            });
        }
        // "What to do" per step: {stepId: text}; blank clears.
        if (body.get("guides") instanceof Map<?, ?> guides) {
            Map<Long, WorkflowStep> steps = stepRepository.findByWorkflowIdOrderByStepOrderAsc(workflowId).stream()
                    .collect(Collectors.toMap(WorkflowStep::getId, Function.identity()));
            guides.forEach((k, v) -> {
                Long stepId;
                try { stepId = Long.valueOf(String.valueOf(k)); } catch (NumberFormatException ex) { return; }
                WorkflowStep step = steps.get(stepId);
                if (step == null) return;
                String text = blankToNull(v);
                if (text != null && text.length() > 2000)
                    throw new BusinessException("GUIDE_TOO_LONG",
                            "Keep step guidance under 2000 characters (step " + step.getStepOrder() + ")", HttpStatus.BAD_REQUEST);
                step.setGuideText(text);
                stepRepository.save(step);
            });
        }
        log.info("[WF-CATALOGUE] demo saved | workflowId={} file={} link={}",
                workflowId, wf.getDemoVideoKey() != null, wf.getDemoVideoUrl() != null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("workflowId", wf.getId());
        out.put("demoVideoKey", wf.getDemoVideoKey());
        out.put("demoVideoUrl", wf.getDemoVideoUrl());
        out.put("chapters", chapters(workflowId));
        return out;
    }

    // ══════════════════════ INTERNALS ════════════════════════════════════════

    /**
     * Active global blueprints plus the tenant's own, latest version of each
     * name only — an older version left active is not a second choice.
     */
    private List<Workflow> visibleWorkflows(String entityType, Long tenantId) {
        List<Workflow> all = new ArrayList<>(workflowRepository.findByTenantIdIsNullAndEntityTypeAndIsActiveTrue(entityType));
        if (tenantId != null) all.addAll(workflowRepository.findByTenantIdAndEntityTypeAndIsActiveTrue(tenantId, entityType));
        Map<String, Workflow> latest = new LinkedHashMap<>();
        for (Workflow w : all) {
            String key = (w.getTenantId() == null ? "G:" : "T:") + w.getName();
            Workflow cur = latest.get(key);
            if (cur == null || safeVersion(w) > safeVersion(cur)) latest.put(key, w);
        }
        return latest.values().stream()
                .sorted(Comparator.comparing(Workflow::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** The module's own type first, then its blueprint's workflow_eligibility (tenant copy over platform). */
    private List<String> workflowTypesFor(String entityType, Long tenantId) {
        LinkedHashSet<String> types = new LinkedHashSet<>();
        types.add(entityType);
        Optional<com.kashi.grc.module.domain.ModuleBlueprint> bp = tenantId == null ? Optional.empty()
                : blueprintRepository.findByEntityTypeIgnoreCaseAndTenantId(entityType, tenantId);
        if (bp.isEmpty()) bp = blueprintRepository.findByEntityTypeIgnoreCaseAndTenantIdIsNull(entityType);
        bp.map(com.kashi.grc.module.domain.ModuleBlueprint::getWorkflowEligibility).ifPresent(el -> {
            for (String t : el.split(",")) if (!t.isBlank()) types.add(t.trim().toUpperCase());
        });
        return new ArrayList<>(types);
    }

    private Workflow requireVisible(Long workflowId, Long tenantId) {
        Workflow wf = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new ResourceNotFoundException("Workflow", workflowId));
        boolean visible = wf.getTenantId() == null || Objects.equals(wf.getTenantId(), tenantId);
        if (!visible && !utilityService.isSystemUser())
            throw new ResourceNotFoundException("Workflow", workflowId);
        return wf;
    }

    private List<Map<String, Object>> describe(List<Workflow> workflows, Long tenantId, boolean withCoverage) {
        if (workflows.isEmpty()) return List.of();
        List<Long> ids = workflows.stream().map(Workflow::getId).toList();

        Map<Long, List<WorkflowStep>> stepsByWf = stepRepository.findByWorkflowIdInOrderByStepOrderAsc(ids).stream()
                .collect(Collectors.groupingBy(WorkflowStep::getWorkflowId, LinkedHashMap::new, Collectors.toList()));
        List<Long> stepIds = stepsByWf.values().stream().flatMap(List::stream).map(WorkflowStep::getId).toList();

        Map<Long, List<Long>> roleIdsByStep = stepIds.isEmpty() ? Map.of()
                : stepRoleRepository.findByStepIdIn(stepIds).stream()
                  .collect(Collectors.groupingBy(WorkflowStepRole::getStepId,
                          Collectors.mapping(WorkflowStepRole::getRoleId, Collectors.toList())));
        Set<Long> allRoleIds = roleIdsByStep.values().stream().flatMap(List::stream).collect(Collectors.toSet());
        Map<Long, Role> roles = allRoleIds.isEmpty() ? Map.of()
                : roleRepository.findAllById(allRoleIds).stream().collect(Collectors.toMap(Role::getId, Function.identity()));

        Map<Long, long[]> usage = new HashMap<>();
        for (Object[] row : instanceRepository.countUsageByWorkflowForTenant(tenantId, ids)) {
            usage.put((Long) row[0], new long[]{ toLong(row[1]), toLong(row[2]) });
        }
        Map<Long, Long> holders = new HashMap<>();
        if (withCoverage && !allRoleIds.isEmpty()) {
            for (Object[] row : roleRepository.countActiveUsersByRoleInTenant(tenantId, allRoleIds)) {
                holders.put((Long) row[0], toLong(row[1]));
            }
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Workflow wf : workflows) {
            List<WorkflowStep> steps = stepsByWf.getOrDefault(wf.getId(), List.of());
            List<Map<String, Object>> stepRows = new ArrayList<>();
            LinkedHashSet<String> sides = new LinkedHashSet<>();
            LinkedHashMap<Long, Map<String, Object>> roleRows = new LinkedHashMap<>();

            for (WorkflowStep s : steps) {
                String side = s.getSide() == null ? "ORGANIZATION" : s.getSide().toUpperCase();
                sides.add(side);
                List<Map<String, Object>> stepRoles = new ArrayList<>();
                for (Long rid : roleIdsByStep.getOrDefault(s.getId(), List.of())) {
                    Role r = roles.get(rid);
                    if (r == null) continue;
                    Map<String, Object> rr = new LinkedHashMap<>();
                    rr.put("id", r.getId());
                    rr.put("name", r.getName());
                    rr.put("side", r.getSide() == null ? null : r.getSide().name());
                    stepRoles.add(rr);
                    roleRows.computeIfAbsent(r.getId(), k -> {
                        Map<String, Object> agg = new LinkedHashMap<>(rr);
                        agg.put("steps", new ArrayList<Integer>());
                        if (withCoverage) agg.put("userCount", holders.getOrDefault(r.getId(), 0L));
                        return agg;
                    });
                    @SuppressWarnings("unchecked")
                    List<Integer> usedIn = (List<Integer>) roleRows.get(r.getId()).get("steps");
                    usedIn.add(s.getStepOrder());
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", s.getId());
                m.put("order", s.getStepOrder());
                m.put("name", s.getName());
                m.put("description", s.getDescription());
                m.put("side", side);
                m.put("slaHours", s.getSlaHours());
                m.put("approvalType", s.getApprovalType() == null ? null : s.getApprovalType().name());
                // How the task finds its people and when the step is done — the
                // catalogue and the guide drawer explain these in plain words.
                m.put("minApprovals", s.getMinApprovalsRequired());
                m.put("actorResolution", s.getActorResolution() == null ? null : s.getActorResolution().name());
                m.put("assignerResolution", s.getAssignerResolution() == null ? null : s.getAssignerResolution().name());
                m.put("assignableSide", s.getAssignableSide());
                m.put("parallel", s.isParallel());
                m.put("stepAction", s.getStepAction() == null ? null : s.getStepAction().name());
                m.put("automated", notBlank(s.getAutomatedAction()) || "SYSTEM".equals(side));
                m.put("autoCompletes", s.isAutoCompleteActorOnSubmit());
                m.put("optional", s.isOptional());
                m.put("transitions", transitions(s.getStepUiOverrideJson()));
                m.put("roles", stepRoles);
                m.put("demoStartSeconds", s.getDemoStartSeconds());
                m.put("guideText", s.getGuideText());
                stepRows.add(m);
            }

            long[] u = usage.getOrDefault(wf.getId(), new long[]{0, 0});
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("id", wf.getId());
            w.put("name", wf.getName());
            w.put("description", wf.getDescription());
            w.put("entityType", wf.getEntityType());
            w.put("version", wf.getVersion());
            w.put("tenantOwned", wf.getTenantId() != null);
            w.put("stepCount", steps.size());
            w.put("sides", sides.stream().sorted(Comparator.comparingInt(x -> {
                int i = SIDE_ORDER.indexOf(x); return i < 0 ? 99 : i;
            })).toList());
            w.put("roleCount", roleRows.size());
            w.put("roles", new ArrayList<>(roleRows.values()));
            w.put("usage", Map.of("total", u[0], "open", u[1]));
            w.put("hasDemo", notBlank(wf.getDemoVideoKey()) || notBlank(wf.getDemoVideoUrl()));
            if (withCoverage) {
                long unstaffed = roleRows.values().stream()
                        .filter(r -> "ORGANIZATION".equals(r.get("side")))
                        .filter(r -> ((Long) r.get("userCount")) == 0L).count();
                w.put("unstaffedOrgRoles", unstaffed);
            }
            w.put("steps", stepRows);
            out.add(w);
        }
        return out;
    }

    private List<Map<String, Object>> chapters(Long workflowId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (WorkflowStep s : stepRepository.findByWorkflowIdOrderByStepOrderAsc(workflowId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stepId", s.getId());
            m.put("order", s.getStepOrder());
            m.put("name", s.getName());
            m.put("startSeconds", s.getDemoStartSeconds());
            m.put("side", s.getSide());
            m.put("guideText", s.getGuideText());
            out.add(m);
        }
        return out;
    }

    /** The action keys a step lists as the buttons that complete it, if any. */
    private List<String> transitions(String stepUiOverrideJson) {
        if (stepUiOverrideJson == null || stepUiOverrideJson.isBlank()) return List.of();
        try {
            JsonNode n = objectMapper.readTree(stepUiOverrideJson).get("transitions");
            if (n == null || !n.isArray()) return List.of();
            List<String> keys = new ArrayList<>();
            n.forEach(x -> { if (x.isTextual()) keys.add(x.asText()); });
            return keys;
        } catch (Exception e) {
            return List.of();
        }
    }

    private void guardPlatformAdmin() {
        if (!utilityService.isSystemUser())
            throw new BusinessException("FORBIDDEN", "Only Platform Admin can manage workflow videos", HttpStatus.FORBIDDEN);
    }

    private static int safeVersion(Workflow w) { return w.getVersion() == null ? 0 : w.getVersion(); }
    private static long toLong(Object o) { return o == null ? 0L : ((Number) o).longValue(); }
    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
    private static String blankToNull(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }
}