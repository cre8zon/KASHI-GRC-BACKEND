package com.kashi.grc.workflow.dto.response;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder; import lombok.Data;
import java.time.LocalDateTime; import java.util.List;
@Data @Builder @JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkflowResponse {
    private Long id; private String name; private String entityType;
    private String description; private Integer version; private Boolean isActive;
    private LocalDateTime createdAt;
    private List<WorkflowStepResponse> steps;
}
