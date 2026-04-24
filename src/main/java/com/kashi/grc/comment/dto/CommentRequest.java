package com.kashi.grc.comment.dto;

import com.kashi.grc.comment.domain.EntityComment;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CommentRequest {
    @NotNull  private EntityComment.EntityType entityType;
    @NotNull  private Long                     entityId;

    // For QUESTION_RESPONSE only
    private Long questionInstanceId;
    private Long responseId;

    @NotBlank private String                   commentText;

    private EntityComment.CommentType commentType = EntityComment.CommentType.COMMENT;
    private EntityComment.Visibility  visibility  = EntityComment.Visibility.ALL;
    private Long                      parentCommentId;
}