package com.kashi.grc.uiconfig.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

@Data @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LayoutResponse {
    private String layoutKey;
    private String title;
    private String columnsJson;
    private String filtersJson;
    private boolean selectable;
    private boolean reorderable;
}
