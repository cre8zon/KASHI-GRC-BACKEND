package com.kashi.grc.comment.service;

import com.kashi.grc.comment.domain.EntityComment;
import com.kashi.grc.comment.dto.CommentRequest;
import com.kashi.grc.comment.dto.CommentResponse;
import com.kashi.grc.comment.repository.EntityCommentRepository;
import com.kashi.grc.usermanagement.domain.User;
import com.kashi.grc.actionitem.service.ActionItemService;
import com.kashi.grc.assessment.repository.AssessmentQuestionInstanceRepository;
import com.kashi.grc.usermanagement.repository.UserRepository;
import com.kashi.grc.workflow.repository.TaskInstanceRepository;
import com.kashi.grc.workflow.enums.TaskStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CommentService {

    private final EntityCommentRepository   commentRepository;
    private final UserRepository            userRepository;
    private final SimpMessagingTemplate     messagingTemplate;
    private final ActionItemService          actionItemService;
    private final AssessmentQuestionInstanceRepository questionInstanceRepository;
    private final TaskInstanceRepository               taskInstanceRepository;

    /**
     * Add a comment and push to WebSocket subscribers.
     * Visibility is enforced at save time — what gets stored is what gets broadcast.
     */
    @Transactional
    public CommentResponse addComment(CommentRequest req, Long userId, Long tenantId) {
        EntityComment comment = EntityComment.builder()
                .tenantId(tenantId)
                .entityType(req.getEntityType())
                .entityId(req.getEntityId())
                .questionInstanceId(req.getQuestionInstanceId())
                .responseId(req.getResponseId())
                .commentText(req.getCommentText())
                .commentType(req.getCommentType() != null
                        ? req.getCommentType() : EntityComment.CommentType.COMMENT)
                .visibility(req.getVisibility() != null
                        ? req.getVisibility() : EntityComment.Visibility.ALL)
                .parentCommentId(req.getParentCommentId())
                .createdBy(userId)
                .build();
        commentRepository.save(comment);

        String createdByName = resolveUserName(userId);
        CommentResponse response = toResponse(comment, createdByName);

        // Push via WebSocket to the entity's topic room
        pushComment(comment, response);

        // ── Action item side effects ─────────────────────────────────────
        if (comment.getCommentType() == EntityComment.CommentType.REVISION_REQUEST
                && comment.getQuestionInstanceId() != null) {
            handleRevisionRequest(comment, tenantId);
        } else if (comment.getCommentType() == EntityComment.CommentType.RESOLVED
                && comment.getParentCommentId() != null) {
            // Resolve any open action items triggered by the parent comment
            actionItemService.resolveByComment(
                    comment.getParentCommentId(), userId,
                    List.of(), tenantId
            );
        }

        log.info("[COMMENT] {} | entity={}/{} | type={} | visibility={} | by={}",
                comment.getId(), comment.getEntityType(), comment.getEntityId(),
                comment.getCommentType(), comment.getVisibility(), userId);

        return response;
    }

    /**
     * Get comments for an entity, filtered by what this user's role can see.
     * userSide: 'ORGANIZATION' or 'VENDOR'
     * userRole: e.g. 'VENDOR_CISO', 'VENDOR_RESPONDER', 'ORG_ADMIN'
     */
    @Transactional(readOnly = true)
    public List<CommentResponse> getComments(
            EntityComment.EntityType entityType, Long entityId,
            String userSide, String userRole) {

        List<EntityComment.Visibility> allowed = resolveAllowedVisibilities(userSide, userRole);

        return commentRepository.findVisible(entityType, entityId, allowed)
                .stream()
                .map(c -> toResponse(c, resolveUserName(c.getCreatedBy())))
                .toList();
    }

    /** Get comments for a question instance (used on fill/review pages) */
    @Transactional(readOnly = true)
    public List<CommentResponse> getQuestionComments(
            Long questionInstanceId, String userSide, String userRole) {

        List<EntityComment.Visibility> allowed = resolveAllowedVisibilities(userSide, userRole);

        return commentRepository.findByQuestionInstanceIdOrderByCreatedAtAsc(questionInstanceId)
                .stream()
                .filter(c -> allowed.contains(c.getVisibility()))
                .map(c -> toResponse(c, resolveUserName(c.getCreatedBy())))
                .toList();
    }

    /** System-generated comment (audit trail) — always visibility=ALL */
    @Transactional
    public void addSystemComment(EntityComment.EntityType entityType, Long entityId,
                                 Long tenantId, String text) {
        EntityComment comment = EntityComment.builder()
                .tenantId(tenantId)
                .entityType(entityType)
                .entityId(entityId)
                .commentText(text)
                .commentType(EntityComment.CommentType.SYSTEM)
                .visibility(EntityComment.Visibility.ALL)
                .createdBy(0L) // system user
                .build();
        commentRepository.save(comment);
        pushComment(comment, toResponse(comment, "System"));
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private List<EntityComment.Visibility> resolveAllowedVisibilities(
            String userSide, String userRole) {
        if ("ORGANIZATION".equals(userSide)) {
            // Org side sees everything: ALL + INTERNAL + CISO_ONLY
            return List.of(EntityComment.Visibility.ALL,
                    EntityComment.Visibility.INTERNAL,
                    EntityComment.Visibility.CISO_ONLY);
        }
        // Vendor CISO sees ALL + CISO_ONLY (not INTERNAL)
        if ("VENDOR_CISO".equals(userRole) || "VENDOR_VRM".equals(userRole)) {
            return List.of(EntityComment.Visibility.ALL,
                    EntityComment.Visibility.CISO_ONLY);
        }
        // Vendor responders/contributors see ALL only
        return List.of(EntityComment.Visibility.ALL);
    }

    private void pushComment(EntityComment comment, CommentResponse response) {
        String topic = "/topic/comments/"
                + comment.getEntityType().name().toLowerCase()
                + "/" + comment.getEntityId();
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type",       "COMMENT_ADDED");
            payload.put("comment",    response);
            payload.put("ts",         System.currentTimeMillis());
            messagingTemplate.convertAndSend(topic, payload);
            log.debug("[WS] Comment pushed to {}", topic);
        } catch (Exception e) {
            log.warn("[WS] Failed to push comment to {}: {}", topic, e.getMessage());
        }
    }

    private CommentResponse toResponse(EntityComment c, String createdByName) {
        return CommentResponse.builder()
                .id(c.getId())
                .entityType(c.getEntityType())
                .entityId(c.getEntityId())
                .questionInstanceId(c.getQuestionInstanceId())
                .responseId(c.getResponseId())
                .commentText(c.getCommentText())
                .commentType(c.getCommentType())
                .visibility(c.getVisibility())
                .parentCommentId(c.getParentCommentId())
                .createdBy(c.getCreatedBy())
                .createdByName(createdByName)
                .createdAt(c.getCreatedAt())
                .isInternal(c.getVisibility() == EntityComment.Visibility.INTERNAL)
                .isCisoOnly(c.getVisibility() == EntityComment.Visibility.CISO_ONLY)
                .build();
    }

    /**
     * When a REVISION_REQUEST comment is saved, create an action item for
     * the contributor assigned to this question.
     * Only created when the question has a contributor (assignedUserId != null
     * and assignedUserId != commentCreatedBy).
     */
    private void handleRevisionRequest(EntityComment comment, Long tenantId) {
        try {
            questionInstanceRepository.findById(comment.getQuestionInstanceId()).ifPresent(qi -> {
                Long contributorId = qi.getAssignedUserId();
                if (contributorId == null || contributorId.equals(comment.getCreatedBy())) {
                    log.debug("[ACTION-ITEM] Skipping revision action item — no contributor or self-request");
                    return;
                }
                // Build nav_context for deep-link to fill page.
                // Access is granted by the revision bypass in assertUserHasActiveTask —
                // no taskId needed in the URL when contributor's task is already APPROVED.
                // Try to find an active task (PENDING/IN_PROGRESS) for a cleaner URL;
                // fall back to route without taskId — revision bypass handles access.
                Long assessmentId = comment.getEntityId();
                List<com.kashi.grc.workflow.domain.TaskInstance> activeTasks =
                        new java.util.ArrayList<>();
                activeTasks.addAll(taskInstanceRepository
                        .findByAssignedUserIdAndStatus(contributorId, TaskStatus.PENDING));
                activeTasks.addAll(taskInstanceRepository
                        .findByAssignedUserIdAndStatus(contributorId, TaskStatus.IN_PROGRESS));
                Long taskId = activeTasks.stream()
                        .map(t -> t.getId()).findFirst().orElse(null);
                // navContext routing contract:
                //   assigneeRoute  = where the contributor goes to do the work
                //   reviewerRoute  = where the responder goes to review & resolve
                //
                // This is set HERE by CommentService because it knows the module context
                // (TPRM vendor assessment fill/review pages). Future modules set their
                // own routes when creating action items — ActionItemsPage never hardcodes.
                String assigneeRoute = taskId != null
                        ? String.format("/vendor/assessments/%d/fill?taskId=%d&openWork=1", assessmentId, taskId)
                        : String.format("/vendor/assessments/%d/fill?openWork=1", assessmentId);
                // Reviewer route: responder-review page.
                // We don't look up the responder's taskId here — the page resolves it
                // itself from the inbox, so a plain route is sufficient.
                String reviewerRoute = String.format(
                        "/vendor/assessments/%d/responder-review", assessmentId);
                String navCtx = String.format(
                        "{\"assigneeRoute\":\"%s\",\"reviewerRoute\":\"%s\"" +
                                ",\"questionInstanceId\":%d" +
                                ",\"sectionInstanceId\":%s,\"assessmentId\":%d}",
                        assigneeRoute,
                        reviewerRoute,
                        comment.getQuestionInstanceId(),
                        qi.getSectionInstanceId() != null ? qi.getSectionInstanceId() : "null",
                        assessmentId
                );
                actionItemService.createFromComment(
                        comment, contributorId, "VENDOR_RESPONDER", navCtx, tenantId
                );
            });
        } catch (Exception e) {
            log.warn("[ACTION-ITEM] Failed to create action item for revision request: {}", e.getMessage());
        }
    }

    private String resolveUserName(Long userId) {
        if (userId == null || userId == 0L) return "System";
        return userRepository.findById(userId).map(u -> {
            String fn = u.getFirstName() != null ? u.getFirstName() : "";
            String ln = u.getLastName()  != null ? u.getLastName()  : "";
            String full = (fn + " " + ln).trim();
            return full.isEmpty() ? u.getEmail() : full;
        }).orElse("Unknown");
    }
}