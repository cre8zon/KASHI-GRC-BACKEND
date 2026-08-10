package com.kashi.grc.uiconfig.service;

import com.kashi.grc.common.cache.CacheNames;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.usermanagement.domain.User;
import com.kashi.grc.uiconfig.domain.*;
import com.kashi.grc.uiconfig.dto.response.*;
import com.kashi.grc.uiconfig.repository.*;
import com.kashi.grc.usermanagement.repository.PermissionGrantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class UiConfigServiceImpl implements UiConfigService {

    private final UiNavigationRepository    navigationRepository;
    private final UiComponentRepository     componentRepository;
    private final UiOptionRepository        optionRepository;
    private final UiLayoutRepository        layoutRepository;
    private final UiFormRepository          formRepository;
    private final UiFormFieldRepository     formFieldRepository;
    private final UiActionRepository        actionRepository;
    private final DashboardWidgetRepository widgetRepository;
    private final FeatureFlagRepository     featureFlagRepository;
    private final com.kashi.grc.tenant.repository.TenantRepository tenantRepository;
    private final com.kashi.grc.vendor.repository.VendorRepository vendorRepository;
    private final TenantBrandingRepository  brandingRepository;
    private final UtilityService            utilityService;
    private final PermissionGrantRepository permissionGrantRepository;
    private final com.kashi.grc.usermanagement.service.user.UserService userService;

    // ── Bootstrap (single call after login) ───────────────────────

    @Override
    @Transactional(readOnly = true)
    public AppBootstrapResponse bootstrap() {
        // getLoggedInDataContext() returns the User entity directly.
        // Called once here — all sub-methods (getNavigation, getDashboardWidgets, etc.)
        // reuse the same cached result via UtilityService.REQUEST_USER_CACHE.
        com.kashi.grc.usermanagement.domain.User currentUser =
                utilityService.getLoggedInDataContext();
        Long tenantId = currentUser.getTenantId();

        String tenantName = tenantRepository.findById(tenantId)
                .map(t -> t.getName()).orElse("");

        // For vendor users — look up vendor name from vendorId on the user entity
        String vendorName = null;
        if (currentUser.getVendorId() != null) {
            vendorName = vendorRepository.findById(currentUser.getVendorId())
                    .map(v -> v.getName()).orElse(null);
        }

        return AppBootstrapResponse.builder()
                .tenantName(tenantName)
                .vendorName(vendorName)
                .userPreferences(userService.getPreferences())
                .branding(getBranding())
                .navigation(getNavigation())
                .dashboardWidgets(getDashboardWidgets())
                .featureFlags(getEnabledFeatureFlags())
                .build();
    }

    // ── Screen Config ─────────────────────────────────────────────

    // ── Per-request caches — cleared by UtilityService.clearRequestCache() ──
    // Feature flags and layout are identical across all screen config calls
    // within the same request — no need to re-query for _header, _tab_overview etc.
    private static final ThreadLocal<Map<String, Boolean>>        FEATURE_FLAG_CACHE = new ThreadLocal<>();
    private static final ThreadLocal<Map<String, LayoutResponse>> LAYOUT_CACHE       = new ThreadLocal<>();

    public static void clearScreenConfigCache() {
        FEATURE_FLAG_CACHE.remove();
        LAYOUT_CACHE.remove();
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheNames.UI_SCREEN)
    public ScreenConfigResponse getScreenConfig(String screenKey) {
        User currentUser = utilityService.getLoggedInDataContext();
        Long tenantId    = currentUser.getTenantId();

        // Components — global first, then tenant overrides
        List<UiComponent> components = componentRepository
                .findByScreenForTenant(screenKey, tenantId);

        // Batch-fetch ALL options for this screen's components in ONE query
        // instead of one query per component (N+1 fix)
        Map<String, List<UiOption>> optionsByKey = Collections.emptyMap();
        if (!components.isEmpty()) {
            List<String> componentKeys = components.stream()
                    .filter(UiComponent::isVisible)
                    .map(UiComponent::getComponentKey)
                    .toList();
            if (!componentKeys.isEmpty()) {
                optionsByKey = optionRepository
                        .findByComponentKeysAndTenant(componentKeys, tenantId)
                        .stream()
                        .collect(Collectors.groupingBy(
                                o -> o.getComponent().getComponentKey()));
            }
        }

        Map<String, UiComponentResponse> componentMap = new LinkedHashMap<>();
        for (UiComponent c : components) {
            if (!c.isVisible()) continue;
            List<UiOption> options = optionsByKey.getOrDefault(c.getComponentKey(), List.of());
            componentMap.put(c.getComponentKey(), toComponentResponse(c, options));
        }

        // Layout — cache per request (same tenant layout repeated across _header, _tab_overview etc.)
        Map<String, LayoutResponse> layoutCache = LAYOUT_CACHE.get();
        if (layoutCache == null) { layoutCache = new HashMap<>(); LAYOUT_CACHE.set(layoutCache); }
        LayoutResponse layout = layoutCache.computeIfAbsent(screenKey, key ->
                layoutRepository.findByLayoutKeyAndTenantId(key, tenantId)
                        .or(() -> layoutRepository.findByLayoutKeyAndTenantIdIsNull(key))
                        .map(this::toLayoutResponse)
                        .orElse(null));

        // Actions visible to this user
        List<UiActionResponse> actions = getActions(screenKey, null);

        // Feature flags — cache per request (identical for all screen keys in same request)
        Map<String, Boolean> flags = FEATURE_FLAG_CACHE.get();
        if (flags == null) {
            Set<String> enabledKeys = featureFlagRepository.resolveEnabledFeaturesForTenant(tenantId);
            flags = enabledKeys.stream().collect(Collectors.toMap(k -> k, k -> Boolean.TRUE));
            FEATURE_FLAG_CACHE.set(flags);
        }

        return ScreenConfigResponse.builder()
                .components(componentMap)
                .layout(layout)
                .actions(actions)
                .featureFlags(flags)
                .build();
    }

    // ── Navigation ────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<UiNavigationItemResponse> getNavigation() {
        // Single call — result cached in ThreadLocal for this request.
        // Previously this called getLoggedInDataContext() twice (once for tenantId,
        // once for user), causing two full criteria JOIN FETCH queries per nav load.
        User user     = utilityService.getLoggedInDataContext();
        Long tenantId = user.getTenantId();
        Set<String> userSides       = extractSides(user);
        Set<String> userPermissions = extractPermissions(user);

        log.info("[NAV-DEBUG] userId={} sides={} perms={}", user.getId(), userSides, userPermissions);

        List<UiNavigation> all = navigationRepository.findAllForTenant(tenantId);
        log.info("[NAV] userId={} tenantId={} total={}", user.getId(), tenantId, all.size());

        // Enabled feature keys for this tenant. A nav row whose required_feature
        // is not enabled for the tenant is hidden (and not route-resolved).
        Set<String> tenantFeatures = featureFlagRepository.resolveEnabledFeaturesForTenant(tenantId);

        // Filter by side/permission only — NOT by isActive.
        // isActive is returned in the response so the frontend can decide
        // what to show in the sidebar vs what to use only for route resolution.
        // Filtering isActive here would hide task-specific nav entries (is_active=0)
        // that the TaskInbox needs to resolve routes for "Open Task" buttons.
        List<UiNavigation> visible = all.stream()
                .filter(item -> {
                    boolean v = isNavVisible(item, userSides, userPermissions, tenantFeatures);
                    if ("module_issue".equals(item.getNavKey())) {
                        log.info("[NAV-DEBUG] module_issue: active={} sides='{}' requiredPerm='{}' userSides={} userPerms={} → visible={}",
                                item.isActive(), item.getAllowedSides(), item.getRequiredPermission(),
                                userSides, userPermissions, v);
                    }
                    return v;
                })
                .toList();

        log.debug("Visible items: {}", visible.stream().map(UiNavigation::getNavKey).toList());
        log.debug("=== NAV DEBUG END ===");

        return buildNavTree(visible, null);
    }

    // ── Form ──────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheNames.UI_FORM)
    public UiFormResponse getForm(String formKey) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();

        UiForm form = formRepository.findByFormKeyAndTenantId(formKey, tenantId)
                .or(() -> formRepository.findByFormKey(formKey))
                .orElseThrow(() -> new com.kashi.grc.common.exception
                        .ResourceNotFoundException("UiForm", "formKey", formKey));

        List<UiFormField> fields = formFieldRepository
                .findByFormIdAndIsVisibleTrueOrderBySortOrder(form.getId());

        // Include global components (screen IS NULL) so SELECT/MULTI_SELECT fields
        // can resolve their options without needing a separate screen config call.
        // Uses the same findByScreenForTenant query — passing a non-existent key
        // returns only global components (WHERE screen IS NULL OR screen = '').
        List<UiComponent> components = componentRepository
                .findByScreenForTenant("__form_globals__", tenantId);

        // Batch-fetch ALL options for these components in ONE query instead of
        // one query per component (same N+1 fix already applied in
        // getScreenConfig — this method just never got it).
        Map<String, List<UiOption>> optionsByKey = Collections.emptyMap();
        if (!components.isEmpty()) {
            List<String> componentKeys = components.stream()
                    .filter(UiComponent::isVisible)
                    .map(UiComponent::getComponentKey)
                    .toList();
            if (!componentKeys.isEmpty()) {
                optionsByKey = optionRepository
                        .findByComponentKeysAndTenant(componentKeys, tenantId)
                        .stream()
                        .collect(Collectors.groupingBy(
                                o -> o.getComponent().getComponentKey()));
            }
        }

        Map<String, UiComponentResponse> componentMap = new java.util.LinkedHashMap<>();
        for (UiComponent c : components) {
            if (!c.isVisible()) continue;
            List<UiOption> options = optionsByKey.getOrDefault(c.getComponentKey(), List.of());
            componentMap.put(c.getComponentKey(), toComponentResponse(c, options));
        }

        return UiFormResponse.builder()
                .formKey(form.getFormKey()).title(form.getTitle())
                .description(form.getDescription()).submitUrl(form.getSubmitUrl())
                .httpMethod(form.getHttpMethod()).stepsJson(form.getStepsJson())
                .fields(fields.stream().map(this::toFormFieldResponse).toList())
                .components(componentMap)
                .build();
    }

    // ── Actions ───────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    // NOTE: only applies to external calls through the Spring proxy — the
    // internal self-invocation from getScreenConfig() bypasses this cache,
    // which is fine since getScreenConfig()'s own result is cached as a whole.
    @Cacheable(cacheNames = CacheNames.UI_ACTIONS)
    public List<UiActionResponse> getActions(String screenKey, String entityStatus) {
        // Single call — result cached in ThreadLocal for this request.
        // Previously called getLoggedInDataContext() twice (once for tenantId, once for user).
        User user         = utilityService.getLoggedInDataContext();
        Long tenantId     = user.getTenantId();
        Set<String> sides = extractSides(user);
        Set<String> perms = extractPermissions(user);

        return actionRepository.findByScreenAndTenant(screenKey, tenantId).stream()
                .filter(a -> isActionVisible(a, sides, perms, entityStatus))
                .map(this::toActionResponse)
                .toList();
    }

    // ── Dashboard Widgets ─────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheNames.UI_DASHBOARD)
    public List<DashboardWidgetResponse> getDashboardWidgets() {
        // Single call — result cached in ThreadLocal for this request.
        // Previously called getLoggedInDataContext() twice (once for tenantId, once for user).
        User user         = utilityService.getLoggedInDataContext();
        Long tenantId     = user.getTenantId();
        Set<String> sides = extractSides(user);
        Set<String> perms = extractPermissions(user);

        return widgetRepository.findActiveByTenant(tenantId).stream()
                .filter(w -> isWidgetVisible(w, sides, perms))
                .map(this::toWidgetResponse)
                .toList();
    }

    // ── Branding ──────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public TenantBrandingResponse getBranding() {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return brandingRepository.findByTenantId(tenantId)
                .map(this::toBrandingResponse)
                .orElse(TenantBrandingResponse.builder()
                        .primaryColor("#1e40af").accentColor("#7c3aed")
                        .sidebarTheme("dark").build());
    }

    // ── Role/Permission helpers ───────────────────────────────────

    /** Extract all RoleSide names the user holds. */
    private Set<String> extractSides(User user) {
        if (user.getRoles() == null) return Set.of();
        return user.getRoles().stream()
                .filter(r -> r.getSide() != null)
                .map(r -> r.getSide().name())
                .collect(Collectors.toSet());
    }

    /** Derive permission codes by walking User -> Roles -> Permissions. */
    private Set<String> extractPermissions(User user) {
        if (user.getRoles() == null) return Set.of();

        // Base permissions from role_permissions (static ManyToMany)
        Set<String> perms = user.getRoles().stream()
                .filter(r -> r.getPermissions() != null)
                .flatMap(r -> r.getPermissions().stream())
                .map(p -> p.getCode())
                .collect(Collectors.toCollection(java.util.HashSet::new));

        // Dynamic grants from permission_grants table (UI-managed, supports revoke)
        List<Long> roleIds = user.getRoles().stream()
                .map(r -> r.getId())
                .collect(Collectors.toList());
        if (!roleIds.isEmpty()) {
            permissionGrantRepository.findGrantsForUserRoles(roleIds)
                    .forEach(row -> {
                        String code    = (String)  row[0];
                        Boolean granted = (Boolean) row[1];
                        if (code != null) {
                            if (Boolean.TRUE.equals(granted)) perms.add(code);
                            else perms.remove(code);  // explicit revoke overrides static grant
                        }
                    });
        }
        return perms;
    }

    private Map<String, Boolean> getEnabledFeatureFlags() {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return featureFlagRepository.resolveEnabledFeaturesForTenant(tenantId).stream()
                .collect(Collectors.toMap(
                        k -> k,
                        k -> Boolean.TRUE,
                        (global, tenant) -> tenant)); // tenant row wins
    }

    // ── Visibility checks ─────────────────────────────────────────

    private boolean isNavVisible(UiNavigation item,
                                 Set<String> sides, Set<String> perms, Set<String> features) {
        // Feature entitlement first — cheapest, tenant-wide exclusion. A row with
        // a required_feature the tenant lacks is neither shown nor route-resolved.
        if (item.getRequiredFeature() != null && !item.getRequiredFeature().isBlank()
                && !features.contains(item.getRequiredFeature())) {
            return false;
        }

        // required_permission supports comma-separated OR logic (same pattern as allowed_sides).
        // e.g. "audit:engagement:read,audit:engagement:read-limited" → visible if user has either.
        if (item.getRequiredPermission() != null && !item.getRequiredPermission().isBlank()) {
            boolean hasAny = Arrays.stream(item.getRequiredPermission().split(","))
                    .map(String::trim)
                    .anyMatch(perms::contains);
            if (!hasAny) return false;
        }

        if (item.getAllowedSides() != null && !item.getAllowedSides().isBlank()) {
            Set<String> allowed = Arrays.stream(item.getAllowedSides().split(","))
                    .map(String::trim).collect(Collectors.toSet());
            return sides.stream().anyMatch(allowed::contains);
        }
        return true;
    }

    private boolean isActionVisible(UiAction a, Set<String> sides,
                                    Set<String> perms, String entityStatus) {
        if (a.getRequiredPermission() != null && !a.getRequiredPermission().isBlank()) {
            boolean hasAny = Arrays.stream(a.getRequiredPermission().split(","))
                    .map(String::trim)
                    .anyMatch(perms::contains);
            if (!hasAny) return false;
        }
        if (a.getAllowedSides() != null && !a.getAllowedSides().isBlank()) {
            Set<String> allowed = Arrays.stream(a.getAllowedSides().split(","))
                    .map(String::trim).collect(Collectors.toSet());
            if (sides.stream().noneMatch(allowed::contains)) return false;
        }
        if (a.getAllowedStatusesJson() != null && entityStatus != null
                && !a.getAllowedStatusesJson().contains("\"" + entityStatus + "\"")) return false;
        return true;
    }

    private boolean isWidgetVisible(DashboardWidget w,
                                    Set<String> sides, Set<String> perms) {
        if (w.getRequiredPermission() != null && !w.getRequiredPermission().isBlank()) {
            boolean hasAny = Arrays.stream(w.getRequiredPermission().split(","))
                    .map(String::trim)
                    .anyMatch(perms::contains);
            if (!hasAny) return false;
        }
        if (w.getAllowedSidesJson() != null && !w.getAllowedSidesJson().isBlank()) {
            return sides.stream()
                    .anyMatch(s -> w.getAllowedSidesJson().contains("\"" + s + "\""));
        }
        return true;
    }

    // ── Nav tree builder ──────────────────────────────────────────

    private List<UiNavigationItemResponse> buildNavTree(
            List<UiNavigation> items, String parentKey) {
        return items.stream()
                .filter(i -> Objects.equals(i.getParentKey(), parentKey))
                .sorted(Comparator.comparingInt(i -> (i.getSortOrder() != null ? i.getSortOrder() : 0)))
                .map(i -> UiNavigationItemResponse.builder()
                        .id(i.getId()).navKey(i.getNavKey()).label(i.getLabel())
                        .icon(i.getIcon()).route(i.getRoute()).parentKey(i.getParentKey())
                        .sortOrder(i.getSortOrder()).module(i.getModule())
                        .badgeCountEndpoint(i.getBadgeCountEndpoint())
                        .requiredFeature(i.getRequiredFeature())
                        .isActive(i.isActive())
                        .children(buildNavTree(items, i.getNavKey()))
                        .build())
                .toList();
    }

    // ── Mappers ───────────────────────────────────────────────────

    private UiComponentResponse toComponentResponse(UiComponent c, List<UiOption> options) {
        return UiComponentResponse.builder()
                .componentKey(c.getComponentKey())
                .componentType(c.getComponentType().name())
                .label(c.getLabel()).configJson(c.getConfigJson())
                .options(options.stream().map(o -> UiOptionResponse.builder()
                        .id(o.getId()).value(o.getOptionValue()).label(o.getOptionLabel())
                        .colorTag(o.getColorTag()).icon(o.getIcon())
                        .sortOrder(o.getSortOrder()).transitionsJson(o.getTransitionsJson())
                        .build()).toList())
                .build();
    }

    private LayoutResponse toLayoutResponse(UiLayout l) {
        return LayoutResponse.builder()
                .layoutKey(l.getLayoutKey()).title(l.getTitle())
                .columnsJson(l.getColumnsJson()).filtersJson(l.getFiltersJson())
                .selectable(l.isSelectable()).reorderable(l.isReorderable())
                .layoutMode(l.getLayoutMode() != null ? l.getLayoutMode() : "FULL_PAGE")
                .tabsJson(l.getTabsJson())
                // Was missing: without it the client's parseRoleAccessJson() always got
                // undefined -> {} -> isItemAllowed() default-allow, so per-role tab and
                // action visibility never applied at runtime on any screen.
                .roleAccessJson(l.getRoleAccessJson() != null ? l.getRoleAccessJson() : "{}")
                .build();
    }

    private UiFormFieldResponse toFormFieldResponse(UiFormField f) {
        return UiFormFieldResponse.builder()
                .id(f.getId()).fieldKey(f.getFieldKey())
                .fieldType(f.getFieldType().name()).label(f.getLabel())
                .placeholder(f.getPlaceholder()).helperText(f.getHelperText())
                .isRequired(f.isRequired()).sortOrder(f.getSortOrder())
                .optionsComponentKey(f.getOptionsComponentKey())
                .validationRulesJson(f.getValidationRulesJson())
                .dependsOnJson(f.getDependsOnJson())
                .gridCols(f.getGridCols()).stepNumber(f.getStepNumber())
                // Extended field-type metadata — all nullable, ignored when null by @JsonInclude
                .lookupEntityType(f.getLookupEntityType())
                .lookupApiPath(f.getLookupApiPath())
                .rowsCount(f.getRowsCount())
                .minValue(f.getMinValue())
                .maxValue(f.getMaxValue())
                .stepValue(f.getStepValue())
                .currencyCode(f.getCurrencyCode())
                .tagSuggestions(f.getTagSuggestions())
                .defaultValue(f.getDefaultValue())
                .build();
    }

    private UiActionResponse toActionResponse(UiAction a) {
        return UiActionResponse.builder()
                .id(a.getId())
                .actionKey(a.getActionKey()).label(a.getLabel())
                .icon(a.getIcon()).variant(a.getVariant())
                .apiEndpoint(a.getApiEndpoint()).httpMethod(a.getHttpMethod())
                .payloadTemplateJson(a.getPayloadTemplateJson())
                .allowedStatusesJson(a.getAllowedStatusesJson())
                .requiresConfirmation(a.getRequiresConfirmation())
                .confirmationMessage(a.getConfirmationMessage())
                .requiresRemarks(a.getRequiresRemarks())
                .requiresAssignment(Boolean.TRUE.equals(a.getRequiresAssignment()))
                .sortOrder(a.getSortOrder())
                .build();
    }

    private DashboardWidgetResponse toWidgetResponse(DashboardWidget w) {
        return DashboardWidgetResponse.builder()
                .widgetKey(w.getWidgetKey()).widgetType(w.getWidgetType().name())
                .title(w.getTitle()).subtitle(w.getSubtitle())
                .dataEndpoint(w.getDataEndpoint()).dataPath(w.getDataPath())
                .refreshIntervalSeconds(w.getRefreshIntervalSeconds())
                .configJson(w.getConfigJson()).gridCols(w.getGridCols())
                .sortOrder(w.getSortOrder()).clickThroughRoute(w.getClickThroughRoute())
                .build();
    }

    private TenantBrandingResponse toBrandingResponse(TenantBranding b) {
        return TenantBrandingResponse.builder()
                .companyName(b.getCompanyName()).logoUrl(b.getLogoUrl())
                .faviconUrl(b.getFaviconUrl()).primaryColor(b.getPrimaryColor())
                .accentColor(b.getAccentColor()).sidebarTheme(b.getSidebarTheme())
                .supportEmail(b.getSupportEmail()).supportUrl(b.getSupportUrl())
                .footerText(b.getFooterText()).build();
    }
}