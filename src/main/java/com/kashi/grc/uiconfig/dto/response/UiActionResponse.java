package com.kashi.grc.uiconfig.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

@Data @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UiActionResponse {
    private String  actionKey;
    private String  label;
    private String  icon;
    private String  variant;
    private String  apiEndpoint;
    private String  httpMethod;
    private String  payloadTemplateJson;
    private boolean requiresConfirmation;
    private String  confirmationMessage;
    private boolean requiresRemarks;
    private Integer sortOrder;
}
