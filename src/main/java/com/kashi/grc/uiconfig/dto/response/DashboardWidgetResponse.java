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
}
