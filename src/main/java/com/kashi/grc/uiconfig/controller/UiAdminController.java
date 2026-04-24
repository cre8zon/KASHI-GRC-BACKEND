package com.kashi.grc.uiconfig.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.dto.PaginatedResponse;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.repository.DbRepository;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.uiconfig.domain.*;
import com.kashi.grc.uiconfig.dto.request.*;
import com.kashi.grc.uiconfig.dto.response.*;
import com.kashi.grc.uiconfig.repository.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Admin CRUD endpoints for managing all ui_* tables.
 * Used by the platform admin to configure the frontend without code deploys.
 *
 * Navigation:    POST/PUT/DELETE /v1/admin/ui/navigation
 * Components:    POST/PUT/DELETE /v1/admin/ui/components
 * Options:       POST/PUT/DELETE /v1/admin/ui/options
 * Layouts:       POST/PUT/DELETE /v1/admin/ui/layouts
 * Forms:         POST/PUT/DELETE /v1/admin/ui/forms
 * Form Fields:   POST/PUT/DELETE /v1/admin/ui/form-fields
 * Actions:       POST/PUT/DELETE /v1/admin/ui/actions
 * Widgets:       POST/PUT/DELETE /v1/admin/ui/widgets
 * Feature Flags: POST/PUT/DELETE /v1/admin/ui/flags
 * Branding:      POST/PUT         /v1/admin/ui/branding
 */
@RestController
@RequestMapping("/v1/admin/ui")
@Tag(name = "UI Admin (Platform Config)", description = "Admin CRUD for all DB-driven UI config tables")
@RequiredArgsConstructor
public class UiAdminController {

    private final UiNavigationRepository    navigationRepository;
    private final UiComponentRepository     componentRepository;
    private final UiOptionRepository        optionRepository;
    private final UiLayoutRepository        layoutRepository;
    private final UiFormRepository          formRepository;
    private final UiFormFieldRepository     formFieldRepository;
    private final UiActionRepository        actionRepository;
    private final DashboardWidgetRepository widgetRepository;
    private final FeatureFlagRepository     featureFlagRepository;
    private final TenantBrandingRepository  brandingRepository;
    private final DbRepository              dbRepository;
    private final UtilityService            utilityService;

    // ══════════════════════════════════════════════════════════════
    // NAVIGATION
    // ══════════════════════════════════════════════════════════════

