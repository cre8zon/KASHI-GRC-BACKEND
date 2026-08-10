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
import lombok.Data;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class ActionItemService {

    private final ActionItemRepository          actionItemRepository;
    private final ActionItemBlueprintRepository blueprintRepository;
    private final UserRepository                userRepository;
    private final SimpMessagingTemplate         messagingTemplate;
    private final NotificationService           notificationService;

    // ── Create ────────────────────────────────────────────────────────────────

    @Transactional
    public ActionItemResponse create(ActionItemRequest req, Long createdBy, Long tenantId) {
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
                // NEW: parent context
                .parentEntityType(req.getParentEntityType())
                .parentEntityId(req.getParentEntityId())
                .title(req.getTitle())
                .description(req.getDescription())
                .resolutionReservedFor(req.getResolutionReservedFor())
                .resolutionRole(blueprint != null && req.getResolutionRole() == null
                        ? blueprint.getResolutionRole() : req.getResolutionRole())
                .priority(blueprint != null && req.getPriority() == null
                        ? blueprint.getDefaultPriority() : (req.getPriority() != null
                                                            ? req.getPriority() : ActionItem.Priority.MEDIUM))
                .dueAt(req.getDueAt() != null ? LocalDateTime.parse(req.getDueAt()) : null)
                .navContext(req.getNavContext())
                .vendorId(req.getVendorId())
                // NEW: item UI rendering
                .itemScreenKey(req.getItemScreenKey())
                .itemUiJson(req.getItemUiJson())
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
     * Convenience method called by CommentService for REVISION_REQUEST comments.
     * Unchanged — idempotency guard preserved.
     */
    @Transactional
    public ActionItemResponse createFromComment(EntityComment comment,
                                                Long assignedTo,
                                                String resolutionRole,
                                                String navContextJson,
                                                Long tenantId,
                                                Long vendorId) {
        if (actionItemRepository.existsOpenForSource(ActionItem.SourceType.COMMENT, comment.getId())) {
            log.debug("[ACTION-ITEM] Skipping duplicate for comment={}", comment.getId());
            return null;
        }

        ActionItemRequest req = new ActionItemRequest();
        req.setSourceType(ActionItem.SourceType.COMMENT);
        req.setSourceId(comment.getId());
        req.setEntityType(ActionItem.EntityType.QUESTION_RESPONSE);
        req.setEntityId(comment.getEntityId());
        req.setAssignedTo(assignedTo);
        req.setVendorId(vendorId);
        req.setResolutionReservedFor(comment.getCreatedBy());
        req.setResolutionRole(resolutionRole);
        req.setTitle("Revision requested: " + truncate(comment.getCommentText(), 80));
        req.setDescription(comment.getCommentText());
        req.setPriority(ActionItem.Priority.MEDIUM);
        req.setNavContext(navContextJson);

        return create(req, comment.getCreatedBy(), tenantId);
    }

    // ── Get by ID ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ActionItemResponse getById(Long id, Long callerId, List<String> callerRoles, Long tenantId) {
        ActionItem item = actionItemRepository.findById(id)
                .filter(a -> a.getTenantId().equals(tenantId))
                .orElseThrow(() -> new ResourceNotFoundException("ActionItem", id));
        return toResponse(item, callerId, callerRoles);
    }

    // ── Update ────────────────────────────────────────────────────────────────

    @Transactional
    public ActionItemResponse update(Long id, UpdateRequest req,
                                     Long callerId, List<String> callerRoles, Long tenantId) {
        ActionItem item = actionItemRepository.findById(id)
                .filter(a -> a.getTenantId().equals(tenantId))
                .orElseThrow(() -> new ResourceNotFoundException("ActionItem", id));

        boolean isCreator = item.getCreatedBy().equals(callerId);
        boolean isAdmin   = hasRole(callerRoles, "ORG_ADMIN", "SYSTEM_ADMIN");
        if (!isCreator && !isAdmin) {
            throw new ForbiddenException("Only the creator or admin can update this action item");
        }

        if (req.getTitle()             != null) item.setTitle(req.getTitle());
        if (req.getDescription()       != null) item.setDescription(req.getDescription());
        if (req.getDueAt()             != null) item.setDueAt(LocalDateTime.parse(req.getDueAt()));
        if (req.getPriority()          != null) item.setPriority(req.getPriority());
        if (req.getAssignedTo()        != null) item.setAssignedTo(req.getAssignedTo());
        if (req.getAssignedGroupRole() != null) item.setAssignedGroupRole(req.getAssignedGroupRole());
        if (req.getNavContext()        != null) item.setNavContext(req.getNavContext());
        if (req.getItemScreenKey()     != null) item.setItemScreenKey(req.getItemScreenKey());
        if (req.getItemUiJson()        != null) item.setItemUiJson(req.getItemUiJson());

        actionItemRepository.save(item);
        return toResponse(item, callerId, callerRoles);
    }

    // ── Status update ─────────────────────────────────────────────────────────

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
        }

        log.info("[ACTION-ITEM] Status update id={} → {} by userId={}", id, newStatus, userId);
        actionItemRepository.save(item);

        ActionItemResponse response = toResponse(item, userId, userRoles);

        if (newStatus == ActionItem.Status.RESOLVED) {
            String actorName = resolveName(userId);
            pushToUser(item.getCreatedBy(), "ACTION_ITEM_RESOLVED", response);
            if (item.getAssignedTo() != null && !item.getAssignedTo().equals(userId)) {
                pushToUser(item.getAssignedTo(), "ACTION_ITEM_RESOLVED", response);
            }
            notificationService.send(item.getCreatedBy(), "ACTION_ITEM_RESOLVED",
                    actorName + " resolved: " + truncate(item.getTitle(), 80),
                    "ACTION_ITEM", item.getId());
        } else if (newStatus == ActionItem.Status.DISMISSED) {
            String actorName = resolveName(userId);
            pushToUser(item.getCreatedBy(), "ACTION_ITEM_DISMISSED", response);
            notificationService.send(item.getCreatedBy(), "ACTION_ITEM_DISMISSED",
                    actorName + " dismissed: " + truncate(item.getTitle(), 80),
                    "ACTION_ITEM", item.getId());
        } else if (newStatus == ActionItem.Status.PENDING_REVIEW) {
            pushToUser(item.getResolutionReservedFor(), "ACTION_ITEM_PENDING_REVIEW", response);
        }

        return response;
    }

    // ── Dismiss (soft delete) ─────────────────────────────────────────────────

    @Transactional
    public void dismiss(Long id, Long callerId, List<String> callerRoles, Long tenantId) {
        ActionItem item = actionItemRepository.findById(id)
                .filter(a -> a.getTenantId().equals(tenantId))
                .orElseThrow(() -> new ResourceNotFoundException("ActionItem", id));

        boolean isCreator = item.getCreatedBy().equals(callerId);
        boolean isAdmin   = hasRole(callerRoles, "ORG_ADMIN", "SYSTEM_ADMIN");
        if (!isCreator && !isAdmin) {
            throw new ForbiddenException("Only the creator or admin can delete this action item");
        }

        item.setStatus(ActionItem.Status.DISMISSED);
        actionItemRepository.save(item);
        log.info("[ACTION-ITEM] Dismissed id={} by userId={}", id, callerId);
    }

    // ── My open items ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ActionItemResponse> getMyOpenItems(Long userId, List<String> userRoles,
                                                   Long tenantId, Long userVendorId) {
        Specification<ActionItem> assigneeSpec =
                ActionItemSpecification.forTenant(tenantId)
                        .and(ActionItemSpecification.assignedToUserOrRole(userId, userRoles, userVendorId))
                        .and(ActionItemSpecification.open());

        Specification<ActionItem> reviewerSpec =
                ActionItemSpecification.forTenant(tenantId)
                        .and(ActionItemSpecification.resolvableBy(userId, userRoles))
                        .and(ActionItemSpecification.withStatus(ActionItem.Status.PENDING_REVIEW));

        java.util.Map<Long, ActionItem> combined = new java.util.LinkedHashMap<>();
        actionItemRepository.findAll(assigneeSpec).forEach(a -> combined.put(a.getId(), a));
        actionItemRepository.findAll(reviewerSpec).forEach(a -> combined.putIfAbsent(a.getId(), a));

        return combined.values().stream()
                .map(a -> toResponse(a, userId, userRoles))
                .toList();
    }

    // ── For entity ────────────────────────────────────────────────────────────

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

    /**
     * Bulk variant of getForEntity — one query for many entity ids.
     *
     * The assessment pages render a per-question action-item banner; fetching them
     * one id at a time meant ~2 HTTP requests per question (~180 on a 90-question
     * assessment), all serialised behind the browser connection limit. Callers
     * group the result by entityId themselves.
     */
    @Transactional(readOnly = true)
    public List<ActionItemResponse> getForEntities(ActionItem.EntityType entityType,
                                                   java.util.Collection<Long> entityIds,
                                                   Long userId, List<String> userRoles,
                                                   Long tenantId) {
        if (entityIds == null || entityIds.isEmpty()) return List.of();

        Specification<ActionItem> spec =
                ActionItemSpecification.forTenant(tenantId)
                        .and(ActionItemSpecification.forEntities(entityType, entityIds));

        return actionItemRepository.findAll(spec).stream()
                .map(a -> toResponse(a, userId, userRoles))
                .toList();
    }

    // ── Count ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public long countOpenForUser(Long userId, Long tenantId) {
        long asAssignee = actionItemRepository.countOpenForUser(userId, tenantId);
        long asReviewer = actionItemRepository.findAll(
                ActionItemSpecification.forTenant(tenantId)
                        .and(ActionItemSpecification.resolvableBy(userId, java.util.List.of()))
                        .and(ActionItemSpecification.withStatus(ActionItem.Status.PENDING_REVIEW))
        ).size();
        return asAssignee + asReviewer;
    }

    // ── Resolve by comment ────────────────────────────────────────────────────

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

    // ── Private helpers ───────────────────────────────────────────────────────

    private void validateTransition(ActionItem item, ActionItem.Status newStatus,
                                    Long userId, List<String> userRoles) {
        switch (newStatus) {
            case IN_PROGRESS -> {
                boolean isAssignee    = userId.equals(item.getAssignedTo());
                boolean isGroupMember = item.getAssignedGroupRole() != null && userRoles != null
                        && userRoles.contains(item.getAssignedGroupRole());
                if (!isAssignee && !isGroupMember && !canResolve(item, userId, userRoles)) {
                    throw new ForbiddenException("Only the assignee or reviewer can change to In Progress");
                }
            }
            case PENDING_REVIEW -> {
                boolean isAssignee    = userId.equals(item.getAssignedTo());
                boolean isGroupMember = item.getAssignedGroupRole() != null && userRoles != null
                        && userRoles.contains(item.getAssignedGroupRole());
                if (!isAssignee && !isGroupMember) {
                    throw new ForbiddenException("Only the assignee can submit for review");
                }
            }
            case DISMISSED -> {
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
        if (item.getResolutionReservedFor() != null) return userId.equals(item.getResolutionReservedFor());
        if (item.getResolutionRole() != null && userRoles != null)
            return userRoles.stream().anyMatch(r -> r.equals(item.getResolutionRole()));
        return userId.equals(item.getAssignedTo());
    }

    private boolean hasRole(List<String> userRoles, String... required) {
        if (userRoles == null) return false;
        for (String r : required) if (userRoles.contains(r)) return true;
        return false;
    }

    private ActionItemResponse toResponse(ActionItem item, Long callerId, List<String> callerRoles) {
        return ActionItemResponse.builder()
                .id(item.getId())
                .blueprintId(item.getBlueprintId())
                .assignedTo(item.getAssignedTo())
                .assignedToName(resolveName(item.getAssignedTo()))
                .assignedGroupRole(item.getAssignedGroupRole())
                .createdBy(item.getCreatedBy())
                .createdByName(resolveName(item.getCreatedBy()))
                .vendorId(item.getVendorId())
                .sourceType(item.getSourceType())
                .sourceId(item.getSourceId())
                .entityType(item.getEntityType())
                .entityId(item.getEntityId())
                // NEW: parent context
                .parentEntityType(item.getParentEntityType())
                .parentEntityId(item.getParentEntityId())
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
                // NEW: item UI rendering
                .itemScreenKey(item.getItemScreenKey())
                .itemUiJson(item.getItemUiJson())
                .createdAt(item.getCreatedAt())
                .updatedAt(item.getUpdatedAt())
                .canResolve(canResolve(item, callerId, callerRoles))
                .isOverdue(item.getDueAt() != null && LocalDateTime.now().isAfter(item.getDueAt())
                        && item.getStatus() != ActionItem.Status.RESOLVED
                        && item.getStatus() != ActionItem.Status.DISMISSED)
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

    // ── Inner update request ──────────────────────────────────────────────────

    @Data
    public static class UpdateRequest {
        private String                  title;
        private String                  description;
        private String                  dueAt;
        private ActionItem.Priority     priority;
        private Long                    assignedTo;
        private String                  assignedGroupRole;
        private String                  navContext;
        private String                  itemScreenKey;
        private String                  itemUiJson;
    }
}