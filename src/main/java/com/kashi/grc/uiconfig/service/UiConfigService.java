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

    /** Dashboard widgets filtered by role side */
    List<DashboardWidgetResponse> getDashboardWidgets();

    /** Tenant branding (logo, colors) */
    TenantBrandingResponse getBranding();
}
