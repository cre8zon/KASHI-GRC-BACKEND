package com.kashi.grc.uiconfig.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

@Data @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UiOptionResponse {
    private Long   id;
    private String value;
    private String label;
    private String colorTag;
    private String icon;
    private Integer sortOrder;
    private String transitionsJson;
}
