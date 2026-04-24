package com.kashi.grc.actionitem.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kashi.grc.actionitem.domain.ActionItem;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ActionItemResponse {
    private Long   id;
    private Long   blueprintId;

    private Long   assignedTo;
    private String assignedToName;
    private String assignedGroupRole;
    private Long   createdBy;
    private String createdByName;

    private ActionItem.SourceType sourceType;
    private Long                  sourceId;
    private ActionItem.EntityType entityType;
    private Long                  entityId;

    private String title;
    private String description;

    private ActionItem.Status   status;
    private ActionItem.Priority priority;
    private LocalDateTime       dueAt;

    private Long          resolutionReservedFor;
    private String        resolutionReservedForName;
    private String        resolutionRole;
    private LocalDateTime resolvedAt;
    private Long          resolvedBy;
    private String        resolvedByName;
    private String        resolutionNote;

    private String navContext; // JSON passthrough

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Remediation / clarification specific fields
    private String  remediationType;      // REMEDIATION_REQUEST | CLARIFICATION
    private String  severity;             // LOW | MEDIUM | HIGH | CRITICAL
    private String  expectedEvidence;     // what the vendor must provide
    private Boolean acceptedRisk;         // reviewer accepted risk without fix
    private Long    acceptedRiskBy;
    private String  acceptedRiskByName;
    private String  acceptedRiskNote;
    private LocalDateTime acceptedRiskAt;

    // Computed convenience fields
    private boolean canResolve;   // set by service based on calling user
    private boolean isOverdue;
}