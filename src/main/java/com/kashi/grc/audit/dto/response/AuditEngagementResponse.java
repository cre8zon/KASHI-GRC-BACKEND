package com.kashi.grc.audit.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kashi.grc.audit.domain.AuditEngagement;
import com.kashi.grc.audit.domain.AuditTemplate;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuditEngagementResponse {
    private Long   id;
    private String engagementRef;
    private Long   projectId;
    private Long   projectInstanceId;   // frozen project snapshot — audit trail
    private String name;
    private String description;
    private AuditTemplate.AuditType auditType;
    private AuditEngagement.Status  status;
    private String frameworkRef;
    private Long   leadAuditorId;
    private Long   ownerId;
    private Integer totalControls;
    private Integer testedControls;
    private Integer submittedControls;
    private Integer passedControls;
    private Integer failedControls;
    private Integer openFindingCount;
    private Long   workflowInstanceId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDate plannedStart;
    private LocalDate plannedEnd;
    private String listScreenKey;
    private String detailScreenKey;
}