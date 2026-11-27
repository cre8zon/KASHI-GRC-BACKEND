package com.kashi.grc.uiconfig.service;

import com.kashi.grc.uiconfig.dto.response.*;
import java.util.List;

public interface UiConfigService {

    /** One call after login — navigation tree, branding, feature flags, dashboard widgets */
    AppBootstrapResponse bootstrap();

    /** Everything a screen needs: components, layout, actions, feature flags */
    ScreenConfigResponse getScreenConfig(String screenKey);

    /** Navigation tree filtered by user's role side and permissions */
    List<UiNavigationItemResponse> getNavigation();

    /** Form field definitions + validation rules for a given form */
    UiFormResponse getForm(String formKey);

    /** Action buttons available on a screen for the current user */
    List<UiActionResponse> getActions(String screenKey, String entityStatus);

    /**
     * Server-side check behind the action buttons, for ModuleActionGuard.
     *
     * Finds the active ui_actions bound to this endpoint (http_method plus
     * api_endpoint, with {placeholders} matching one path segment) and answers
     * whether the caller would be shown at least one of them, by exactly the
     * rule getActions uses: required_permission (any of, including
     * permission_grants and their revokes) and allowed_sides.
     *
     * @return null when no active ui_action is bound to the endpoint, so the
     *         caller can leave that request to the service's own checks
     */
    Boolean callerMayInvokeActionEndpoint(String httpMethod, String path);

    /** Dashboard widgets filtered by role side */
    List<DashboardWidgetResponse> getDashboardWidgets();

    /** Tenant branding (logo, colors) */
    TenantBrandingResponse getBranding();

    // ── Dashboards ────────────────────────────────────────────────────────────
    // Reads live in the impl alongside extractSides/extractPermissions, which
    // already handle role-derived permissions AND the permission_grants table
    // with its explicit revokes.

    java.util.List<java.util.Map<String, Object>> listDashboards(String entityType, String scope);

    java.util.Map<String, Object> getDashboard(String key);

    java.util.Map<String, Object> defaultDashboard(String entityType);
}