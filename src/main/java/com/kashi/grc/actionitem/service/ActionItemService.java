package com.kashi.grc.actionitem.service;

import com.kashi.grc.actionitem.domain.ActionItem;
import com.kashi.grc.actionitem.domain.ActionItemBlueprint;
import com.kashi.grc.actionitem.dto.ActionItemRequest;
import com.kashi.grc.actionitem.dto.ActionItemResponse;
import com.kashi.grc.actionitem.dto.ActionItemStatusUpdate;
import com.kashi.grc.actionitem.repository.ActionItemBlueprintRepository;
import com.kashi.grc.actionitem.repository.ActionItemRepository;
import com.kashi.grc.actionitem.specification.ActionItemSpecification;
import com.kashi.grc.comment.domain.EntityComment;
import com.kashi.grc.common.exception.ForbiddenException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.notification.service.NotificationService;
import com.kashi.grc.usermanagement.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ActionItemService {

    private final ActionItemRepository          actionItemRepository;
    private final ActionItemBlueprintRepository blueprintRepository;
    private final UserRepository                userRepository;
    private final SimpMessagingTemplate         messagingTemplate;
    private final NotificationService           notificationService;

    // ── Create ────────────────────────────────────────────────────────────

    @Transactional
    public ActionItemResponse create(ActionItemRequest req, Long createdBy, Long tenantId) {
        // Resolve blueprint if provided
        // Resolve blueprint — accept either blueprintId or blueprintCode
        if (req.getBlueprintId() == null && req.getBlueprintCode() != null) {
            blueprintRepository.findByBlueprintCode(req.getBlueprintCode())
                    .ifPresent(b -> req.setBlueprintId(b.getId()));
        }
        ActionItemBlueprint blueprint = req.getBlueprintId() != null
                ? blueprintRepository.findById(req.getBlueprintId()).orElse(null)
                : null;

        ActionItem item = ActionItem.builder()
                .tenantId(tenantId)
                .blueprintId(req.getBlueprintId())
                .assignedTo(req.getAssignedTo())
                .assignedGroupRole(req.getAssignedGroupRole())
                .createdBy(createdBy)
                .sourceType(req.getSourceType())
                .sourceId(req.getSourceId())
                .entityType(req.getEntityType())
                .entityId(req.getEntityId())
                .title(req.getTitle())
                .description(req.getDescription())
                .resolutionReservedFor(req.getResolutionReservedFor())
                .resolutionRole(blueprint != null && req.getResolutionRole() == null
                        ? blueprint.getResolutionRole() : req.getResolutionRole())
                .priority(blueprint != null && req.getPriority() == null
                        ? blueprint.getDefaultPriority() : (req.getPriority() != null
                                                            ? req.getPriority() : ActionItem.Priority.MEDIUM))
                .navContext(req.getNavContext())
                .status(ActionItem.Status.OPEN)
                .build();

        actionItemRepository.save(item);
        log.info("[ACTION-ITEM] Created id={} source={}/{} entity={}/{} assignedTo={}",
                item.getId(), item.getSourceType(), item.getSourceId(),
                item.getEntityType(), item.getEntityId(), item.getAssignedTo());

        ActionItemResponse response = toResponse(item, createdBy, List.of());
        pushToUser(item.getAssignedTo(), "ACTION_ITEM_CREATED", response);
        return response;
    }

    /**
     * Convenience method called by CommentService when a REVISION_REQUEST comment is saved.
     * Avoids duplicate action items for the same source comment.
     */
    @Transactional
    public ActionItemResponse createFromComment(EntityComment comment,
                                                Long assignedTo,
                                                String resolutionRole,
                                                String navContextJson,
                                                Long tenantId) {
        // Idempotency — don't create duplicate for same comment
        if (actionItemRepository.existsOpenForSource(ActionItem.SourceType.COMMENT, comment.getId())) {
            log.debug("[ACTION-ITEM] Skipping duplicate for comment={}", comment.getId());
            return null;
        }

        ActionItemRequest req = new ActionItemRequest();
        req.setSourceType(ActionItem.SourceType.COMMENT);
        req.setSourceId(comment.getId());
        req.setEntityType(ActionItem.EntityType.QUESTION_RESPONSE);
        req.setEntityId(comment.getEntityId()); // questionInstanceId
        req.setAssignedTo(assignedTo);
        req.setResolutionReservedFor(comment.getCreatedBy()); // only the requester can resolve
        req.setResolutionRole(resolutionRole);
        req.setTitle("Revision requested: " + truncate(comment.getCommentText(), 80));
        req.setDescription(comment.getCommentText());
        req.setPriority(ActionItem.Priority.MEDIUM);
        req.setNavContext(navContextJson);

        return create(req, comment.getCreatedBy(), tenantId);
    }

    // ── Status update ─────────────────────────────────────────────────────

    @Transactional
    public ActionItemResponse updateStatus(Long id, ActionItemStatusUpdate update,
                                           Long userId, List<String> userRoles,
                                           Long tenantId) {
        ActionItem item = actionItemRepository.findById(id)
                .filter(a -> a.getTenantId().equals(tenantId))
                .orElseThrow(() -> new ResourceNotFoundException("ActionItem", id));

        ActionItem.Status newStatus = update.getStatus();
        validateTransition(item, newStatus, userId, userRoles);

        item.setStatus(newStatus);
        if (newStatus == ActionItem.Status.RESOLVED) {
            item.setResolvedAt(LocalDateTime.now());
            item.setResolvedBy(userId);
            item.setResolutionNote(update.getResolutionNote());
        } else if (newStatus == ActionItem.Status.OPEN) {
            // Re-opening clears resolution fields
            item.setResolvedAt(null);
            item.setResolvedBy(null);
            item.setResolutionNote(null);
        }

        actionItemRepository.save(item);
        log.info("[ACTION-ITEM] Status update id={} → {} by userId={}", id, newStatus, userId);

        ActionItemResponse response = toResponse(item, userId, userRoles);
        // Push to assignee + resolver
        pushToUser(item.getAssignedTo(), "ACTION_ITEM_UPDATED", response);
        if (!userId.equals(item.getAssignedTo())) {
            pushToUser(userId, "ACTION_ITEM_UPDATED", response);
        }
        // Push to the item's own topic for entity-level listeners
        pushToTopic("/topic/action-items/" + id, "ACTION_ITEM_UPDATED", response);

        // ── Lifecycle notifications (scalable — covers all modules) ────────
        // RESOLVED → notify createdBy: "work you requested is done"
        // DISMISSED → notify createdBy: "your request was dismissed"
        // These two events cover the full lifecycle in one place.
        // No per-module notification code needed anywhere else.
        // Scalable lifecycle notifications — covers ALL modules
        String actorName = resolveName(userId);
        String entityType = item.getEntityType() != null
                ? item.getEntityType().name() : "ACTION_ITEM";

        if (newStatus == ActionItem.Status.PENDING_REVIEW) {
            // Assignee submitted work → notify reviewer "ready for your review"
            Long reviewerId = item.getResolutionReservedFor() != null
                    ? item.getResolutionReservedFor() : item.getCreatedBy();
            if (reviewerId != null && reviewerId != 0L && !reviewerId.equals(userId)) {
                notificationService.send(reviewerId, "ACTION_ITEM_PENDING_REVIEW",
                        actorName + " submitted for review: " + truncate(item.getTitle(), 80),
                        entityType, item.getId());
            }
        } else if (newStatus == ActionItem.Status.IN_PROGRESS
                && item.getStatus() == ActionItem.Status.PENDING_REVIEW) {
            // Reviewer pushed back → notify assignee + createdBy "rework needed"
            String reworkMsg = actorName + " sent back for rework: " + truncate(item.getTitle(), 80);
            if (item.getAssignedTo() != null && !item.getAssignedTo().equals(userId)) {
                notificationService.send(item.getAssignedTo(), "ACTION_ITEM_REWORK",
                        reworkMsg, entityType, item.getId());
            }
            // Also notify createdBy if different (they raised the remediation)
            if (item.getCreatedBy() != null && !item.getCreatedBy().equals(userId)
                    && !item.getCreatedBy().equals(item.getAssignedTo())) {
                notificationService.send(item.getCreatedBy(), "ACTION_ITEM_REWORK",
                        reworkMsg, entityType, item.getId());
            }
        } else if (newStatus == ActionItem.Status.RESOLVED) {
            // Reviewer accepted → notify createdBy "your request is done"
            if (item.getCreatedBy() != null && item.getCreatedBy() != 0L
                    && !item.getCreatedBy().equals(userId)) {
                notificationService.send(item.getCreatedBy(), "ACTION_ITEM_RESOLVED",
                        actorName + " resolved: " + truncate(item.getTitle(), 80),
                        entityType, item.getId());
            }
            // Also notify assignee if different from createdBy
            if (item.getAssignedTo() != null && !item.getAssignedTo().equals(userId)
                    && !item.getAssignedTo().equals(item.getCreatedBy())) {
                notificationService.send(item.getAssignedTo(), "ACTION_ITEM_RESOLVED",
                        "Resolved: " + truncate(item.getTitle(), 80),
                        entityType, item.getId());
            }
        } else if (newStatus == ActionItem.Status.DISMISSED) {
            // Dismissed → notify createdBy
            if (item.getCreatedBy() != null && item.getCreatedBy() != 0L
                    && !item.getCreatedBy().equals(userId)) {
                notificationService.send(item.getCreatedBy(), "ACTION_ITEM_DISMISSED",
                        actorName + " dismissed: " + truncate(item.getTitle(), 80),
                        entityType, item.getId());
            }
        } else if (newStatus == ActionItem.Status.OPEN
                && item.getStatus() == ActionItem.Status.RESOLVED) {
            // Re-opened → notify assignee "this issue recurred"
            if (item.getAssignedTo() != null && !item.getAssignedTo().equals(userId)) {
                notificationService.send(item.getAssignedTo(), "ACTION_ITEM_REOPENED",
                        actorName + " re-opened: " + truncate(item.getTitle(), 80),
                        entityType, item.getId());
            }
        }
        return response;
    }

    // ── Queries ───────────────────────────────────────────────────────────

    /**
     * My open action items — items I must act on, from two perspectives:
     *
     * 1. ASSIGNEE VIEW: items assigned to me (I do the work)
     *    → assignedTo = userId OR assignedGroupRole in userRoles
     *
     * 2. REVIEWER VIEW: items I must review/resolve (PENDING_REVIEW status)
     *    → resolutionReservedFor = userId OR resolutionRole in userRoles
     *    These are items where someone submitted work waiting for my approval.
     *
     * Combined: union of both, deduplicated by id.
     */
    @Transactional(readOnly = true)
    public List<ActionItemResponse> getMyOpenItems(Long userId, List<String> userRoles,
                                                   Long tenantId) {
        // Assignee view: items I need to work on
        Specification<ActionItem> assigneeSpec =
                ActionItemSpecification.forTenant(tenantId)
                        .and(ActionItemSpecification.assignedToUserOrRole(userId, userRoles))
                        .and(ActionItemSpecification.open());

        // Reviewer view: items awaiting my review/resolution (PENDING_REVIEW)
        Specification<ActionItem> reviewerSpec =
                ActionItemSpecification.forTenant(tenantId)
                        .and(ActionItemSpecification.resolvableBy(userId, userRoles))
                        .and(ActionItemSpecification.withStatus(
                                ActionItem.Status.PENDING_REVIEW));

        // Union deduplicated by id
        java.util.Map<Long, ActionItem> combined = new java.util.LinkedHashMap<>();
        actionItemRepository.findAll(assigneeSpec).forEach(a -> combined.put(a.getId(), a));
        actionItemRepository.findAll(reviewerSpec).forEach(a -> combined.putIfAbsent(a.getId(), a));

        return combined.values().stream()
                .map(a -> toResponse(a, userId, userRoles))
                .toList();
    }

    /** All action items for an entity — for oversight views (CISO, VRM, coordinator) */
    @Transactional(readOnly = true)
    public List<ActionItemResponse> getForEntity(ActionItem.EntityType entityType,
                                                 Long entityId, Long userId,
                                                 List<String> userRoles, Long tenantId) {
        Specification<ActionItem> spec =
                ActionItemSpecification.forTenant(tenantId)
                        .and(ActionItemSpecification.forEntity(entityType, entityId));

        return actionItemRepository.findAll(spec).stream()
                .map(a -> toResponse(a, userId, userRoles))
                .toList();
    }

    /** Count of open items — includes items user must resolve (PENDING_REVIEW) */
    @Transactional(readOnly = true)
    public long countOpenForUser(Long userId, Long tenantId) {
        long asAssignee = actionItemRepository.countOpenForUser(userId, tenantId);
        // Also count PENDING_REVIEW items where user is the resolver
        long asReviewer = actionItemRepository.findAll(
                ActionItemSpecification.forTenant(tenantId)
                        .and(ActionItemSpecification.resolvableBy(userId, java.util.List.of()))
                        .and(ActionItemSpecification.withStatus(ActionItem.Status.PENDING_REVIEW))
        ).size();
        // Use Set to avoid double-counting items where user is both assignee and resolver
        return asAssignee + asReviewer;
    }

    /**
     * Resolve action items linked to a comment (called when RESOLVED comment is added).
     * Finds all open action items sourced from the given comment and resolves them.
     */
    @Transactional
    public void resolveByComment(Long commentId, Long resolvedBy,
                                 List<String> userRoles, Long tenantId) {
        Specification<ActionItem> spec =
                ActionItemSpecification.forTenant(tenantId)
                        .and(ActionItemSpecification.forSource(ActionItem.SourceType.COMMENT, commentId))
                        .and(ActionItemSpecification.open());

        actionItemRepository.findAll(spec).forEach(item -> {
            try {
                ActionItemStatusUpdate upd = new ActionItemStatusUpdate();
                upd.setStatus(ActionItem.Status.RESOLVED);
                upd.setResolutionNote("Resolved via comment");
                updateStatus(item.getId(), upd, resolvedBy, userRoles, tenantId);
            } catch (ForbiddenException e) {
                log.warn("[ACTION-ITEM] Cannot resolve id={} — permission denied for user={}",
                        item.getId(), resolvedBy);
            }
        });
    }

    // ── Private helpers ───────────────────────────────────────────────────

    /**
     * State transition rules:
     *   OPEN → IN_PROGRESS   : assignedTo only
     *   OPEN → DISMISSED     : assignedTo or admin
     *   * → RESOLVED         : resolutionReservedFor (if set) OR user has resolutionRole
     *   RESOLVED → OPEN      : same as RESOLVED permission (re-open)
     */
    private void validateTransition(ActionItem item, ActionItem.Status newStatus,
                                    Long userId, List<String> userRoles) {
        switch (newStatus) {
            case IN_PROGRESS -> {
                // Assignee moves to IN_PROGRESS ("I'm working on it")
                // Reviewer can also push back to IN_PROGRESS ("not good enough, rework")
                // assignedGroupRole members (e.g. VENDOR_CISO) can also start working
                boolean isAssignee      = userId.equals(item.getAssignedTo());
                boolean isGroupMember   = item.getAssignedGroupRole() != null && userRoles != null
                        && userRoles.contains(item.getAssignedGroupRole());
                if (!isAssignee && !isGroupMember && !canResolve(item, userId, userRoles)) {
                    throw new ForbiddenException("Only the assignee or reviewer can change to In Progress");
                }
            }
            case PENDING_REVIEW -> {
                // Assignee or group role member submits for review ("I've done my part")
                boolean isAssignee    = userId.equals(item.getAssignedTo());
                boolean isGroupMember = item.getAssignedGroupRole() != null && userRoles != null
                        && userRoles.contains(item.getAssignedGroupRole());
                if (!isAssignee && !isGroupMember) {
                    throw new ForbiddenException("Only the assignee can submit for review");
                }
            }
            case DISMISSED -> {
                // Reviewer or admin can dismiss
                if (!canResolve(item, userId, userRoles)
                        && !hasRole(userRoles, "ORG_ADMIN", "SYSTEM_ADMIN")) {
                    throw new ForbiddenException("Only the reviewer or admin can dismiss");
                }
            }
            case RESOLVED, OPEN -> {
                if (!canResolve(item, userId, userRoles)) {
                    throw new ForbiddenException(
                            "You don't have permission to resolve/reopen this action item. " +
                                    "Required role: " + item.getResolutionRole());
                }
            }
        }
    }

    private boolean canResolve(ActionItem item, Long userId, List<String> userRoles) {
        // Specific user reservation takes precedence
        if (item.getResolutionReservedFor() != null) {
            return userId.equals(item.getResolutionReservedFor());
        }
        // Role-based resolution
        if (item.getResolutionRole() != null && userRoles != null) {
            return userRoles.stream().anyMatch(r -> r.equals(item.getResolutionRole()));
        }
        // No restriction — assignee can resolve
        return userId.equals(item.getAssignedTo());
    }

    private boolean hasRole(List<String> userRoles, String... required) {
        if (userRoles == null) return false;
        for (String r : required) {
            if (userRoles.contains(r)) return true;
        }
        return false;
    }

    private ActionItemResponse toResponse(ActionItem item, Long callerId,
                                          List<String> callerRoles) {
        return ActionItemResponse.builder()
                .id(item.getId())
                .blueprintId(item.getBlueprintId())
                .assignedTo(item.getAssignedTo())
                .assignedToName(resolveName(item.getAssignedTo()))
                .assignedGroupRole(item.getAssignedGroupRole())
                .createdBy(item.getCreatedBy())
                .createdByName(resolveName(item.getCreatedBy()))
                .sourceType(item.getSourceType())
                .sourceId(item.getSourceId())
                .entityType(item.getEntityType())
                .entityId(item.getEntityId())
                .title(item.getTitle())
                .description(item.getDescription())
                .status(item.getStatus())
                .priority(item.getPriority())
                .dueAt(item.getDueAt())
                .resolutionReservedFor(item.getResolutionReservedFor())
                .resolutionReservedForName(resolveName(item.getResolutionReservedFor()))
                .resolutionRole(item.getResolutionRole())
                .resolvedAt(item.getResolvedAt())
                .resolvedBy(item.getResolvedBy())
                .resolvedByName(resolveName(item.getResolvedBy()))
                .resolutionNote(item.getResolutionNote())
                .navContext(item.getNavContext())
                .createdAt(item.getCreatedAt())
                .updatedAt(item.getUpdatedAt())
                .canResolve(canResolve(item, callerId, callerRoles))
                .isOverdue(item.getDueAt() != null && LocalDateTime.now().isAfter(item.getDueAt())
                        && item.getStatus() != ActionItem.Status.RESOLVED
                        && item.getStatus() != ActionItem.Status.DISMISSED)
                // Remediation / clarification specific fields
                .remediationType(item.getRemediationType())
                .severity(item.getSeverity())
                .expectedEvidence(item.getExpectedEvidence())
                .acceptedRisk(Boolean.TRUE.equals(item.getAcceptedRisk()))
                .acceptedRiskBy(item.getAcceptedRiskBy())
                .acceptedRiskByName(resolveName(item.getAcceptedRiskBy()))
                .acceptedRiskNote(item.getAcceptedRiskNote())
                .acceptedRiskAt(item.getAcceptedRiskAt())
                .build();
    }

    private String resolveName(Long userId) {
        if (userId == null) return null;
        return userRepository.findById(userId).map(u -> {
            String fn = u.getFirstName() != null ? u.getFirstName() : "";
            String ln = u.getLastName()  != null ? u.getLastName()  : "";
            String full = (fn + " " + ln).trim();
            return full.isEmpty() ? u.getEmail() : full;
        }).orElse(null);
    }

    private void pushToUser(Long userId, String type, ActionItemResponse payload) {
        if (userId == null) return;
        pushToTopic("/topic/user/" + userId, type, payload);
    }

    private void pushToTopic(String topic, String type, Object payload) {
        try {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", type);
            msg.put("actionItem", payload);
            msg.put("ts", System.currentTimeMillis());
            messagingTemplate.convertAndSend(topic, msg);
        } catch (Exception e) {
            log.warn("[WS] Failed to push {} to {}: {}", type, topic, e.getMessage());
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}