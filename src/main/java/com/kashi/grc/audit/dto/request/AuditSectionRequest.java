package com.kashi.grc.audit.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request to create or update a library AuditSection.
 * parentId=null → create as a root section (depth=0)
 * parentId=X    → create as a child of section X (path + depth computed automatically)
 */
@Data
public class AuditSectionRequest {
    @NotBlank private String name;
    private String description;
    private String sectionCode;
    private Integer OrderNo;
    private String frameworkRef;

    /**
     * Parent section ID.
     * null     → top-level root section
     * non-null → child of the specified section
     */
    private Long parentId;
}