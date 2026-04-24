package com.kashi.grc.assessment.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnswerResponse {
    private Long          responseId;
    private String        responseText;
    private Long          selectedOptionInstanceId;
    private List<Long>    selectedOptionInstanceIds; // MULTI_CHOICE
    private Double        scoreEarned;
    private String        reviewerStatus;
    private LocalDateTime submittedAt;
    private List<CommentResponse> comments;
    private List<Map<String, Object>> documents;
    // Audit: who actually answered this question (may differ from assignedUserId)
    private Long          answeredBy;
    private String        answeredByName;
}