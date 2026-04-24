package com.kashi.grc.workflow.dto.request;

import com.kashi.grc.workflow.enums.ActionType;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class TaskActionRequest {
    @NotNull public Long taskInstanceId;
    @NotNull public ActionType actionType;
    public String remarks;
    /** For REASSIGN / DELEGATE */
    public Long targetUserId;
    /** For SEND_BACK — null means previous step */
    public Long targetStepId;
}
