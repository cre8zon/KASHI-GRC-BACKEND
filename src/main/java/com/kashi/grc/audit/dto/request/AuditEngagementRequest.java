// ── AuditEngagementRequest.java ───────────────────────────────────────────────
package com.kashi.grc.audit.dto.request;

import com.kashi.grc.audit.domain.AuditTemplate;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class AuditEngagementRequest {
    private Long   projectId;  // nullable — standalone engagement without project
    private Long   projectInstanceId; // set by createProjectInstance cascade — bypasses findByOriginalProjectId lookup
    @NotBlank private String name;
    private String  description;
    private Long    templateId;
    private String  frameworkRef;
    private AuditTemplate.AuditType auditType;
    private Long    leadAuditorId;
    private Long    ownerId;
    private LocalDate plannedStart;
    private LocalDate plannedEnd;
    private Long    workflowId;   // null = auto-discover by convention
}