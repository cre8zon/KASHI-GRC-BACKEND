package com.kashi.grc.audit.dto.request;

import com.kashi.grc.audit.domain.*;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AuditTemplateRequest {
    /**
     * Primary name field — maps to template_name column.
     * Also accepted as "name" for backward compatibility with the frontend form.
     */
    @NotBlank private String templateName;
    private String name;             // alias — if templateName is null, falls back to name
    private String description;
    private String frameworkRef;
    private AuditTemplate.AuditType auditType;

    /** Resolves the effective name — prefers templateName, falls back to name */
    public String getEffectiveName() {
        return templateName != null && !templateName.isBlank() ? templateName
                : name != null ? name : null;
    }
}