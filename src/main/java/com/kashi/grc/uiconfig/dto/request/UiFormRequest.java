package com.kashi.grc.uiconfig.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UiFormRequest {
    @NotBlank public String formKey;
    public String title;
    public String description;
    public String submitUrl;   // optional — DynamicForm falls back to ModuleBlueprint.apiBasePath
    public String httpMethod = "POST";
    public String stepsJson;
}