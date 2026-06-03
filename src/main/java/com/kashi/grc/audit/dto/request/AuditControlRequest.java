package com.kashi.grc.audit.dto.request;

import com.kashi.grc.audit.domain.AuditControl;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AuditControlRequest {
    @NotBlank private String name;
    private String description;
    private String controlCode;
    private String frameworkRef;
    private AuditControl.TestType testType;
    private String controlTag;   // for KashiGuard rule matching
}
