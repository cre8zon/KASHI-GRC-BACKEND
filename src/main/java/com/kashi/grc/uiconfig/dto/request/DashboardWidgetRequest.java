package com.kashi.grc.uiconfig.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class DashboardWidgetRequest {
    @NotBlank public String widgetKey;
    @NotBlank public String widgetType;
    public String  title;
    public String  subtitle;
    public String  dataEndpoint;
    public String  dataPath;
    public Integer refreshIntervalSeconds = 300;
    public String  configJson;
    public String  requiredPermission;
    public String  allowedSidesJson;
    public Integer sortOrder = 0;
    public Integer gridCols = 6;
    public boolean isActive = true;
    public String  clickThroughRoute;
}
