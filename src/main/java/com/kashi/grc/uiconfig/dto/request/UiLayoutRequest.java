package com.kashi.grc.uiconfig.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UiLayoutRequest {
    @NotBlank public String layoutKey;
    public String screen;
    public String title;
    @NotBlank public String columnsJson;
    public String filtersJson;
    public String roleAccessJson;
    public boolean selectable;
    public boolean reorderable;
    public String layoutMode;  // FULL_PAGE | DRAWER | SIDE_PANEL

    /**
     * JSON array of tab definitions for DETAIL screens.
     * Format: [{key: "overview", label: "Overview"}, {key: "tests", label: "Tests"}, ...]
     * Null means "use frontend defaults" — sent as null when only other fields are being updated.
     */
    public String tabsJson;
}