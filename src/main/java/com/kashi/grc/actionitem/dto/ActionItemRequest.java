package com.kashi.grc.actionitem.dto;

import com.kashi.grc.actionitem.domain.ActionItem;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ActionItemRequest {
    // Blueprint (optional — null for ad-hoc)
    // Provide either blueprintId (Long) or blueprintCode (String) — code is resolved to ID in service
    private Long   blueprintId;
    private String blueprintCode;  // alternative to blueprintId — resolved by service

    // Assignment
    private Long assignedTo;
    private String assignedGroupRole;

    // Source — what triggered this
    @NotNull private ActionItem.SourceType sourceType;
    @NotNull private Long sourceId;

    // Entity — what this is about
    @NotNull private ActionItem.EntityType entityType;
    @NotNull private Long entityId;

    // Content
    @NotBlank private String title;
    private String description;

    // Resolution
    private Long resolutionReservedFor;
    private String resolutionRole;

    // State
    private ActionItem.Priority priority;
    private String dueAt;

    // Navigation
    private String navContext; // JSON string
}