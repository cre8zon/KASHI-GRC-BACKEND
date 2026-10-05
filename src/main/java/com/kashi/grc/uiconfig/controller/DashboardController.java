package com.kashi.grc.uiconfig.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.exception.ForbiddenException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.exception.ValidationException;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.uiconfig.domain.Dashboard;
import com.kashi.grc.uiconfig.domain.DashboardWidget;
import com.kashi.grc.uiconfig.repository.DashboardRepository;
import com.kashi.grc.uiconfig.repository.DashboardWidgetRepository;
import com.kashi.grc.usermanagement.domain.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Dashboards: which exist, what is on them, and who may see them.
 *
 * ── THE SHAPE OF THIS API IS THE POINT ────────────────────────────────────
 * The brief was that extending dashboard authoring to USERS later should need
 * little new development. That only holds if the read path and the write path
 * speak the same language now — so a dashboard the platform ships and one a
 * user builds are the same row, differing by scope and owner_user_id, and both
 * are served and edited through these endpoints.
 *
 * ── WHAT IS DELIBERATELY NOT HERE ─────────────────────────────────────────
 * No @PreAuthorize: this module's controllers gate in code, and mixing the two
 * styles is how a gap appears. Read visibility is decided in exactly one place,
 * UiConfigServiceImpl.isDashboardVisible, and all three read endpoints below
 * delegate to it. Writes gate on tenant ownership here, in requireOwned.
 */
@Slf4j
@RestController
@RequestMapping("/v1/dashboards")
@RequiredArgsConstructor
@Tag(name = "Dashboards", description = "Dashboard definitions and their widgets")
public class DashboardController {

    private final DashboardRepository       dashboardRepository;
    private final DashboardWidgetRepository widgetRepository;
    private final UtilityService            utilityService;
    private final com.kashi.grc.uiconfig.service.UiConfigService uiConfigService;

    // ═════════════════════════════════════════════════════════════════════════
    // READ — delegated, because the permission logic must not be duplicated
    //
    // extractPermissions in UiConfigServiceImpl walks roles to permissions and
    // then applies permission_grants, which supports explicit REVOKE. A second
    // implementation here would eventually grant somebody a widget their role
    // had been specifically denied — so these three are one-liners on purpose.
    // ═════════════════════════════════════════════════════════════════════════

