package com.kashi.grc.actionitem.dto;

import com.kashi.grc.actionitem.domain.ActionItem;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * ActionItemStatusUpdate — PATCH /v1/action-items/:id/status
 *
 * Status transitions enforced by ActionItemService:
 *   OPEN → IN_PROGRESS        : assignedTo or any user with resolutionRole
 *   OPEN/IN_PROGRESS → RESOLVED: resolutionReservedFor (if set) OR resolutionRole holder
 *   * → DISMISSED             : assignedTo or ORG_ADMIN
 *   RESOLVED → OPEN           : reopen if resolution not accepted
 */
@Data
public class ActionItemStatusUpdate {
    @NotNull private ActionItem.Status status;
    private String resolutionNote;
}