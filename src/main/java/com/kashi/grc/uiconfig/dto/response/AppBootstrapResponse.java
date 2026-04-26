package com.kashi.grc.uiconfig.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * The single call made right after login.
 * Returns everything the app needs to boot:
 * navigation tree, branding, global feature flags, dashboard widgets.
 * Cached by React Query for the session duration.
 */
@Data @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AppBootstrapResponse {
    private String tenantName;
    private String vendorName;
    private Map<String, String> userPreferences;
    private TenantBrandingResponse branding;
    private List<UiNavigationItemResponse> navigation;
    private List<DashboardWidgetResponse> dashboardWidgets;
    /** flagKey -> boolean */
    private Map<String, Boolean> featureFlags;
}