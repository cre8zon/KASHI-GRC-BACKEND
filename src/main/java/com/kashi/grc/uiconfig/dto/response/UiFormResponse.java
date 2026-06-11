package com.kashi.grc.uiconfig.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import java.util.List;
import java.util.Map;

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
    /** Global components keyed by componentKey — used by SELECT fields for option resolution */
    private Map<String, UiComponentResponse> components;
}