package com.kashi.grc.uiconfig.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UiFormResponse {
    private String  formKey;
    private String  title;
    private String  description;
    private String  submitUrl;
    private String  httpMethod;
    private String  stepsJson;
    private List<UiFormFieldResponse> fields;
}
