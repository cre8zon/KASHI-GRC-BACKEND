package com.kashi.grc.audit.dto.request;

import com.kashi.grc.audit.domain.*;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.time.LocalDate;

@Data
public class AuditProjectRequest {
    @NotBlank private String name;
    private String    description;
    private Long      ownerId;
    private LocalDate plannedStart;
    private LocalDate plannedEnd;
    /** User-selected workflow for the project lifecycle (e.g. Audit Project Lifecycle, id=16) */
    private Long      workflowId;
}