package com.kashi.grc.workflow.dto.response;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder; import lombok.Data;
import java.time.LocalDateTime;
@Data @Builder @JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkflowHistoryResponse {
    private Long id; private Long workflowInstanceId; private Long stepInstanceId;
    private Long taskInstanceId; private String eventType;
    private String fromStatus; private String toStatus;
    private Long performedBy; private LocalDateTime performedAt; private String remarks;
    private Long stepId; private String stepName; private Integer stepOrder;
}
