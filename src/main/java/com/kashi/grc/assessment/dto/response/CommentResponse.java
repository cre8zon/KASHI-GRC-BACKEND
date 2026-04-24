package com.kashi.grc.assessment.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CommentResponse {
    private Long commentId;
    private String commentText;
    private Long commentedBy;
    private String commenterName;
    private LocalDateTime createdAt;
}
