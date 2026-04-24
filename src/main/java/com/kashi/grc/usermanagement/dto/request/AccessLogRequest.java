package com.kashi.grc.usermanagement.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
public class AccessLogRequest {
    @NotNull
    public Long userId;
    @NotBlank
    public String action;
    public String ipAddress;
    public String userAgent;
    public String result;
    public Map<String, String> metadata;
}
