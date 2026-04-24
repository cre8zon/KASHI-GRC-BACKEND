package com.kashi.grc.uiconfig.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * Defines every widget on every dashboard.
 * Role-filtered: only widgets matching the user's side are returned.
 * Add a KPI card or chart = insert one row.
 */
@Entity
@Table(name = "dashboard_widgets")
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class DashboardWidget extends BaseEntity {

    @Column(name = "widget_key", unique = true, nullable = false, length = 100)
    private String widgetKey;

    @Column(name = "widget_type", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private WidgetType widgetType;

    @Column(name = "title", length = 255)
    private String title;

    @Column(name = "subtitle", length = 255)
    private String subtitle;

    /** API endpoint to fetch widget data. e.g. '/v1/vendors?status=ACTIVE' */
    @Column(name = "data_endpoint", length = 255)
    private String dataEndpoint;

    /**
     * JSONPath to extract the display value from the API response.
     * e.g. 'pagination.totalItems' for a KPI card count.
     */
    @Column(name = "data_path", length = 255)
    private String dataPath;

    /** Auto-refresh interval in seconds. 0 = no auto-refresh. */
    @Column(name = "refresh_interval_seconds")
    @Builder.Default
    private Integer refreshIntervalSeconds = 300;

    /**
     * Extra configuration as JSON.
     * KPI: {"prefix": "$", "suffix": " vendors", "trend": true}
     * Chart: {"xAxis": "month", "yAxis": "count", "colors": ["#1e40af"]}
     */
    @Column(name = "config_json", columnDefinition = "JSON")
    private String configJson;

    /** Permission required to see this widget. NULL = no check. */
    @Column(name = "required_permission", length = 255)
    private String requiredPermission;

    /** Role sides that see this widget. JSON array: '["ORGANIZATION","SYSTEM"]'. NULL = all. */
    @Column(name = "allowed_sides_json", columnDefinition = "JSON")
    private String allowedSidesJson;

    @Column(name = "sort_order")
    @Builder.Default
    private Integer sortOrder = 0;

    /** Grid columns: 3=quarter, 4=third, 6=half, 12=full */
    @Column(name = "grid_cols")
    @Builder.Default
    private Integer gridCols = 6;

    @Column(name = "is_active")
    @Builder.Default
    private boolean isActive = true;

    /** Route to navigate to when widget is clicked. NULL = no link. */
    @Column(name = "click_through_route", length = 255)
    private String clickThroughRoute;

    @Column(name = "tenant_id")
    private Long tenantId;

    public enum WidgetType {
        KPI_CARD, BAR_CHART, LINE_CHART, PIE_CHART,
        DONUT_CHART, HEATMAP, AREA_CHART, TABLE,
        CALENDAR, PROGRESS_BAR, ACTIVITY_FEED
    }
}
