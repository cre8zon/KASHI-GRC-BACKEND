package com.kashi.grc.uiconfig.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

@Data @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UiStateResponse {
    private Long   id;
    private String screenKey;
    private String stateType;
    private String title;
    private String description;
    private String icon;
    private String colorTag;
    private String ctaLabel;
    private String ctaAction;
    private String secondaryCtaLabel;
    private String secondaryCtaAction;
}
