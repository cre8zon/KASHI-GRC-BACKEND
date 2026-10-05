package com.kashi.grc.comment.controller;

import com.kashi.grc.comment.domain.EntityComment;
import com.kashi.grc.comment.dto.CommentRequest;
import com.kashi.grc.comment.dto.CommentResponse;
import com.kashi.grc.comment.service.CommentService;
import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.usermanagement.domain.RoleSide;
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

        // Enforce visibility rules — strict channel isolation between vendor and org sides
        String userSide = resolveUserSide(user);
        String userRole = resolveUserRole(user);

        // ── REFUSE, DO NOT DOWNGRADE ─────────────────────────────────────
        // These three checks previously reset a disallowed visibility to ALL
        // and carried on. The comment was posted, the API returned 201, and the
        // author had no way to know their "private" note had gone to the shared
        // thread — where the other side had already read it. There is no undo
        // for that.
        //
        // Refusing is the only outcome where nobody is misled. A client that
        // offers a channel the caller may not use has a bug worth surfacing;
        // silently rewriting the request hides it and costs the user a
        // disclosure instead.
        if (req.getVisibility() == EntityComment.Visibility.VENDOR_INTERNAL
                && "ORGANIZATION".equals(userSide)) {
            throw new BusinessException("ACCESS_DENIED",
                    "That is the vendor's private thread. Post to the shared thread "
                            + "or your own organisation's.",
                    HttpStatus.FORBIDDEN);
        }

        // INTERNAL: vendor side cannot post to org's private channel
        if (req.getVisibility() == EntityComment.Visibility.INTERNAL
                && !"ORGANIZATION".equals(userSide)) {
            throw new BusinessException("ACCESS_DENIED",
                    "That is the assessing organisation's private thread. Post to the "
                            + "shared thread or your own team's.",
                    HttpStatus.FORBIDDEN);
        }

        // CISO_ONLY: only vendor CISO/VRM and org roles
        if (req.getVisibility() == EntityComment.Visibility.CISO_ONLY
                && !"ORGANIZATION".equals(userSide)
                && !"VENDOR_CISO".equals(userRole)
                && !"VENDOR_VRM".equals(userRole)) {
            throw new BusinessException("ACCESS_DENIED",
                    "The CISO channel is open to the assessing organisation and the "
                            + "vendor's CISO or VRM only.",
                    HttpStatus.FORBIDDEN);
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

    /**
     * Which side the caller is on.
     *
     * ── WHY THIS IS NOT findFirst() ANY MORE ─────────────────────────────
     * User.roles is a HashSet. findFirst() on a stream from a HashSet returns
     * an ARBITRARY element — iteration order is unspecified and can differ
     * between runs or as the set changes. So a user holding an ORGANIZATION
     * role alongside any other side-bearing role got a non-deterministic side.
     *
     * On the read path that is merely wrong. On the write path it is a leak:
     * an org user who resolved as VENDOR passed the VENDOR_INTERNAL check and
     * could post into the vendor's private thread — content the vendor treats
     * as its own, from the other party, invisible to that user on the next
     * request when the side happened to resolve the other way.
     *
     * Precedence, not iteration order. SYSTEM first because a platform
     * operator is neither party; ORGANIZATION before VENDOR because holding an
     * org role is the stronger claim and the one that must not be missed.
     *
     * The default stays VENDOR: a user with no side-bearing role sees the
     * narrower set, so a misclassification hides rather than discloses.
     */
    private String resolveUserSide(User user) {
        if (user == null || user.getRoles() == null) return "VENDOR";
        if (hasSide(user, RoleSide.SYSTEM))       return "SYSTEM";
        if (hasSide(user, RoleSide.ORGANIZATION)
         || hasSide(user, RoleSide.AUDITOR))      return "ORGANIZATION";
        return "VENDOR";
    }

    private boolean hasSide(User user, RoleSide side) {
        return user.getRoles().stream().anyMatch(r -> r.getSide() == side);
    }

    /**
     * Which role name the visibility rules should see.
     *
     * Same HashSet problem, opposite consequence. CommentService checks this
     * against exactly two names — VENDOR_CISO and VENDOR_VRM — so a CISO who
     * also holds another role could resolve to the other one and silently lose
     * the CISO channel. That hides a conversation rather than leaking one, but
     * it reads as "the CISO channel is empty for me", which nobody would report
     * as a permissions bug.
     *
     * So: return one of the two names when the user holds it, and otherwise
     * fall back to a stable pick rather than an arbitrary one.
     */
    private String resolveUserRole(User user) {
        if (user == null || user.getRoles() == null) return "";
        java.util.Set<String> names = user.getRoles().stream()
                .map(r -> r.getName() != null ? r.getName() : "")
                .filter(n -> !n.isEmpty())
                .collect(java.util.stream.Collectors.toSet());
        if (names.contains("VENDOR_CISO")) return "VENDOR_CISO";
        if (names.contains("VENDOR_VRM"))  return "VENDOR_VRM";
        return names.stream().sorted().findFirst().orElse("");
    }
}