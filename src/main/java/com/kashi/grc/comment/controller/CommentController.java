package com.kashi.grc.comment.controller;

import com.kashi.grc.comment.domain.EntityComment;
import com.kashi.grc.comment.dto.CommentRequest;
import com.kashi.grc.comment.dto.CommentResponse;
import com.kashi.grc.comment.service.CommentService;
import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.usermanagement.domain.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@Tag(name = "Comments", description = "Unified comment system for tasks, assessments, questions")
@RequiredArgsConstructor
public class CommentController {

    private final CommentService  commentService;
    private final UtilityService  utilityService;

    /**
     * POST /v1/comments
     * Add a comment to any entity (task, assessment, question).
     */
    @PostMapping("/v1/comments")
    @Operation(summary = "Add a comment to a task, assessment, or question")
    public ResponseEntity<ApiResponse<CommentResponse>> addComment(
            @Valid @RequestBody CommentRequest req) {

        User user     = utilityService.getLoggedInDataContext();
        Long userId   = user.getId();
        Long tenantId = user.getTenantId();

        // Enforce visibility rules — vendor responders cannot create INTERNAL or CISO_ONLY
        String userSide = resolveUserSide(user);
        String userRole = resolveUserRole(user);

        if (req.getVisibility() == EntityComment.Visibility.INTERNAL
                && !"ORGANIZATION".equals(userSide)) {
            req.setVisibility(EntityComment.Visibility.ALL); // downgrade silently
        }
        if (req.getVisibility() == EntityComment.Visibility.CISO_ONLY
                && !"ORGANIZATION".equals(userSide)
                && !"VENDOR_CISO".equals(userRole)
                && !"VENDOR_VRM".equals(userRole)) {
            req.setVisibility(EntityComment.Visibility.ALL);
        }

        CommentResponse response = commentService.addComment(req, userId, tenantId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    /**
     * GET /v1/comments?entityType=TASK&entityId=123
     * Get all visible comments for an entity.
     */
    @GetMapping("/v1/comments")
    @Operation(summary = "Get comments for a task, assessment, or question")
    public ResponseEntity<ApiResponse<List<CommentResponse>>> getComments(
            @RequestParam EntityComment.EntityType entityType,
            @RequestParam Long entityId) {

        User user = utilityService.getLoggedInDataContext();
        String userSide = resolveUserSide(user);
        String userRole = resolveUserRole(user);

        List<CommentResponse> comments = commentService.getComments(
                entityType, entityId, userSide, userRole);
        return ResponseEntity.ok(ApiResponse.success(comments));
    }

    /**
     * GET /v1/comments/question/:questionInstanceId
     * Get all visible comments for a specific question instance.
     * Used by fill/review pages to show per-question activity.
     */
    @GetMapping("/v1/comments/question/{questionInstanceId}")
    @Operation(summary = "Get comments for a specific question instance")
    public ResponseEntity<ApiResponse<List<CommentResponse>>> getQuestionComments(
            @PathVariable Long questionInstanceId) {

        User user = utilityService.getLoggedInDataContext();
        String userSide = resolveUserSide(user);
        String userRole = resolveUserRole(user);

        List<CommentResponse> comments = commentService.getQuestionComments(
                questionInstanceId, userSide, userRole);
        return ResponseEntity.ok(ApiResponse.success(comments));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String resolveUserSide(User user) {
        return user.getRoles().stream()
                .map(r -> r.getSide() != null ? r.getSide().name() : "")
                .filter(s -> !s.isEmpty())
                .findFirst().orElse("VENDOR");
    }

    private String resolveUserRole(User user) {
        return user.getRoles().stream()
                .map(r -> r.getName() != null ? r.getName() : "")
                .filter(s -> !s.isEmpty())
                .findFirst().orElse("");
    }
}