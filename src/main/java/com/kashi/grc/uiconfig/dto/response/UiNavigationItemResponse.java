package com.kashi.grc.uiconfig.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UiNavigationItemResponse {
    private Long   id;
    private String navKey;
    private String label;
    private String icon;
    private String route;
    private String parentKey;
    private Integer sortOrder;
    private String module;
    private String badgeCountEndpoint;
    /** Feature entitlement key — the frontend route guard blocks the route if the
     *  tenant lacks it. Mirrors the server-side @RequiresFeature enforcement. */
    private String requiredFeature;
    private List<UiNavigationItemResponse> children;
    @JsonProperty("isActive")
    private boolean isActive;
}