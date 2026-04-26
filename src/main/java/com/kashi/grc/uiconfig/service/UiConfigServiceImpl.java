package com.kashi.grc.uiconfig.service;

import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.usermanagement.domain.User;
import com.kashi.grc.uiconfig.domain.*;
import com.kashi.grc.uiconfig.dto.response.*;
import com.kashi.grc.uiconfig.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final com.kashi.grc.usermanagement.service.user.UserService userService;

    // ── Bootstrap (single call after login) ───────────────────────

    @Override
    @Transactional(readOnly = true)
    public AppBootstrapResponse bootstrap() {
        // getLoggedInDataContext() returns the User entity directly
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

    @Override
    @Transactional(readOnly = true)
    public ScreenConfigResponse getScreenConfig(String screenKey) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();

        // Components — global first, then tenant overrides
        List<UiComponent> components = componentRepository
                .findByScreenForTenant(screenKey, tenantId);

        Map<String, UiComponentResponse> componentMap = new LinkedHashMap<>();
        for (UiComponent c : components) {
            if (!c.isVisible()) continue;
            List<UiOption> options = optionRepository
                    .findByComponentKeyAndTenant(c.getComponentKey(), tenantId);
            componentMap.put(c.getComponentKey(), toComponentResponse(c, options));
        }

        // Layout — tenant override takes priority over global
        LayoutResponse layout = layoutRepository
                .findByLayoutKeyAndTenantId(screenKey, tenantId)
                .or(() -> layoutRepository.findByLayoutKeyAndTenantIdIsNull(screenKey))
                .map(this::toLayoutResponse)
                .orElse(null);

        // Actions visible to this user
        List<UiActionResponse> actions = getActions(screenKey, null);

        return ScreenConfigResponse.builder()
                .components(componentMap)
                .layout(layout)
                .actions(actions)
                .featureFlags(getEnabledFeatureFlags())
                .build();
    }

    // ── Navigation ────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<UiNavigationItemResponse> getNavigation() {
        Long tenantId  = utilityService.getLoggedInDataContext().getTenantId();
        User user      = utilityService.getLoggedInDataContext();
        Set<String> userSides       = extractSides(user);
        Set<String> userPermissions = extractPermissions(user);

        log.info("=== NAV DEBUG ===");
        log.info("UserId: {}, TenantId: {}", user.getId(), tenantId);
        log.info("User sides: {}", userSides);
        log.info("User permissions: {}", userPermissions);

        List<UiNavigation> all = navigationRepository.findAllForTenant(tenantId);
        log.info("Total nav items from DB: {}", all.size());

        // Filter by side/permission only — NOT by isActive.
        // isActive is returned in the response so the frontend can decide
        // what to show in the sidebar vs what to use only for route resolution.
        // Filtering isActive here would hide task-specific nav entries (is_active=0)
        // that the TaskInbox needs to resolve routes for "Open Task" buttons.
        List<UiNavigation> visible = all.stream()
                .filter(item -> {
                    boolean v = isNavVisible(item, userSides, userPermissions);
                    log.debug("  item={} active={} allowedSides='{}' → visible={}",
                            item.getNavKey(), item.isActive(),
                            item.getAllowedSides(), v);
                    return v;
                })
                .toList();

        log.info("Visible items: {}", visible.stream().map(UiNavigation::getNavKey).toList());
        log.info("=== NAV DEBUG END ===");

        return buildNavTree(visible, null);
    }

    // ── Form ──────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public UiFormResponse getForm(String formKey) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();

        UiForm form = formRepository.findByFormKeyAndTenantId(formKey, tenantId)
                .or(() -> formRepository.findByFormKey(formKey))
                .orElseThrow(() -> new com.kashi.grc.common.exception
                        .ResourceNotFoundException("UiForm", "formKey", formKey));

        List<UiFormField> fields = formFieldRepository
                .findByFormIdAndIsVisibleTrueOrderBySortOrder(form.getId());

        return UiFormResponse.builder()
                .formKey(form.getFormKey()).title(form.getTitle())
                .description(form.getDescription()).submitUrl(form.getSubmitUrl())
                .httpMethod(form.getHttpMethod()).stepsJson(form.getStepsJson())
                .fields(fields.stream().map(this::toFormFieldResponse).toList())
                .build();
    }

    // ── Actions ───────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<UiActionResponse> getActions(String screenKey, String entityStatus) {
        Long tenantId       = utilityService.getLoggedInDataContext().getTenantId();
        User user           = utilityService.getLoggedInDataContext();
        Set<String> sides   = extractSides(user);
        Set<String> perms   = extractPermissions(user);

        return actionRepository.findByScreenAndTenant(screenKey, tenantId).stream()
                .filter(a -> isActionVisible(a, sides, perms, entityStatus))
                .map(this::toActionResponse)
                .toList();
    }

    // ── Dashboard Widgets ─────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<DashboardWidgetResponse> getDashboardWidgets() {
        Long tenantId     = utilityService.getLoggedInDataContext().getTenantId();
        User user         = utilityService.getLoggedInDataContext();
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
        return user.getRoles().stream()
                .filter(r -> r.getPermissions() != null)
                .flatMap(r -> r.getPermissions().stream())
                .map(p -> p.getCode())
                .collect(Collectors.toSet());
    }

    private Map<String, Boolean> getEnabledFeatureFlags() {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return featureFlagRepository.findEnabledForTenant(tenantId).stream()
                .collect(Collectors.toMap(
                        FeatureFlag::getFlagKey,
                        FeatureFlag::isEnabled,
                        (global, tenant) -> tenant)); // tenant row wins
    }

    // ── Visibility checks ─────────────────────────────────────────

    private boolean isNavVisible(UiNavigation item,
                                 Set<String> sides, Set<String> perms) {
        if (item.getRequiredPermission() != null
                && !item.getRequiredPermission().isBlank()
                && !perms.contains(item.getRequiredPermission())) return false;

        if (item.getAllowedSides() != null && !item.getAllowedSides().isBlank()) {
            Set<String> allowed = Arrays.stream(item.getAllowedSides().split(","))
                    .map(String::trim).collect(Collectors.toSet());
            return sides.stream().anyMatch(allowed::contains);
        }
        return true;
    }

    private boolean isActionVisible(UiAction a, Set<String> sides,
                                    Set<String> perms, String entityStatus) {
        if (a.getRequiredPermission() != null
                && !a.getRequiredPermission().isBlank()
                && !perms.contains(a.getRequiredPermission())) return false;
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
        if (w.getRequiredPermission() != null
                && !perms.contains(w.getRequiredPermission())) return false;
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
                .build();
    }

    private UiActionResponse toActionResponse(UiAction a) {
        return UiActionResponse.builder()
                .actionKey(a.getActionKey()).label(a.getLabel())
                .icon(a.getIcon()).variant(a.getVariant())
                .apiEndpoint(a.getApiEndpoint()).httpMethod(a.getHttpMethod())
                .payloadTemplateJson(a.getPayloadTemplateJson())
                .requiresConfirmation(a.isRequiresConfirmation())
                .confirmationMessage(a.getConfirmationMessage())
                .requiresRemarks(a.isRequiresRemarks()).sortOrder(a.getSortOrder())
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