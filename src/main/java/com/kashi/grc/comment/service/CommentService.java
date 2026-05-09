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

    /**
     * User IDs explicitly @mentioned in the comment.
     * Populated by the frontend's mention-input component when user types @name
     * and selects from the autocomplete dropdown.
     * Backend sends a MENTIONED notification to each listed user ID.
     */
    private java.util.List<Long> mentionedUserIds;
}