package com.kashi.grc.uiconfig.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.uiconfig.dto.response.*;
import com.kashi.grc.uiconfig.service.UiConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Read-only UI Config endpoints consumed by the frontend.
 *
 * GET /v1/ui-config/bootstrap              — Called once after login
 * GET /v1/ui-config/navigation             — Sidebar tree for current user
 * GET /v1/ui-config/screen/{screenKey}     — All config a screen needs
 * GET /v1/ui-config/form/{formKey}         — Form fields + validation rules
 * GET /v1/ui-config/actions/{screenKey}    — Action buttons for screen
 * GET /v1/ui-config/dashboard              — Widgets for current user's role
 * GET /v1/ui-config/branding               — Tenant logo, colors, theme
 */
@RestController
@RequestMapping("/v1/ui-config")
@Tag(name = "UI Config (Frontend)", description = "DB-driven frontend configuration — called by React app")
@RequiredArgsConstructor
public class UiConfigController {

    private final UiConfigService uiConfigService;

    /**
     * Single call made immediately after login.
     * Returns navigation, branding, dashboard widgets, and feature flags.
     * Cache this in React Query for the entire session.
     */
    @GetMapping("/bootstrap")
    @Operation(summary = "Bootstrap the entire app after login — nav, branding, widgets, flags")
    public ResponseEntity<ApiResponse<AppBootstrapResponse>> bootstrap() {
        return ResponseEntity.ok(ApiResponse.success(uiConfigService.bootstrap()));
    }

    /**
     * Navigation tree filtered by the current user's role side and permissions.
     * ORGANIZATION users get TPRM/Audit/Workflow menu.
     * VENDOR users get only their assessment and document screens.
     */
    @GetMapping("/navigation")
    @Operation(summary = "Sidebar navigation tree for the current user")
    public ResponseEntity<ApiResponse<List<UiNavigationItemResponse>>> getNavigation() {
        return ResponseEntity.ok(ApiResponse.success(uiConfigService.getNavigation()));
    }

    /**
     * Everything a screen needs in one call:
     * - components (dropdowns, badges, radio groups with their options)
     * - layout (table columns and filters)
     * - actions (buttons visible to this user for this screen)
     * - featureFlags (relevant to this screen)
     */
    @GetMapping("/screen/{screenKey}")
    @Operation(summary = "Full config for a screen — components, layout, actions, feature flags")
    public ResponseEntity<ApiResponse<ScreenConfigResponse>> getScreenConfig(
            @PathVariable String screenKey) {
        return ResponseEntity.ok(ApiResponse.success(uiConfigService.getScreenConfig(screenKey)));
    }

    /**
     * Form field definitions for a dynamic form.
     * Returns fields in sort_order, each with type, label, validation rules,
     * conditional visibility, and options component key.
     */
    @GetMapping("/form/{formKey}")
    @Operation(summary = "Form field definitions + validation rules")
    public ResponseEntity<ApiResponse<UiFormResponse>> getForm(
            @PathVariable String formKey) {
        return ResponseEntity.ok(ApiResponse.success(uiConfigService.getForm(formKey)));
    }

    /**
     * Action buttons available to the current user on a screen.
     * Filtered by role side, permission, and optionally the entity's current status.
     * e.g. "Approve" only shows when task status = PENDING.
     */
    @GetMapping("/actions/{screenKey}")
    @Operation(summary = "Action buttons for a screen, filtered by role and entity status")
    public ResponseEntity<ApiResponse<List<UiActionResponse>>> getActions(
            @PathVariable String screenKey,
            @RequestParam(required = false) String entityStatus) {
        return ResponseEntity.ok(ApiResponse.success(
                uiConfigService.getActions(screenKey, entityStatus)));
    }

    /**
     * Dashboard widgets for the current user.
     * ORGANIZATION roles see risk heatmaps, compliance KPIs.
     * VENDOR roles see only their assessment progress widgets.
     */
    @GetMapping("/dashboard")
    @Operation(summary = "Dashboard widgets filtered by role side and permissions")
    public ResponseEntity<ApiResponse<List<DashboardWidgetResponse>>> getDashboardWidgets() {
        return ResponseEntity.ok(ApiResponse.success(uiConfigService.getDashboardWidgets()));
    }

    /**
     * Tenant branding — logo URL, primary color, accent color, sidebar theme.
     * Frontend injects these as CSS variables on page load.
     */
    @GetMapping("/branding")
    @Operation(summary = "Tenant branding: logo, colors, theme")
    public ResponseEntity<ApiResponse<TenantBrandingResponse>> getBranding() {
        return ResponseEntity.ok(ApiResponse.success(uiConfigService.getBranding()));
    }
}
