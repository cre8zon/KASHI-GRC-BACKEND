package com.kashi.grc.uiconfig.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.dto.PaginatedResponse;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.repository.DbRepository;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.uiconfig.domain.NotificationTemplate;
import com.kashi.grc.uiconfig.repository.NotificationTemplateRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Notification Template Admin — DB-driven template management.
 *
 * Templates are matched by eventKey when TemplateNotificationService.send() is called.
 * Placeholders use {{key}} syntax resolved at send time from the context map.
 *
 * GET    /v1/admin/notification-templates             — list (paginated, filter by isActive)
 * GET    /v1/admin/notification-templates/{id}        — get single
 * POST   /v1/admin/notification-templates             — create
 * PUT    /v1/admin/notification-templates/{id}        — update
 * DELETE /v1/admin/notification-templates/{id}        — delete
 * POST   /v1/admin/notification-templates/preview     — dry-run render (no notification sent)
 *
 * Follows exact same pattern as UiAdminController for consistency.
 */
@Slf4j
@RestController
@RequestMapping("/v1/admin/notification-templates")
@Tag(name = "Notification Templates (Platform Admin)", description = "DB-driven notification content and routing")
@RequiredArgsConstructor
public class NotificationTemplateAdminController {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)\\}\\}");

    private final NotificationTemplateRepository templateRepository;
    private final DbRepository                   dbRepository;
    private final UtilityService                 utilityService;

    // ══════════════════════════════════════════════════════════════
    // LIST
    // ══════════════════════════════════════════════════════════════

    @GetMapping
    @Operation(summary = "List notification templates — paginated, filterable by isActive and eventKey search")
    public ResponseEntity<ApiResponse<PaginatedResponse<Map<String, Object>>>> list(
            @RequestParam Map<String, String> allParams) {
        Long tenantId    = utilityService.getLoggedInDataContext().getTenantId();
        String search    = allParams.getOrDefault("search", "");
        String activeStr = allParams.getOrDefault("isActive", "");

        return ResponseEntity.ok(ApiResponse.success(dbRepository.findAll(
                NotificationTemplate.class,
                utilityService.getpageDetails(allParams),
                (cb, root) -> {
                    List<jakarta.persistence.criteria.Predicate> p = new ArrayList<>();
                    // Global (tenantId null) OR tenant-specific
                    p.add(cb.or(
                            cb.isNull(root.get("tenantId")),
                            cb.equal(root.get("tenantId"), tenantId)));
                    if (!search.isBlank())
                        p.add(cb.like(cb.lower(root.get("eventKey")), "%" + search.toLowerCase() + "%"));
                    if ("true".equalsIgnoreCase(activeStr))
                        p.add(cb.isTrue(root.get("isActive")));
                    else if ("false".equalsIgnoreCase(activeStr))
                        p.add(cb.isFalse(root.get("isActive")));
                    return p;
                },
                (cb, root) -> Map.of("eventkey", root.get("eventKey")),
                t -> toMap(t))));
    }

    // ══════════════════════════════════════════════════════════════
    // GET SINGLE
    // ══════════════════════════════════════════════════════════════

    @GetMapping("/{id}")
    @Operation(summary = "Get a single notification template by ID")
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(@PathVariable Long id) {
        NotificationTemplate t = templateRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("NotificationTemplate", id));
        return ResponseEntity.ok(ApiResponse.success(toMap(t)));
    }

    // ══════════════════════════════════════════════════════════════
    // CREATE
    // ══════════════════════════════════════════════════════════════

    @PostMapping
    @Operation(summary = "Create a notification template — matched by eventKey at send time")
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @Valid @RequestBody TemplateRequest req) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();

        NotificationTemplate t = NotificationTemplate.builder()
                .eventKey(req.getEventKey().toUpperCase())
                .titleTemplate(req.getTitleTemplate())
                .bodyTemplate(req.getBodyTemplate())
                .icon(req.getIcon() != null ? req.getIcon() : "Bell")
                .colorTag(req.getColorTag() != null ? req.getColorTag() : "blue")
                .actionUrl(req.getActionUrl())
                .isActive(req.isActive())
                .tenantId(tenantId)
                .build();

        templateRepository.save(t);
        log.info("[NOTIF-TEMPLATE] Created eventKey={}", t.getEventKey());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(toSimpleMap("id", t.getId(), "eventKey", t.getEventKey())));
    }

    // ══════════════════════════════════════════════════════════════
    // UPDATE
    // ══════════════════════════════════════════════════════════════

    @PutMapping("/{id}")
    @Operation(summary = "Update a notification template")
    public ResponseEntity<ApiResponse<Map<String, Object>>> update(
            @PathVariable Long id, @RequestBody TemplateRequest req) {
        NotificationTemplate t = templateRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("NotificationTemplate", id));
        if (req.getTitleTemplate() != null) t.setTitleTemplate(req.getTitleTemplate());
        if (req.getBodyTemplate()  != null) t.setBodyTemplate(req.getBodyTemplate());
        if (req.getIcon()          != null) t.setIcon(req.getIcon());
        if (req.getColorTag()      != null) t.setColorTag(req.getColorTag());
        if (req.getActionUrl()     != null) t.setActionUrl(req.getActionUrl());
        t.setActive(req.isActive());
        templateRepository.save(t);
        return ResponseEntity.ok(ApiResponse.success(
                toSimpleMap("id", t.getId(), "eventKey", t.getEventKey())));
    }

    // ══════════════════════════════════════════════════════════════
    // DELETE
    // ══════════════════════════════════════════════════════════════

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a template — notifications fall back to raw message strings")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        templateRepository.deleteById(id);
        return ResponseEntity.ok(ApiResponse.success());
    }

    // ══════════════════════════════════════════════════════════════
    // PREVIEW (dry-run — no notification sent)
    // ══════════════════════════════════════════════════════════════

    @PostMapping("/preview")
    @Operation(summary = "Dry-run render — resolves {{placeholders}} without sending a notification")
    public ResponseEntity<ApiResponse<Map<String, Object>>> preview(
            @RequestBody PreviewRequest req) {
        Map<String, String> ctx = req.getContext() != null ? req.getContext() : Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title",     resolve(req.getTitleTemplate(), ctx));
        result.put("body",      resolve(req.getBodyTemplate(), ctx));
        result.put("actionUrl", resolve(req.getActionUrl(), ctx));
        result.put("icon",      req.getIcon() != null ? req.getIcon() : "Bell");
        result.put("colorTag",  req.getColorTag() != null ? req.getColorTag() : "blue");
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Replace all {{key}} placeholders in a template with context values.
     * Unmatched placeholders are left as-is.
     */
    private String resolve(String template, Map<String, String> context) {
        if (template == null || template.isBlank()) return template;
        StringBuffer sb = new StringBuffer();
        Matcher m = PLACEHOLDER.matcher(template);
        while (m.find()) {
            String key = m.group(1);
            String val = context.getOrDefault(key, m.group(0));
            m.appendReplacement(sb, Matcher.quoteReplacement(val));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private Map<String, Object> toMap(NotificationTemplate t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",            t.getId());
        m.put("eventKey",      t.getEventKey());
        m.put("titleTemplate", t.getTitleTemplate() != null ? t.getTitleTemplate() : "");
        m.put("bodyTemplate",  t.getBodyTemplate()  != null ? t.getBodyTemplate()  : "");
        m.put("icon",          t.getIcon()          != null ? t.getIcon()          : "Bell");
        m.put("colorTag",      t.getColorTag()      != null ? t.getColorTag()      : "blue");
        m.put("actionUrl",     t.getActionUrl()     != null ? t.getActionUrl()     : "");
        m.put("isActive",      t.isActive());
        m.put("tenantId",      t.getTenantId());
        m.put("createdAt",     t.getCreatedAt());
        m.put("updatedAt",     t.getUpdatedAt());
        return m;
    }

    private Map<String, Object> toSimpleMap(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length - 1; i += 2) m.put(kv[i].toString(), kv[i + 1]);
        return m;
    }

    // ── Inner request DTOs ────────────────────────────────────────────────────

    @Data
    public static class TemplateRequest {
        @NotBlank private String  eventKey;
        @NotBlank private String  titleTemplate;
        private String            bodyTemplate;
        private String            icon;       // Lucide icon name — default Bell
        private String            colorTag;   // blue | green | amber | red | purple | gray
        private String            actionUrl;  // supports {{placeholder}} for deep-link routing
        private boolean           active = true;
    }

    @Data
    public static class PreviewRequest {
        private String              titleTemplate;
        private String              bodyTemplate;
        private String              actionUrl;
        private String              icon;
        private String              colorTag;
        /**
         * Placeholder values to resolve in the templates.
         * e.g. { "userName": "Alice", "stepName": "Risk Approval", "taskId": "99" }
         */
        private Map<String, String> context;
    }
}