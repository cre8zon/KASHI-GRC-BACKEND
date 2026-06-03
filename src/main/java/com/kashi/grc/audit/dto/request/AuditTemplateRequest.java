package com.kashi.grc.audit.dto.request;

import com.kashi.grc.audit.domain.*;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AuditTemplateRequest {
    @NotBlank private String name;
    private String description;
    private String frameworkRef;
    private AuditTemplate.AuditType auditType;
}

