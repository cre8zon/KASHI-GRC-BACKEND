package com.kashi.grc.workflow.dto.response;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.kashi.grc.workflow.enums.StepStatus;
import lombok.Builder; import lombok.Data;
import java.time.LocalDateTime; import java.util.List;
@Data @Builder @JsonInclude(JsonInclude.Include.NON_NULL)
public class StepInstanceResponse {
    private Long id; private Long workflowInstanceId; private Long stepId;
    private String stepName; private Integer stepOrder; private StepStatus status;
    private LocalDateTime startedAt; private LocalDateTime completedAt;
    private LocalDateTime slaDueAt; private Integer iterationCount; private String remarks;
    private List<TaskInstanceResponse> taskInstances;
}
