package com.kashi.grc.module.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.document.service.StorageService;
import com.kashi.grc.module.domain.ModuleBlueprint;
import com.kashi.grc.module.repository.ModuleBlueprintRepository;
import com.kashi.grc.uiconfig.domain.UiAction;
import com.kashi.grc.uiconfig.repository.UiActionRepository;
import com.kashi.grc.usermanagement.domain.User;
import com.kashi.grc.usermanagement.repository.RoleRepository;
import com.kashi.grc.workflow.repository.WorkflowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * "How this module works" — for every module, with or without a workflow.
 *
 *   lifecycle   the module's own states and moves (module_blueprints.status_flow_json)
 *   actions     what can be done on its list and detail screens (ui_actions), and
 *               which of the tenant's roles may do each (via required_permission)
 *   guide       summary, connections to other modules and video chapters, written
 *               by Platform Admin (module_blueprints.guide_json)
 *   video       an uploaded file or a link, like a workflow walkthrough
 *
 * The guide text and video live on the PLATFORM blueprint (tenant_id NULL) so
 * one edit reaches every organisation; a tenant's own blueprint copy is used
 * for everything else, as at runtime.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModuleGuideService {

    private final ModuleBlueprintRepository blueprintRepository;
    private final UiActionRepository        actionRepository;
    private final RoleRepository            roleRepository;
    private final WorkflowRepository        workflowRepository;
    private final StorageService            storageService;
    private final UtilityService            utilityService;
    private final ObjectMapper              objectMapper;

    // ══════════════════════ READ ═════════════════════════════════════════════

    @Transactional(readOnly = true)
    public Map<String, Object> guide(String entityType) {
        User user = utilityService.getLoggedInDataContext();
        Long tenantId = user.getTenantId();
        String et = norm(entityType);
        ModuleBlueprint bp = runtimeBlueprint(et, tenantId);
        ModuleBlueprint platform = blueprintRepository.findByEntityTypeIgnoreCaseAndTenantIdIsNull(et).orElse(bp);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entityType", bp.getEntityType());
        out.put("displayName", bp.getDisplayName());
        out.put("displayNamePlural", bp.getDisplayNamePlural());
        out.put("supportsWorkflow", bp.isSupportsWorkflow());

        JsonNode guide = readJson(firstNonBlank(bp.getGuideJson(), platform.getGuideJson()));
        out.put("summary", guide != null && guide.hasNonNull("summary") ? guide.get("summary").asText() : null);
        out.put("connections", guide != null && guide.has("connections") ? objectMapper.convertValue(guide.get("connections"), List.class) : List.of());
        out.put("chapters", guide != null && guide.has("chapters") ? objectMapper.convertValue(guide.get("chapters"), List.class) : List.of());
        out.put("hasDemo", notBlank(firstNonBlank(bp.getDemoVideoKey(), platform.getDemoVideoKey()))
                || notBlank(firstNonBlank(bp.getDemoVideoUrl(), platform.getDemoVideoUrl())));

        out.put("lifecycle", lifecycle(bp.getStatusFlowJson()));
        out.put("actions", actions(bp, tenantId));
        out.put("workflowCount", workflowRepository.findByTenantIdIsNullAndEntityTypeAndIsActiveTrue(et).size()
                + (tenantId == null ? 0 : workflowRepository.findByTenantIdAndEntityTypeAndIsActiveTrue(tenantId, et).size()));
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> demo(String entityType) {
        User user = utilityService.getLoggedInDataContext();
        String et = norm(entityType);
        ModuleBlueprint bp = runtimeBlueprint(et, user.getTenantId());
        ModuleBlueprint platform = blueprintRepository.findByEntityTypeIgnoreCaseAndTenantIdIsNull(et).orElse(bp);
        String key = firstNonBlank(bp.getDemoVideoKey(), platform.getDemoVideoKey());
        String url = firstNonBlank(bp.getDemoVideoUrl(), platform.getDemoVideoUrl());
        Map<String, Object> out = new LinkedHashMap<>();
        if (notBlank(key)) { out.put("kind", "FILE"); out.put("url", storageService.generateDownloadUrl(key, false, null)); }
        else if (notBlank(url)) { out.put("kind", "LINK"); out.put("url", url); }
        else { out.put("kind", "NONE"); out.put("url", null); }
        JsonNode guide = readJson(firstNonBlank(bp.getGuideJson(), platform.getGuideJson()));
        out.put("chapters", guide != null && guide.has("chapters") ? objectMapper.convertValue(guide.get("chapters"), List.class) : List.of());
        return out;
    }

    // ══════════════════════ PLATFORM ADMIN ═══════════════════════════════════

    /** Raw guide settings of the platform blueprint, for the editor. */
    @Transactional(readOnly = true)
    public Map<String, Object> settings(String entityType) {
        guardPlatformAdmin();
        ModuleBlueprint bp = platformBlueprint(norm(entityType));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entityType", bp.getEntityType());
        out.put("displayName", bp.getDisplayName());
        out.put("guide", readJson(bp.getGuideJson()) == null ? Map.of() : objectMapper.convertValue(readJson(bp.getGuideJson()), Map.class));
        out.put("demoVideoKey", bp.getDemoVideoKey());
        out.put("demoVideoUrl", bp.getDemoVideoUrl());
        out.put("lifecycle", lifecycle(bp.getStatusFlowJson()));
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> demoUploadUrl(String entityType, String fileName, String mimeType, Long sizeBytes) {
        guardPlatformAdmin();
        ModuleBlueprint bp = platformBlueprint(norm(entityType));
        User user = utilityService.getLoggedInDataContext();
        StorageService.PresignedUploadResult r = storageService.generateUploadUrl(
                user.getTenantId(), user.getId(), fileName, mimeType, sizeBytes,
                "WORKFLOW_DEMO_VIDEO", "MODULE_BLUEPRINT", bp.getId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("presignedUrl", r.getPresignedUrl());
        out.put("s3Key", r.getS3Key());
        out.put("mimeType", r.getEffectiveMimeType());
        out.put("expiresAt", String.valueOf(r.getExpiresAt()));
        return out;
    }

    /**
     * Saves guide text and video on the platform blueprint. Keys left out are
     * not touched; "guide" replaces the whole guide object.
     */
    @Transactional
    public Map<String, Object> save(String entityType, Map<String, Object> body) {
        guardPlatformAdmin();
        ModuleBlueprint bp = platformBlueprint(norm(entityType));
        if (body.containsKey("guide")) {
            Object g = body.get("guide");
            try {
                String json = g == null ? null : objectMapper.writeValueAsString(g);
                if (json != null && json.length() > 20_000)
                    throw new BusinessException("GUIDE_TOO_LONG", "The guide is too long (20,000 characters max)", HttpStatus.BAD_REQUEST);
                bp.setGuideJson(json);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new BusinessException("GUIDE_INVALID", "The guide could not be saved", HttpStatus.BAD_REQUEST);
            }
        }
        if (body.containsKey("demoVideoKey")) bp.setDemoVideoKey(blankToNull(body.get("demoVideoKey")));
        if (body.containsKey("demoVideoUrl")) {
            String url = blankToNull(body.get("demoVideoUrl"));
            if (url != null && !url.startsWith("https://"))
                throw new BusinessException("DEMO_URL_INVALID", "The video link must start with https://", HttpStatus.BAD_REQUEST);
            bp.setDemoVideoUrl(url);
        }
        blueprintRepository.save(bp);
        log.info("[MODULE-GUIDE] saved | entityType={}", bp.getEntityType());
        return settings(entityType);
    }

    // ══════════════════════ INTERNALS ════════════════════════════════════════

    /** Same resolution as the module page: an active tenant copy, else the active platform one. */
    private ModuleBlueprint runtimeBlueprint(String et, Long tenantId) {
        return blueprintRepository.findByEntityTypeIgnoreCaseAndTenantId(et, tenantId)
                .filter(ModuleBlueprint::isActive)
                .or(() -> blueprintRepository.findByEntityTypeIgnoreCaseAndTenantIdIsNull(et).filter(ModuleBlueprint::isActive))
                .orElseThrow(() -> new ResourceNotFoundException("ModuleBlueprint", "entityType", et));
    }

    private ModuleBlueprint platformBlueprint(String et) {
        return blueprintRepository.findByEntityTypeIgnoreCaseAndTenantIdIsNull(et)
                .orElseThrow(() -> new ResourceNotFoundException("ModuleBlueprint", "entityType", et));
    }

    /**
     * {"statuses": [...], "transitions": [{from, to, label, actionKey?}]} →
     * {states: [{key, label}], moves: [{from, to, label}]}. Statuses may be
     * plain strings or {value|key, label} objects.
     */
    private Map<String, Object> lifecycle(String statusFlowJson) {
        List<Map<String, Object>> states = new ArrayList<>();
        List<Map<String, Object>> moves = new ArrayList<>();
        JsonNode sf = readJson(statusFlowJson);
        if (sf != null) {
            JsonNode st = sf.get("statuses");
            if (st != null && st.isArray()) {
                st.forEach(n -> {
                    String key = n.isTextual() ? n.asText()
                            : text(n, "value", text(n, "key", text(n, "status", null)));
                    if (key == null) return;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("key", key);
                    m.put("label", n.isTextual() ? humanise(key) : text(n, "label", humanise(key)));
                    if (!n.isTextual() && n.hasNonNull("colorTag")) m.put("colorTag", n.get("colorTag").asText());
                    states.add(m);
                });
            }
            JsonNode tr = sf.get("transitions");
            if (tr != null && tr.isArray()) {
                tr.forEach(n -> {
                    String to = text(n, "to", null);
                    if (to == null) return;
                    Map<String, Object> m = new LinkedHashMap<>();
                    JsonNode from = n.get("from");
                    m.put("from", from == null ? null : from.isArray() ? objectMapper.convertValue(from, List.class) : from.asText());
                    m.put("to", to);
                    m.put("label", text(n, "label", humanise(to)));
                    moves.add(m);
                });
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("states", states);
        out.put("moves", moves);
        return out;
    }

    /**
     * The actions on the module's list and detail screens, each with the roles
     * that may use it — read from required_permission, so a renamed role or a
     * new one with the permission shows up without any change here.
     */
    private List<Map<String, Object>> actions(ModuleBlueprint bp, Long tenantId) {
        List<UiAction> all = new ArrayList<>();
        if (notBlank(bp.getListScreenKey())) all.addAll(actionRepository.findByScreenAndTenant(bp.getListScreenKey(), tenantId));
        if (notBlank(bp.getDetailScreenKey())) all.addAll(actionRepository.findByScreenAndTenant(bp.getDetailScreenKey(), tenantId));

        // One row per action key (a tenant override shadows the platform one).
        LinkedHashMap<String, UiAction> byKey = new LinkedHashMap<>();
        for (UiAction a : all) {
            String k = a.getActionKey() == null ? "id:" + a.getId() : a.getActionKey();
            UiAction cur = byKey.get(k);
            if (cur == null || (cur.getTenantId() == null && a.getTenantId() != null)) byKey.put(k, a);
        }

        Set<String> codes = new HashSet<>();
        byKey.values().forEach(a -> { if (notBlank(a.getRequiredPermission())) codes.add(a.getRequiredPermission()); });
        Map<String, List<Map<String, Object>>> rolesByCode = new HashMap<>();
        Set<Long> roleIds = new HashSet<>();
        for (Object[] row : roleRepository.findRolesHoldingPermissions(tenantId, codes)) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", row[0]);
            r.put("name", row[1]);
            r.put("side", row[2] == null ? null : row[2].toString());
            rolesByCode.computeIfAbsent((String) row[3], k -> new ArrayList<>()).add(r);
            roleIds.add((Long) row[0]);
        }
        Map<Long, Long> holders = new HashMap<>();
        for (Object[] row : roleRepository.countActiveUsersByRoleInTenant(tenantId, roleIds)) {
            holders.put((Long) row[0], ((Number) row[1]).longValue());
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (UiAction a : byKey.values()) {
            if (Boolean.FALSE.equals(a.getIsActive())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("actionKey", a.getActionKey());
            m.put("label", labelOf(a));
            m.put("screen", Objects.equals(a.getScreenKey(), bp.getListScreenKey()) ? "LIST" : "DETAIL");
            m.put("requiredPermission", a.getRequiredPermission());
            m.put("allowedSides", a.getAllowedSides());
            List<Map<String, Object>> roles = new ArrayList<>();
            for (Map<String, Object> r : rolesByCode.getOrDefault(a.getRequiredPermission(), List.of())) {
                Map<String, Object> rr = new LinkedHashMap<>(r);
                rr.put("userCount", holders.getOrDefault((Long) r.get("id"), 0L));
                roles.add(rr);
            }
            m.put("roles", roles);
            out.add(m);
        }
        return out;
    }

    private String labelOf(UiAction a) {
        JsonNode p = readJson(a.getPayloadTemplateJson());
        String label = p != null && p.hasNonNull("__label") ? p.get("__label").asText() : a.getLabel();
        return label == null ? humanise(a.getActionKey()) : label.replaceFirst("^\\s*\\+\\s*", "");
    }

    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) return null;
        try { return objectMapper.readTree(json); } catch (Exception e) { return null; }
    }

    private void guardPlatformAdmin() {
        if (!utilityService.isSystemUser())
            throw new BusinessException("FORBIDDEN", "Only Platform Admin can edit module guides", HttpStatus.FORBIDDEN);
    }

    private static String text(JsonNode n, String field, String dflt) {
        return n != null && n.hasNonNull(field) ? n.get(field).asText() : dflt;
    }
    private static String norm(String s) {
        if (s == null || s.isBlank()) throw new BusinessException("ENTITY_TYPE_REQUIRED", "entityType is required", HttpStatus.BAD_REQUEST);
        return s.trim().toUpperCase();
    }
    private static String humanise(String s) {
        if (s == null || s.isBlank()) return s;
        String t = s.replace('_', ' ').toLowerCase();
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }
    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
    private static String firstNonBlank(String a, String b) { return notBlank(a) ? a : b; }
    private static String blankToNull(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }
}
