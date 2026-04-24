package com.kashi.grc.uiconfig.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * The single response returned by GET /v1/ui-config/screen/{screenKey}.
 * One call gives the frontend everything it needs for a screen:
 * components (dropdowns, badges), layout (table columns), actions, feature flags.
 */
@Data @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScreenConfigResponse {
    /** screenKey -> UiComponentResponse (includes options) */
    private Map<String, UiComponentResponse> components;
    /** Table columns, filters, access rules */
    private LayoutResponse layout;
    /** Available action buttons on this screen */
    private List<UiActionResponse> actions;
    /** flagKey -> boolean */
    private Map<String, Boolean> featureFlags;
}
