package com.kashi.grc.uiconfig.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UiFormRequest {
    @NotBlank public String formKey;
    public String title;
    public String description;
    @NotBlank public String submitUrl;
    public String httpMethod = "POST";
    public String stepsJson;
}