    @GetMapping
    @Operation(summary = "Dashboards visible to the caller, optionally for one module")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String scope) {
        return ResponseEntity.ok(ApiResponse.success(
                uiConfigService.listDashboards(entityType, scope)));
    }

    @GetMapping("/default")
    @Operation(summary = "The default dashboard for a module, or the global one")
    public ResponseEntity<ApiResponse<Map<String, Object>>> defaultFor(
            @RequestParam(required = false) String entityType) {
        return ResponseEntity.ok(ApiResponse.success(
                uiConfigService.defaultDashboard(entityType)));
    }

    @GetMapping("/{key}")
    @Operation(summary = "One dashboard and the widgets this caller may see")
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(@PathVariable String key) {
        return ResponseEntity.ok(ApiResponse.success(uiConfigService.getDashboard(key)));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // WRITE — the same endpoints a user-facing editor will use
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Creates a dashboard for this tenant, or a personal one.
     *
     * A caller can only ever create within their own tenant: tenantId is taken
     * from the session and never from the payload, and scope PERSONAL forces
     * ownerUserId to the caller. Those two lines are what make it safe to open
     * this endpoint to ordinary users later without revisiting it.
     */
    @PostMapping
    @Operation(summary = "Create a dashboard")
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @RequestBody Map<String, Object> body) {

        User ctx = utilityService.getLoggedInDataContext();
        String key = str(body.get("dashboardKey"));
        String name = str(body.get("name"));
        if (key == null || name == null) {
            throw new ValidationException("A dashboard needs a key and a name.");
        }

        Dashboard.Scope scope = body.get("scope") != null
                ? Dashboard.Scope.valueOf(String.valueOf(body.get("scope")).toUpperCase())
                : Dashboard.Scope.MODULE;

        if (dashboardRepository.findByDashboardKeyAndTenantId(key, ctx.getTenantId()).isPresent()) {
            throw new ValidationException("A dashboard with the key '" + key + "' already exists here.");
        }

        Dashboard d = Dashboard.builder()
                .dashboardKey(key)
                .name(name)
                .description(str(body.get("description")))
                .icon(str(body.get("icon")))
                .scope(scope)
                .entityType(str(body.get("entityType")))
                .ownerUserId(scope == Dashboard.Scope.PERSONAL ? ctx.getId() : null)
                .isDefault(Boolean.TRUE.equals(body.get("isDefault")))
                .allowedSidesJson(str(body.get("allowedSidesJson")))
                .requiredPermission(str(body.get("requiredPermission")))
                .roleAccessJson(str(body.get("roleAccessJson")))
                .gridCols(body.get("gridCols") != null
                        ? Integer.parseInt(String.valueOf(body.get("gridCols"))) : 12)
                .sortOrder(body.get("sortOrder") != null
                        ? Integer.parseInt(String.valueOf(body.get("sortOrder"))) : 0)
                .isActive(true)
                .tenantId(ctx.getTenantId())      // NEVER from the payload
                .createdBy(ctx.getId())
                .build();

        dashboardRepository.save(d);
        log.info("[DASHBOARD] Created | key={} | scope={} | tenant={} | by={}",
                key, scope, ctx.getTenantId(), ctx.getId());
        return ResponseEntity.ok(ApiResponse.success(toSummary(d)));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a dashboard the caller owns")
    public ResponseEntity<ApiResponse<Map<String, Object>>> update(
            @PathVariable Long id, @RequestBody Map<String, Object> body) {

        User ctx = utilityService.getLoggedInDataContext();
        Dashboard d = requireOwned(id, ctx);

        if (body.containsKey("name"))        d.setName(str(body.get("name")));
        if (body.containsKey("description")) d.setDescription(str(body.get("description")));
        if (body.containsKey("icon"))        d.setIcon(str(body.get("icon")));
        if (body.containsKey("isDefault"))   d.setDefault(Boolean.TRUE.equals(body.get("isDefault")));
        if (body.containsKey("roleAccessJson")) d.setRoleAccessJson(str(body.get("roleAccessJson")));
        if (body.containsKey("allowedSidesJson")) d.setAllowedSidesJson(str(body.get("allowedSidesJson")));
        if (body.containsKey("requiredPermission")) d.setRequiredPermission(str(body.get("requiredPermission")));
        if (body.containsKey("sortOrder")) {
            d.setSortOrder(Integer.parseInt(String.valueOf(body.get("sortOrder"))));
        }
        d.setUpdatedAt(LocalDateTime.now());
        dashboardRepository.save(d);
        return ResponseEntity.ok(ApiResponse.success(toSummary(d)));
    }

    /**
     * Deactivates rather than deletes, and refuses while widgets remain.
     *
     * Deleting a dashboard out from under its widgets would orphan them with no
     * way back to a dashboard, which is precisely the state migration 30 had to
     * clean up. Refusing costs one extra click and prevents that.
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "Deactivate a dashboard — refuses while it still holds widgets")
    public ResponseEntity<ApiResponse<Void>> deactivate(@PathVariable Long id) {
        User ctx = utilityService.getLoggedInDataContext();
        Dashboard d = requireOwned(id, ctx);

        long widgets = widgetRepository.countByDashboardId(d.getId());
        if (widgets > 0) {
            throw new ValidationException(
                    "This dashboard still holds " + widgets + " widget(s). Remove them first — "
                            + "deleting it now would leave them with no dashboard to belong to.");
        }
        d.setActive(false);
        d.setUpdatedAt(LocalDateTime.now());
        dashboardRepository.save(d);
        return ResponseEntity.ok(ApiResponse.success());
    }

    // ═════════════════════════════════════════════════════════════════════════
    // TENANT CUSTOMISATION
    //
    // The platform ships dashboards; a tenant makes them theirs. The model is
    // the one the risk and policy libraries already use: you do not EDIT a
    // platform row, you take a copy scoped to your tenant, and from then on
    // yours shadows it.
    //
    // dashboards and dashboard_widgets are both unique on (key, tenant_id), so
    // the copy keeps the SAME key. That is what says "our version of that"
    // rather than leaving two unrelated rows with no connection between them.
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Takes a private copy of a platform dashboard, with all its widgets.
     *
     * Idempotent: if this tenant already has a dashboard with that key, it is
     * returned rather than duplicated. Pressing Customise twice should not
     * produce two dashboards, and somebody will press it twice.
     */
    @PostMapping("/{key}/customise")
    @Operation(summary = "Copy a platform dashboard to this tenant so it can be edited")
    public ResponseEntity<ApiResponse<Map<String, Object>>> customise(@PathVariable String key) {
        User ctx = utilityService.getLoggedInDataContext();
        Long tenantId = ctx.getTenantId();

        Optional<Dashboard> existing = dashboardRepository.findByDashboardKeyAndTenantId(key, tenantId);
        if (existing.isPresent()) {
            return ResponseEntity.ok(ApiResponse.success(uiConfigService.getDashboard(key)));
        }

        Dashboard source = dashboardRepository.findByKeyForTenant(key, tenantId).stream()
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Dashboard", "dashboardKey", key));

        Dashboard copy = Dashboard.builder()
                .dashboardKey(source.getDashboardKey())
                .name(source.getName())
                .description(source.getDescription())
                .icon(source.getIcon())
                .scope(source.getScope())
                .entityType(source.getEntityType())
                .isDefault(source.isDefault())
                .allowedSidesJson(source.getAllowedSidesJson())
                .requiredPermission(source.getRequiredPermission())
                .roleAccessJson(source.getRoleAccessJson())
                .gridCols(source.getGridCols())
                .sortOrder(source.getSortOrder())
                .isActive(true)
                .tenantId(tenantId)
                .createdBy(ctx.getId())
                .build();
        dashboardRepository.save(copy);

        // The dashboard check above is not enough on its own. Two requests can
        // pass it concurrently — a double click, or React's StrictMode double
        // effect in development — and each then copies the full widget set,
        // giving every widget twice and React a duplicate-key warning per pair.
        // Checking per widget closes that: the second pass finds them present
        // and copies nothing.
        Set<String> alreadyThere = widgetRepository
                .findByDashboardIdAndIsActiveTrueOrderBySortOrderAsc(copy.getId()).stream()
                .map(DashboardWidget::getWidgetKey)
                .collect(java.util.stream.Collectors.toSet());

        int copied = 0;
        for (DashboardWidget w : widgetRepository
                .findByDashboardIdAndIsActiveTrueOrderBySortOrderAsc(source.getId())) {
            if (!alreadyThere.add(w.getWidgetKey())) continue;
            widgetRepository.save(cloneWidget(w, copy.getId(), tenantId));
            copied++;
        }

        log.info("[DASHBOARD] Customised | key={} | tenant={} | {} widget(s) copied | by={}",
                key, tenantId, copied, ctx.getId());
        return ResponseEntity.ok(ApiResponse.success(uiConfigService.getDashboard(key)));
    }

    /**
     * Adds a widget to a dashboard this tenant owns.
     *
     * fromWidgetKey copies an existing widget's definition — the catalogue
     * path, which is how most widgets get added. Without it the body is taken
     * as a full definition.
     */
    @PostMapping("/{id}/widgets")
    @Operation(summary = "Add a widget, optionally copied from the catalogue")
    public ResponseEntity<ApiResponse<Map<String, Object>>> addWidget(
            @PathVariable Long id, @RequestBody Map<String, Object> body) {

        User ctx = utilityService.getLoggedInDataContext();
        Dashboard d = requireOwned(id, ctx);

        DashboardWidget w;
        String from = str(body.get("fromWidgetKey"));
        if (from != null) {
            DashboardWidget source = widgetRepository.findAll().stream()
                    .filter(x -> from.equals(x.getWidgetKey()))
                    .filter(x -> x.getTenantId() == null || ctx.getTenantId().equals(x.getTenantId()))
                    .findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException("Widget", "widgetKey", from));
            w = cloneWidget(source, d.getId(), ctx.getTenantId());
        } else {
            String key = str(body.get("widgetKey"));
            if (key == null) throw new ValidationException("A widget needs a key.");
            w = DashboardWidget.builder()
                    .widgetKey(key)
                    .widgetType(DashboardWidget.WidgetType.valueOf(
                            String.valueOf(body.getOrDefault("widgetType", "KPI_CARD"))))
                    .title(str(body.get("title")))
                    .subtitle(str(body.get("subtitle")))
                    .dataEndpoint(str(body.get("dataEndpoint")))
                    .dataPath(str(body.get("dataPath")))
                    .configJson(str(body.get("configJson")))
                    .allowedSidesJson(str(body.get("allowedSidesJson")))
                    .requiredPermission(str(body.get("requiredPermission")))
                    .clickThroughRoute(str(body.get("clickThroughRoute")))
                    .dashboardId(d.getId())
                    .tenantId(ctx.getTenantId())
                    .build();
        }

        // Always last, so adding never reshuffles what is already arranged.
        w.setSortOrder(nextSortOrder(d.getId(), w.getWidgetType()));
        if (body.get("gridCols") != null) {
            w.setGridCols(Integer.parseInt(String.valueOf(body.get("gridCols"))));
        }
        widgetRepository.save(w);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "widgetKey", w.getWidgetKey(), "id", w.getId())));
    }

    /**
     * Removes a widget from a tenant-owned dashboard.
     *
     * Hard delete, unlike almost everything else in this codebase — and
     * deliberately. A widget carries no history and no evidence; it is a view
     * preference. Soft-deleting would leave the Designer's list cluttered with
     * rows nobody can see and nobody wants, and "remove" that leaves the thing
     * present is the kind of half-measure people stop trusting.
     */
    @DeleteMapping("/widgets/{widgetId}")
    @Operation(summary = "Remove a widget from a dashboard this tenant owns")
    public ResponseEntity<ApiResponse<Void>> removeWidget(@PathVariable Long widgetId) {
        User ctx = utilityService.getLoggedInDataContext();
        DashboardWidget w = widgetRepository.findById(widgetId)
                .orElseThrow(() -> new ResourceNotFoundException("Widget", widgetId));

        if (w.getTenantId() == null) {
            throw new ForbiddenException(
                    "That widget belongs to the platform. Use Customise on the dashboard first — "
                            + "you will get your own copy to edit.");
        }
        if (!ctx.getTenantId().equals(w.getTenantId())) {
            throw new ResourceNotFoundException("Widget", widgetId);
        }
        widgetRepository.delete(w);
        return ResponseEntity.ok(ApiResponse.success());
    }

    /**
     * Saves a whole arrangement in one call: order and width for every widget.
     *
     * One request rather than one per widget, because a drag reorders several
     * at once and a half-applied layout is worse than none.
     */
    @PutMapping("/{id}/layout")
    @Operation(summary = "Save widget order and widths for a dashboard this tenant owns")
    public ResponseEntity<ApiResponse<Void>> saveLayout(
            @PathVariable Long id, @RequestBody List<Map<String, Object>> layout) {

        User ctx = utilityService.getLoggedInDataContext();
        Dashboard d = requireOwned(id, ctx);

        Map<Long, DashboardWidget> mine = new LinkedHashMap<>();
        for (DashboardWidget w : widgetRepository
                .findByDashboardIdAndIsActiveTrueOrderBySortOrderAsc(d.getId())) {
            mine.put(w.getId(), w);
        }

        int i = 0;
        for (Map<String, Object> row : layout) {
            Object rawId = row.get("id");
            if (rawId == null) continue;
            DashboardWidget w = mine.get(Long.parseLong(String.valueOf(rawId)));
            // Silently skipping an id that is not on this dashboard, rather
            // than failing the batch: a stale browser tab must not be able to
            // reject an otherwise valid arrangement.
            if (w == null) continue;
            w.setSortOrder((++i) * 10);
            if (row.get("gridCols") != null) {
                w.setGridCols(Integer.parseInt(String.valueOf(row.get("gridCols"))));
            }
            widgetRepository.save(w);
        }
        return ResponseEntity.ok(ApiResponse.success());
    }

    /**
     * Every widget this tenant could add — the catalogue behind "Add widget".
     *
     * Platform widgets plus the tenant's own, deduplicated by key with the
     * tenant's version winning, and grouped by the module they read from so
     * the picker is browsable rather than a flat list of sixty.
     */
    @GetMapping("/widget-catalogue")
    @Operation(summary = "Widgets available to add to a dashboard")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> catalogue() {
        User ctx = utilityService.getLoggedInDataContext();

        Map<String, DashboardWidget> byKey = new LinkedHashMap<>();
        for (DashboardWidget w : widgetRepository.findAll()) {
            if (w.getTenantId() != null && !ctx.getTenantId().equals(w.getTenantId())) continue;
            DashboardWidget seen = byKey.get(w.getWidgetKey());
            if (seen == null || (seen.getTenantId() == null && w.getTenantId() != null)) {
                byKey.put(w.getWidgetKey(), w);
            }
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (DashboardWidget w : byKey.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("widgetKey",  w.getWidgetKey());
            m.put("widgetType", w.getWidgetType());
            m.put("title",      w.getTitle());
            m.put("subtitle",   w.getSubtitle());
            m.put("gridCols",   w.getGridCols());
            // The endpoint's first path segment is a good enough grouping and
            // needs no extra column: /v1/incidents/stats -> incidents.
            String ep = w.getDataEndpoint() == null ? "" : w.getDataEndpoint();
            String[] parts = ep.split("/");
            m.put("group", parts.length > 2 ? parts[2] : "other");
            out.add(m);
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("group")))
                .thenComparing(m -> String.valueOf(m.get("title"))));
        return ResponseEntity.ok(ApiResponse.success(out));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════════════════════════════════


    /** A dashboard this caller may modify: their own tenant's, or their own personal one. */
    private Dashboard requireOwned(Long id, User ctx) {
        Dashboard d = dashboardRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Dashboard", id));

        if (d.getTenantId() == null) {
            throw new ForbiddenException(
                    "This is a platform dashboard. Create your own with the same key to override it.");
        }
        if (!ctx.getTenantId().equals(d.getTenantId())) {
            throw new ResourceNotFoundException("Dashboard", id);
        }
        if (d.getScope() == Dashboard.Scope.PERSONAL
                && !ctx.getId().equals(d.getOwnerUserId())) {
            throw new ForbiddenException("That dashboard belongs to somebody else.");
        }
        return d;
    }

    private Map<String, Object> toSummary(Dashboard d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",                d.getId());
        m.put("dashboardKey",      d.getDashboardKey());
        m.put("name",              d.getName());
        m.put("description",       d.getDescription());
        m.put("icon",              d.getIcon());
        m.put("scope",             d.getScope());
        m.put("entityType",        d.getEntityType());
        m.put("isDefault",         d.isDefault());
        m.put("gridCols",          d.getGridCols());
        m.put("sortOrder",         d.getSortOrder());
        m.put("platformProvided",  d.isPlatformProvided());
        m.put("editable",          !d.isPlatformProvided());
        return m;
    }


    /** A tenant-owned copy of a widget, same key, pointed at a new dashboard. */
    private DashboardWidget cloneWidget(DashboardWidget src, Long dashboardId, Long tenantId) {
        return DashboardWidget.builder()
                .dashboardId(dashboardId)
                .widgetKey(src.getWidgetKey())
                .widgetType(src.getWidgetType())
                .title(src.getTitle())
                .subtitle(src.getSubtitle())
                .dataEndpoint(src.getDataEndpoint())
                .dataPath(src.getDataPath())
                .filtersJson(src.getFiltersJson())
                .valueFormat(src.getValueFormat())
                .thresholdsJson(src.getThresholdsJson())
                .refreshIntervalSeconds(src.getRefreshIntervalSeconds())
                .configJson(src.getConfigJson())
                .requiredPermission(src.getRequiredPermission())
                .allowedSidesJson(src.getAllowedSidesJson())
                .sortOrder(src.getSortOrder())
                .gridCols(src.getGridCols())
                .isActive(true)
                .clickThroughRoute(src.getClickThroughRoute())
                .drillThroughJson(src.getDrillThroughJson())
                .emptyMessage(src.getEmptyMessage())
                .tenantId(tenantId)
                .build();
    }

    /** Next slot, keeping cards ahead of charts as the layout convention expects. */
    private int nextSortOrder(Long dashboardId, DashboardWidget.WidgetType type) {
        int base = type == DashboardWidget.WidgetType.KPI_CARD ? 0 : 1000;
        int max = base;
        for (DashboardWidget w : widgetRepository
                .findByDashboardIdAndIsActiveTrueOrderBySortOrderAsc(dashboardId)) {
            boolean sameBand = (w.getWidgetType() == DashboardWidget.WidgetType.KPI_CARD)
                    == (type == DashboardWidget.WidgetType.KPI_CARD);
            if (sameBand && w.getSortOrder() != null) max = Math.max(max, w.getSortOrder());
        }
        return max + 10;
    }

    private String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }
}