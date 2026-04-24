package com.kashi.grc.comment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kashi.grc.comment.domain.EntityComment;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CommentResponse {
    private Long                       id;
    private EntityComment.EntityType   entityType;
    private Long                       entityId;
    private Long                       questionInstanceId;
    private Long                       responseId;
    private String                     commentText;
    private EntityComment.CommentType  commentType;
    private EntityComment.Visibility   visibility;
    private Long                       parentCommentId;
    private Long                       createdBy;
    private String                     createdByName;
    private LocalDateTime              createdAt;
    private boolean                    isInternal;   // true when visibility=INTERNAL
    private boolean                    isCisoOnly;   // true when visibility=CISO_ONLY
}