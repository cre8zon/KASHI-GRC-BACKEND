package com.kashi.grc.uiconfig.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

@Data @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DashboardWidgetResponse {
    private String  widgetKey;
    private String  widgetType;
    private String  title;
    private String  subtitle;
    private String  dataEndpoint;
    private String  dataPath;
    private Integer refreshIntervalSeconds;
    private String  configJson;
    private Integer gridCols;
    private Integer sortOrder;
    private String  clickThroughRoute;

    // Added with the dashboards table. All optional — a widget that sets none
    // of them behaves exactly as widgets did before.
    private Long    dashboardId;
    private String  filtersJson;
    private String  valueFormat;
    private String  thresholdsJson;
    private String  drillThroughJson;
    private String  emptyMessage;
}