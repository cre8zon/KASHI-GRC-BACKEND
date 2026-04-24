package com.kashi.grc.usermanagement.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
public class AccessEvaluateRequest {
    @NotNull
    public Long userId;
    @NotBlank
    public String action;
    @NotBlank
    public String resourceType;
    public Long resourceId;
    public Map<String, Object> context;
}
