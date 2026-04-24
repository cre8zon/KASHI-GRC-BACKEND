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
}
