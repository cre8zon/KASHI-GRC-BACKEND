package com.kashi.grc.uiconfig.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UiComponentResponse {
    private String componentKey;
    private String componentType;
    private String label;
    private String configJson;
    private List<UiOptionResponse> options;
}