    @PostMapping("/navigation")
    @Operation(summary = "Create a navigation item")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createNav(
            @Valid @RequestBody UiNavigationRequest req) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        UiNavigation nav = UiNavigation.builder()
                .navKey(req.getNavKey()).label(req.getLabel()).icon(req.getIcon())
                .route(req.getRoute()).parentKey(req.getParentKey())
                .sortOrder(req.getSortOrder()).module(req.getModule())
                .allowedSides(req.getAllowedSides()).minLevel(req.getMinLevel())
                .requiredPermission(req.getRequiredPermission())
                .isActive(req.isActive()).badgeCountEndpoint(req.getBadgeCountEndpoint())
                .tenantId(tenantId)
                .build();
        navigationRepository.save(nav);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(toMap("id", nav.getId(), "navKey", nav.getNavKey())));
    }

    @GetMapping("/navigation")
    @Operation(summary = "List all navigation items — paginated")
    public ResponseEntity<ApiResponse<PaginatedResponse<Map<String, Object>>>> listNav(
            @RequestParam Map<String, String> allParams) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(dbRepository.findAll(
                UiNavigation.class,
                utilityService.getpageDetails(allParams),
                (cb, root) -> List.of(cb.or(
                        cb.isNull(root.get("tenantId")),
                        cb.equal(root.get("tenantId"), tenantId))),
                (cb, root) -> Map.of("navkey", root.get("navKey"),
                        "label", root.get("label"), "module", root.get("module")),
                n -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id",                   n.getId());
                    m.put("navKey",               n.getNavKey());
                    m.put("label",                n.getLabel());
                    m.put("route",                n.getRoute());
                    m.put("icon",                 n.getIcon()                != null ? n.getIcon()                : "");
                    m.put("parentKey",            n.getParentKey()           != null ? n.getParentKey()           : "");
                    m.put("module",               n.getModule()              != null ? n.getModule()              : "");
                    m.put("sortOrder",            n.getSortOrder()           != null ? n.getSortOrder()           : 0);
                    m.put("isActive",             n.isActive());
                    m.put("allowedSides",         n.getAllowedSides()        != null ? n.getAllowedSides()        : "");
                    m.put("badgeCountEndpoint",   n.getBadgeCountEndpoint()  != null ? n.getBadgeCountEndpoint()  : "");
                    m.put("requiredPermission",   n.getRequiredPermission()  != null ? n.getRequiredPermission()  : "");
                    return m;
                })));
    }

    @PutMapping("/navigation/{id}")
    @Operation(summary = "Update a navigation item")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateNav(
            @PathVariable Long id, @RequestBody UiNavigationRequest req) {
        UiNavigation nav = navigationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("UiNavigation", id));
        if (req.getLabel()               != null) nav.setLabel(req.getLabel());
        if (req.getIcon()                != null) nav.setIcon(req.getIcon());
        if (req.getRoute()               != null) nav.setRoute(req.getRoute());
        if (req.getParentKey()           != null) nav.setParentKey(req.getParentKey());
        if (req.getSortOrder()           != null) nav.setSortOrder(req.getSortOrder());
        if (req.getModule()              != null) nav.setModule(req.getModule());
        if (req.getAllowedSides()        != null) nav.setAllowedSides(req.getAllowedSides());
        if (req.getMinLevel()            != null) nav.setMinLevel(req.getMinLevel());
        if (req.getRequiredPermission()  != null) nav.setRequiredPermission(req.getRequiredPermission());
        if (req.getBadgeCountEndpoint()  != null) nav.setBadgeCountEndpoint(req.getBadgeCountEndpoint());
        nav.setActive(req.isActive());
        navigationRepository.save(nav);
        return ResponseEntity.ok(ApiResponse.success(toMap("id", nav.getId(), "navKey", nav.getNavKey())));
    }

    @DeleteMapping("/navigation/{id}")
    @Operation(summary = "Delete a navigation item")
    public ResponseEntity<ApiResponse<Void>> deleteNav(@PathVariable Long id) {
        navigationRepository.deleteById(id);
        return ResponseEntity.ok(ApiResponse.success());
    }

    // ══════════════════════════════════════════════════════════════
    // COMPONENTS
    // ══════════════════════════════════════════════════════════════

    @PostMapping("/components")
    @Operation(summary = "Register a new UI component (dropdown, badge set, etc.)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createComponent(
            @Valid @RequestBody UiComponentRequest req) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        UiComponent c = UiComponent.builder()
                .componentKey(req.getComponentKey())
                .componentType(UiComponent.ComponentType.valueOf(req.getComponentType()))
                .module(req.getModule()).screen(req.getScreen()).label(req.getLabel())
                .isVisible(req.isVisible()).configJson(req.getConfigJson())
                .tenantId(tenantId)
                .build();
        componentRepository.save(c);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(toMap("id", c.getId(), "componentKey", c.getComponentKey())));
    }

    @GetMapping("/components")
    @Operation(summary = "List all UI components — paginated")
    public ResponseEntity<ApiResponse<PaginatedResponse<Map<String, Object>>>> listComponents(
            @RequestParam Map<String, String> allParams) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(dbRepository.findAll(
                UiComponent.class,
                utilityService.getpageDetails(allParams),
                (cb, root) -> List.of(cb.or(
                        cb.isNull(root.get("tenantId")),
                        cb.equal(root.get("tenantId"), tenantId))),
                (cb, root) -> Map.of("componentkey", root.get("componentKey"),
                        "module", root.get("module"), "screen", root.get("screen")),
                c -> Map.of("id", c.getId(), "componentKey", c.getComponentKey(),
                        "componentType", c.getComponentType().name(),
                        "module", c.getModule() != null ? c.getModule() : "",
                        "screen", c.getScreen() != null ? c.getScreen() : "",
                        "label", c.getLabel() != null ? c.getLabel() : "",
                        "isVisible", c.isVisible()))));
    }

    @PutMapping("/components/{id}")
    @Operation(summary = "Update a UI component")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateComponent(
            @PathVariable Long id, @RequestBody UiComponentRequest req) {
        UiComponent c = componentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("UiComponent", id));
        if (req.getLabel()      != null) c.setLabel(req.getLabel());
        if (req.getModule()     != null) c.setModule(req.getModule());
        if (req.getScreen()     != null) c.setScreen(req.getScreen());
        if (req.getConfigJson() != null) c.setConfigJson(req.getConfigJson());
        c.setVisible(req.isVisible());
        componentRepository.save(c);
        return ResponseEntity.ok(ApiResponse.success(toMap("id", c.getId(), "componentKey", c.getComponentKey())));
    }

    @DeleteMapping("/components/{id}")
    @Operation(summary = "Delete a UI component (also deletes its options)")
    public ResponseEntity<ApiResponse<Void>> deleteComponent(@PathVariable Long id) {
        componentRepository.deleteById(id);
        return ResponseEntity.ok(ApiResponse.success());
    }

    // ══════════════════════════════════════════════════════════════
    // OPTIONS
    // ══════════════════════════════════════════════════════════════

    @PostMapping("/options")
    @Operation(summary = "Add an option to a component (e.g. add 'CRITICAL' to risk_classification)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createOption(
            @Valid @RequestBody UiOptionRequest req) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        UiComponent component = componentRepository.findById(req.getComponentId())
                .orElseThrow(() -> new ResourceNotFoundException("UiComponent", req.getComponentId()));
        UiOption opt = UiOption.builder()
                .component(component).optionValue(req.getOptionValue())
                .optionLabel(req.getOptionLabel()).colorTag(req.getColorTag())
                .icon(req.getIcon()).sortOrder(req.getSortOrder())
                .isActive(req.isActive()).allowedSides(req.getAllowedSides())
                .transitionsJson(req.getTransitionsJson()).tenantId(tenantId)
                .build();
        optionRepository.save(opt);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(toMap("id", opt.getId(),
                        "value", opt.getOptionValue(), "label", opt.getOptionLabel())));
    }

    @GetMapping("/options/{componentId}")
    @Operation(summary = "List all options for a component")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listOptions(
            @PathVariable Long componentId) {
        List<Map<String, Object>> result = optionRepository
                .findByComponentIdAndIsActiveTrueOrderBySortOrder(componentId)
                .stream()
                .map(o -> Map.<String, Object>of(
                        "id", o.getId(), "value", o.getOptionValue(),
                        "label", o.getOptionLabel(),
                        "colorTag", o.getColorTag() != null ? o.getColorTag() : "",
                        "icon", o.getIcon() != null ? o.getIcon() : "",
                        "sortOrder", o.getSortOrder()))
                .toList();
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PutMapping("/options/{id}")
    @Operation(summary = "Update an option — change label, color, sort order")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateOption(
            @PathVariable Long id, @RequestBody UiOptionRequest req) {
        UiOption opt = optionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("UiOption", id));
        if (req.getOptionLabel()      != null) opt.setOptionLabel(req.getOptionLabel());
        if (req.getColorTag()         != null) opt.setColorTag(req.getColorTag());
        if (req.getIcon()             != null) opt.setIcon(req.getIcon());
        if (req.getSortOrder()        != null) opt.setSortOrder(req.getSortOrder());
        if (req.getAllowedSides()     != null) opt.setAllowedSides(req.getAllowedSides());
        if (req.getTransitionsJson()  != null) opt.setTransitionsJson(req.getTransitionsJson());
        opt.setActive(req.isActive());
        optionRepository.save(opt);
        return ResponseEntity.ok(ApiResponse.success(toMap("id", opt.getId(),
                "value", opt.getOptionValue(), "label", opt.getOptionLabel())));
    }

    @DeleteMapping("/options/{id}")
    @Operation(summary = "Delete an option")
    public ResponseEntity<ApiResponse<Void>> deleteOption(@PathVariable Long id) {
        optionRepository.deleteById(id);
        return ResponseEntity.ok(ApiResponse.success());
    }

    // ══════════════════════════════════════════════════════════════
    // LAYOUTS
    // ══════════════════════════════════════════════════════════════

    @PostMapping("/layouts")
    @Operation(summary = "Create a table layout (column definitions for a screen)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createLayout(
            @Valid @RequestBody UiLayoutRequest req) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        UiLayout layout = UiLayout.builder()
                .layoutKey(req.getLayoutKey()).screen(req.getScreen())
                .title(req.getTitle()).columnsJson(req.getColumnsJson())
                .filtersJson(req.getFiltersJson()).roleAccessJson(req.getRoleAccessJson())
                .selectable(req.isSelectable()).reorderable(req.isReorderable())
                .tenantId(tenantId)
                .build();
        layoutRepository.save(layout);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(toMap("id", layout.getId(), "layoutKey", layout.getLayoutKey())));
    }

    @PutMapping("/layouts/{id}")
    @Operation(summary = "Update a layout — change columns, filters, access rules")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateLayout(
            @PathVariable Long id, @RequestBody UiLayoutRequest req) {
        UiLayout layout = layoutRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("UiLayout", id));
        if (req.getTitle()          != null) layout.setTitle(req.getTitle());
        if (req.getColumnsJson()    != null) layout.setColumnsJson(req.getColumnsJson());
        if (req.getFiltersJson()    != null) layout.setFiltersJson(req.getFiltersJson());
        if (req.getRoleAccessJson() != null) layout.setRoleAccessJson(req.getRoleAccessJson());
        layout.setSelectable(req.isSelectable());
        layout.setReorderable(req.isReorderable());
        layoutRepository.save(layout);
        return ResponseEntity.ok(ApiResponse.success(toMap("id", layout.getId(), "layoutKey", layout.getLayoutKey())));
    }

    @DeleteMapping("/layouts/{id}")
    @Operation(summary = "Delete a layout")
    public ResponseEntity<ApiResponse<Void>> deleteLayout(@PathVariable Long id) {
        layoutRepository.deleteById(id);
        return ResponseEntity.ok(ApiResponse.success());
    }

    // ══════════════════════════════════════════════════════════════
    // FORMS
    // ══════════════════════════════════════════════════════════════

    @PostMapping("/forms")
    @Operation(summary = "Create a dynamic form definition")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createForm(
            @Valid @RequestBody UiFormRequest req) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        UiForm form = UiForm.builder()
                .formKey(req.getFormKey()).title(req.getTitle())
                .description(req.getDescription()).submitUrl(req.getSubmitUrl())
                .httpMethod(req.getHttpMethod()).stepsJson(req.getStepsJson())
                .tenantId(tenantId)
                .build();
        formRepository.save(form);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(toMap("id", form.getId(), "formKey", form.getFormKey())));
    }

    @GetMapping("/forms")
    @Operation(summary = "List all form definitions — paginated")
    public ResponseEntity<ApiResponse<PaginatedResponse<Map<String, Object>>>> listForms(
            @RequestParam Map<String, String> allParams) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(dbRepository.findAll(
                UiForm.class,
                utilityService.getpageDetails(allParams),
                (cb, root) -> List.of(cb.or(
                        cb.isNull(root.get("tenantId")),
                        cb.equal(root.get("tenantId"), tenantId))),
                (cb, root) -> Map.of("formkey", root.get("formKey"),
                        "title", root.get("title")),
                f -> Map.of("id", f.getId(), "formKey", f.getFormKey(),
                        "title", f.getTitle() != null ? f.getTitle() : "",
                        "description", f.getDescription() != null ? f.getDescription() : "",
                        "submitUrl", f.getSubmitUrl() != null ? f.getSubmitUrl() : "",
                        "httpMethod", f.getHttpMethod() != null ? f.getHttpMethod() : "POST"))));
    }

    @GetMapping("/form-fields/{formId}")
    @Operation(summary = "List all fields for a form")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listFormFields(
            @PathVariable Long formId) {
        List<Map<String, Object>> fields = formFieldRepository
                .findByFormIdAndIsVisibleTrueOrderBySortOrder(formId)
                .stream()
                .map(f -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id",                   f.getId());
                    m.put("fieldKey",             f.getFieldKey());
                    m.put("fieldType",            f.getFieldType().name());
                    m.put("label",                f.getLabel()                != null ? f.getLabel()                : "");
                    m.put("placeholder",          f.getPlaceholder()          != null ? f.getPlaceholder()          : "");
                    m.put("helperText",           f.getHelperText()           != null ? f.getHelperText()           : "");
                    m.put("isRequired",           f.isRequired());
                    m.put("isVisible",            f.isVisible());
                    m.put("sortOrder",            f.getSortOrder()            != null ? f.getSortOrder()            : 0);
                    m.put("gridCols",             f.getGridCols()             != null ? f.getGridCols()             : 12);
                    m.put("optionsComponentKey",  f.getOptionsComponentKey()  != null ? f.getOptionsComponentKey()  : "");
                    return m;
                })
                .collect(java.util.stream.Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(fields));
    }

    @PutMapping("/forms/{id}")
    @Operation(summary = "Update a form")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateForm(
            @PathVariable Long id, @RequestBody UiFormRequest req) {
        UiForm form = formRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("UiForm", id));
        if (req.getTitle()       != null) form.setTitle(req.getTitle());
        if (req.getDescription() != null) form.setDescription(req.getDescription());
        if (req.getSubmitUrl()   != null) form.setSubmitUrl(req.getSubmitUrl());
        if (req.getHttpMethod()  != null) form.setHttpMethod(req.getHttpMethod());
        if (req.getStepsJson()   != null) form.setStepsJson(req.getStepsJson());
        formRepository.save(form);
        return ResponseEntity.ok(ApiResponse.success(toMap("id", form.getId(), "formKey", form.getFormKey())));
    }

    @DeleteMapping("/forms/{id}")
    @Operation(summary = "Delete a form and all its fields")
    public ResponseEntity<ApiResponse<Void>> deleteForm(@PathVariable Long id) {
        formRepository.deleteById(id);
        return ResponseEntity.ok(ApiResponse.success());
    }

    // ══════════════════════════════════════════════════════════════
    // FORM FIELDS
    // ══════════════════════════════════════════════════════════════

    @PostMapping("/form-fields")
    @Operation(summary = "Add a field to a form")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createField(
            @Valid @RequestBody UiFormFieldRequest req) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        UiForm form = formRepository.findById(req.getFormId())
                .orElseThrow(() -> new ResourceNotFoundException("UiForm", req.getFormId()));
        UiFormField field = UiFormField.builder()
                .form(form).fieldKey(req.getFieldKey())
                .fieldType(UiFormField.FieldType.valueOf(req.getFieldType()))
                .label(req.getLabel()).placeholder(req.getPlaceholder())
                .helperText(req.getHelperText()).isRequired(req.isRequired())
                .isVisible(req.isVisible()).sortOrder(req.getSortOrder())
                .optionsComponentKey(req.getOptionsComponentKey())
                .validationRulesJson(req.getValidationRulesJson())
                .dependsOnJson(req.getDependsOnJson())
                .gridCols(req.getGridCols()).stepNumber(req.getStepNumber())
                .tenantId(tenantId)
                .build();
        formFieldRepository.save(field);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(toMap("id", field.getId(), "fieldKey", field.getFieldKey())));
    }

    @PutMapping("/form-fields/{id}")
    @Operation(summary = "Update a form field — label, validation, visibility, grid width")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateField(
            @PathVariable Long id, @RequestBody UiFormFieldRequest req) {
        UiFormField f = formFieldRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("UiFormField", id));
        if (req.getLabel()                != null) f.setLabel(req.getLabel());
        if (req.getPlaceholder()          != null) f.setPlaceholder(req.getPlaceholder());
        if (req.getHelperText()           != null) f.setHelperText(req.getHelperText());
        if (req.getValidationRulesJson()  != null) f.setValidationRulesJson(req.getValidationRulesJson());
        if (req.getDependsOnJson()        != null) f.setDependsOnJson(req.getDependsOnJson());
        if (req.getOptionsComponentKey()  != null) f.setOptionsComponentKey(req.getOptionsComponentKey());
        if (req.getGridCols()            != null) f.setGridCols(req.getGridCols());
        if (req.getSortOrder()           != null) f.setSortOrder(req.getSortOrder());
        if (req.getStepNumber()          != null) f.setStepNumber(req.getStepNumber());
        f.setRequired(req.isRequired());
        f.setVisible(req.isVisible());
        formFieldRepository.save(f);
        return ResponseEntity.ok(ApiResponse.success(toMap("id", f.getId(), "fieldKey", f.getFieldKey())));
    }

    @DeleteMapping("/form-fields/{id}")
    @Operation(summary = "Remove a field from a form")
    public ResponseEntity<ApiResponse<Void>> deleteField(@PathVariable Long id) {
        formFieldRepository.deleteById(id);
        return ResponseEntity.ok(ApiResponse.success());
    }

    // ══════════════════════════════════════════════════════════════
    // ACTIONS
    // ══════════════════════════════════════════════════════════════

    @PostMapping("/actions")
    @Operation(summary = "Add an action button to a screen")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createAction(
            @Valid @RequestBody UiActionRequest req) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        UiAction action = UiAction.builder()
                .screenKey(req.getScreenKey()).actionKey(req.getActionKey())
                .label(req.getLabel()).icon(req.getIcon()).variant(req.getVariant())
                .apiEndpoint(req.getApiEndpoint()).httpMethod(req.getHttpMethod())
                .payloadTemplateJson(req.getPayloadTemplateJson())
                .requiredPermission(req.getRequiredPermission())
                .allowedSides(req.getAllowedSides())
                .allowedStatusesJson(req.getAllowedStatusesJson())
                .requiresConfirmation(req.isRequiresConfirmation())
                .confirmationMessage(req.getConfirmationMessage())
                .requiresRemarks(req.isRequiresRemarks())
                .sortOrder(req.getSortOrder()).isActive(req.isActive())
                .tenantId(tenantId)
                .build();
        actionRepository.save(action);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(toMap("id", action.getId(), "actionKey", action.getActionKey())));
    }

    @PutMapping("/actions/{id}")
    @Operation(summary = "Update an action button")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateAction(
            @PathVariable Long id, @RequestBody UiActionRequest req) {
        UiAction a = actionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("UiAction", id));
        if (req.getLabel()                != null) a.setLabel(req.getLabel());
        if (req.getIcon()                 != null) a.setIcon(req.getIcon());
        if (req.getVariant()              != null) a.setVariant(req.getVariant());
        if (req.getApiEndpoint()          != null) a.setApiEndpoint(req.getApiEndpoint());
        if (req.getPayloadTemplateJson()  != null) a.setPayloadTemplateJson(req.getPayloadTemplateJson());
        if (req.getRequiredPermission()   != null) a.setRequiredPermission(req.getRequiredPermission());
        if (req.getAllowedSides()         != null) a.setAllowedSides(req.getAllowedSides());
        if (req.getAllowedStatusesJson()  != null) a.setAllowedStatusesJson(req.getAllowedStatusesJson());
        if (req.getConfirmationMessage()  != null) a.setConfirmationMessage(req.getConfirmationMessage());
        if (req.getSortOrder()           != null) a.setSortOrder(req.getSortOrder());
        a.setRequiresConfirmation(req.isRequiresConfirmation());
        a.setRequiresRemarks(req.isRequiresRemarks());
        a.setActive(req.isActive());
        actionRepository.save(a);
        return ResponseEntity.ok(ApiResponse.success(toMap("id", a.getId(), "actionKey", a.getActionKey())));
    }

    @DeleteMapping("/actions/{id}")
    @Operation(summary = "Remove an action button from a screen")
    public ResponseEntity<ApiResponse<Void>> deleteAction(@PathVariable Long id) {
        actionRepository.deleteById(id);
        return ResponseEntity.ok(ApiResponse.success());
    }

    // ══════════════════════════════════════════════════════════════
    // DASHBOARD WIDGETS
    // ══════════════════════════════════════════════════════════════

    @PostMapping("/widgets")
    @Operation(summary = "Add a dashboard widget")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createWidget(
            @Valid @RequestBody DashboardWidgetRequest req) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        DashboardWidget w = DashboardWidget.builder()
                .widgetKey(req.getWidgetKey())
                .widgetType(DashboardWidget.WidgetType.valueOf(req.getWidgetType()))
                .title(req.getTitle()).subtitle(req.getSubtitle())
                .dataEndpoint(req.getDataEndpoint()).dataPath(req.getDataPath())
                .refreshIntervalSeconds(req.getRefreshIntervalSeconds())
                .configJson(req.getConfigJson())
                .requiredPermission(req.getRequiredPermission())
                .allowedSidesJson(req.getAllowedSidesJson())
                .sortOrder(req.getSortOrder()).gridCols(req.getGridCols())
                .isActive(req.isActive()).clickThroughRoute(req.getClickThroughRoute())
                .tenantId(tenantId)
                .build();
        widgetRepository.save(w);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(toMap("id", w.getId(), "widgetKey", w.getWidgetKey())));
    }

    @PutMapping("/widgets/{id}")
    @Operation(summary = "Update a dashboard widget")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateWidget(
            @PathVariable Long id, @RequestBody DashboardWidgetRequest req) {
        DashboardWidget w = widgetRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("DashboardWidget", id));
        if (req.getTitle()                   != null) w.setTitle(req.getTitle());
        if (req.getSubtitle()                != null) w.setSubtitle(req.getSubtitle());
        if (req.getDataEndpoint()            != null) w.setDataEndpoint(req.getDataEndpoint());
        if (req.getDataPath()                != null) w.setDataPath(req.getDataPath());
        if (req.getRefreshIntervalSeconds()  != null) w.setRefreshIntervalSeconds(req.getRefreshIntervalSeconds());
        if (req.getConfigJson()              != null) w.setConfigJson(req.getConfigJson());
        if (req.getAllowedSidesJson()        != null) w.setAllowedSidesJson(req.getAllowedSidesJson());
        if (req.getSortOrder()              != null) w.setSortOrder(req.getSortOrder());
        if (req.getGridCols()               != null) w.setGridCols(req.getGridCols());
        if (req.getClickThroughRoute()      != null) w.setClickThroughRoute(req.getClickThroughRoute());
        w.setActive(req.isActive());
        widgetRepository.save(w);
        return ResponseEntity.ok(ApiResponse.success(toMap("id", w.getId(), "widgetKey", w.getWidgetKey())));
    }

    @DeleteMapping("/widgets/{id}")
    @Operation(summary = "Remove a dashboard widget")
    public ResponseEntity<ApiResponse<Void>> deleteWidget(@PathVariable Long id) {
        widgetRepository.deleteById(id);
        return ResponseEntity.ok(ApiResponse.success());
    }

    // ══════════════════════════════════════════════════════════════
    // FEATURE FLAGS
    // ══════════════════════════════════════════════════════════════

    @PostMapping("/flags")
    @Operation(summary = "Create a feature flag")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createFlag(
            @Valid @RequestBody FeatureFlagRequest req) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        FeatureFlag flag = FeatureFlag.builder()
                .flagKey(req.getFlagKey()).isEnabled(req.isEnabled())
                .description(req.getDescription())
                .allowedSidesJson(req.getAllowedSidesJson())
                .tenantId(tenantId)
                .build();
        featureFlagRepository.save(flag);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(toMap("id", flag.getId(), "flagKey", flag.getFlagKey(),
                        "isEnabled", flag.isEnabled())));
    }

    @GetMapping("/flags")
    @Operation(summary = "List all feature flags")
    public ResponseEntity<ApiResponse<PaginatedResponse<Map<String, Object>>>> listFlags(
            @RequestParam Map<String, String> allParams) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(dbRepository.findAll(
                FeatureFlag.class,
                utilityService.getpageDetails(allParams),
                (cb, root) -> List.of(cb.or(
                        cb.isNull(root.get("tenantId")),
                        cb.equal(root.get("tenantId"), tenantId))),
                (cb, root) -> Map.of("flagkey", root.get("flagKey")),
                f -> Map.of("id", f.getId(), "flagKey", f.getFlagKey(),
                        "isEnabled", f.isEnabled(),
                        "description", f.getDescription() != null ? f.getDescription() : ""))));
    }

    @PutMapping("/flags/{id}")
    @Operation(summary = "Toggle or update a feature flag")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateFlag(
            @PathVariable Long id, @RequestBody FeatureFlagRequest req) {
        FeatureFlag flag = featureFlagRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("FeatureFlag", id));
        if (req.getDescription()      != null) flag.setDescription(req.getDescription());
        if (req.getAllowedSidesJson() != null) flag.setAllowedSidesJson(req.getAllowedSidesJson());
        flag.setEnabled(req.isEnabled());
        featureFlagRepository.save(flag);
        return ResponseEntity.ok(ApiResponse.success(toMap("id", flag.getId(),
                "flagKey", flag.getFlagKey(), "isEnabled", flag.isEnabled())));
    }

    @DeleteMapping("/flags/{id}")
    @Operation(summary = "Delete a feature flag")
    public ResponseEntity<ApiResponse<Void>> deleteFlag(@PathVariable Long id) {
        featureFlagRepository.deleteById(id);
        return ResponseEntity.ok(ApiResponse.success());
    }

    // ══════════════════════════════════════════════════════════════
    // BRANDING
    // ══════════════════════════════════════════════════════════════

    @PostMapping("/branding")
    @Operation(summary = "Set branding for the current tenant")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createBranding(
            @Valid @RequestBody TenantBrandingRequest req) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        TenantBranding branding = TenantBranding.builder()
                .tenantId(tenantId).companyName(req.getCompanyName())
                .logoUrl(req.getLogoUrl()).faviconUrl(req.getFaviconUrl())
                .primaryColor(req.getPrimaryColor() != null ? req.getPrimaryColor() : "#1e40af")
                .accentColor(req.getAccentColor() != null ? req.getAccentColor() : "#7c3aed")
                .sidebarTheme(req.getSidebarTheme() != null ? req.getSidebarTheme() : "dark")
                .supportEmail(req.getSupportEmail()).supportUrl(req.getSupportUrl())
                .footerText(req.getFooterText())
                .build();
        brandingRepository.save(branding);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(toMap("tenantId", tenantId, "companyName", req.getCompanyName())));
    }

    @PutMapping("/branding")
    @Operation(summary = "Update branding for the current tenant")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateBranding(
            @RequestBody TenantBrandingRequest req) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        TenantBranding b = brandingRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("TenantBranding", "tenantId", tenantId));
        if (req.getCompanyName()  != null) b.setCompanyName(req.getCompanyName());
        if (req.getLogoUrl()      != null) b.setLogoUrl(req.getLogoUrl());
        if (req.getFaviconUrl()   != null) b.setFaviconUrl(req.getFaviconUrl());
        if (req.getPrimaryColor() != null) b.setPrimaryColor(req.getPrimaryColor());
        if (req.getAccentColor()  != null) b.setAccentColor(req.getAccentColor());
        if (req.getSidebarTheme() != null) b.setSidebarTheme(req.getSidebarTheme());
        if (req.getSupportEmail() != null) b.setSupportEmail(req.getSupportEmail());
        if (req.getSupportUrl()   != null) b.setSupportUrl(req.getSupportUrl());
        if (req.getFooterText()   != null) b.setFooterText(req.getFooterText());
        brandingRepository.save(b);
        return ResponseEntity.ok(ApiResponse.success(
                toMap("tenantId", tenantId, "updated", true)));
    }

    // ── Util ──────────────────────────────────────────────────────
    private Map<String, Object> toMap(Object... kvPairs) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kvPairs.length - 1; i += 2) {
            map.put(kvPairs[i].toString(), kvPairs[i + 1]);
        }
        return map;
    }
}