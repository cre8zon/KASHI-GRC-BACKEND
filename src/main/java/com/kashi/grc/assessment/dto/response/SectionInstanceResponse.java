package com.kashi.grc.assessment.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SectionInstanceResponse {
    private Long          sectionInstanceId;
    private String        sectionName;
    private Integer       sectionOrderNo;
    private Long          assignedUserId;
    private String        assignedUserName;
    // Submission state — null means editable, non-null means locked
    private LocalDateTime submittedAt;
    private Long          submittedBy;
    private String        submittedByName;
    private LocalDateTime reopenedAt;
    private List<QuestionInstanceResponse> questions;
}