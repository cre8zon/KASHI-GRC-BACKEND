package com.kashi.grc.actionitem.dto;

import com.kashi.grc.actionitem.domain.ActionItem;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ActionItemStatusUpdate {
    @NotNull private ActionItem.Status status;
    private String resolutionNote;
}
