package com.kashi.grc.workflow.dto.response;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.kashi.grc.workflow.enums.WorkflowStatus;
import lombok.Builder; import lombok.Data;
import java.time.LocalDateTime; import java.util.List;
@Data @Builder @JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkflowInstanceResponse {
    private Long id; private Long tenantId; private Long workflowId; private String workflowName;
    private String entityType; private Long entityId;
    private Long currentStepId; private String currentStepName; private Integer currentStepOrder;
    private WorkflowStatus status; private LocalDateTime startedAt; private LocalDateTime completedAt;
    private LocalDateTime dueDate; private String priority; private Long initiatedBy; private String remarks;
    private List<StepInstanceResponse> stepInstances;
}
