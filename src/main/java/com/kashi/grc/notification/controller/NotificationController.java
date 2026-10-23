package com.kashi.grc.notification.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.dto.PaginatedResponse;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.repository.DbRepository;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.notification.domain.Notification;
import com.kashi.grc.notification.dto.response.NotificationResponse;
import com.kashi.grc.notification.repository.NotificationRepository;
import com.kashi.grc.notification.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/notifications")
@Tag(name = "Notifications", description = "User notification management")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationRepository notificationRepository;
    private final DbRepository           dbRepository;
    private final UtilityService         utilityService;
    private final NotificationService    notificationService;

    // 12.2 Get Notifications (paginated, filterable)
    @GetMapping
    @Operation(summary = "Get notifications for the current user — paginated, filterable")
    public ResponseEntity<ApiResponse<PaginatedResponse<NotificationResponse>>> getNotifications(
            @RequestParam(required = false) Boolean read,
            @RequestParam(required = false) String type,
            @RequestParam Map<String, String> allParams) {
        Long userId = utilityService.getLoggedInDataContext().getId();

        return ResponseEntity.ok(ApiResponse.success(dbRepository.findAll(
                Notification.class,
                utilityService.getpageDetails(allParams),
                (cb, root) -> {
                    List<jakarta.persistence.criteria.Predicate> preds = new ArrayList<>();
                    preds.add(cb.equal(root.get("userId"), userId));
                    if (type != null)
                        preds.add(cb.equal(root.get("type"), type));
                    if (read != null)
                        preds.add(Boolean.TRUE.equals(read)
                                ? cb.isNotNull(root.get("readAt"))
                                : cb.isNull(root.get("readAt")));
                    return preds;
                },
                (cb, root) -> Map.of(
                        "type",       root.get("type"),
                        "entitytype", root.get("entityType"),
                        "message",    root.get("message")
                ),
                n -> NotificationResponse.builder()
                        .notificationId(n.getId()).type(n.getType()).message(n.getMessage())
                        .entityType(n.getEntityType()).entityId(n.getEntityId())
                        .actionUrl(n.getActionUrl())
                        .sentAt(n.getSentAt()).readAt(n.getReadAt())
                        .build()
        )));
    }

    // 12.3 Mark Notification as Read
    @PutMapping("/{notificationId}/read")
    @Operation(summary = "Mark a notification as read")
    public ResponseEntity<ApiResponse<NotificationResponse>> markAsRead(
            @PathVariable Long notificationId) {
        Long userId = utilityService.getLoggedInDataContext().getId();
        Notification n = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification", notificationId));
        if (!n.getUserId().equals(userId))
            throw new BusinessException("UNAUTHORIZED", "Notification does not belong to this user");
        n.setReadAt(LocalDateTime.now());
        notificationRepository.save(n);
        return ResponseEntity.ok(ApiResponse.success(
                NotificationResponse.builder()
                        .notificationId(n.getId()).type(n.getType()).message(n.getMessage())
                        .entityType(n.getEntityType()).entityId(n.getEntityId())
                        .actionUrl(n.getActionUrl())
                        .sentAt(n.getSentAt()).readAt(n.getReadAt())
                        .build()));
    }

    // ── Counts and bulk reads ─────────────────────────────────────────────────
    // One count for the bell, the Notifications page and the sidebar badge.
    // They used to count three different things: unread items on the first
    // page of the unread list, unread items on the first page of ALL
    // notifications, and whatever the nav row's endpoint returned.

    @GetMapping("/unread-count")
    @Operation(summary = "How many unread notifications the current user has (all of them, not one page)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> unreadCount() {
        Long userId = utilityService.getLoggedInDataContext().getId();
        return ResponseEntity.ok(ApiResponse.success(Map.of("count", notificationService.unreadCount(userId))));
    }

    @PutMapping("/read-all")
    @Operation(summary = "Mark every unread notification of the current user as read")
    public ResponseEntity<ApiResponse<Map<String, Object>>> readAll() {
        Long userId = utilityService.getLoggedInDataContext().getId();
        return ResponseEntity.ok(ApiResponse.success(Map.of("updated", notificationService.markAllRead(userId))));
    }

    /** The app reports the page on screen; notifications about it are read. Body: { path, query }. */
    @PostMapping("/read-visited")
    @Operation(summary = "Mark read the current user's notifications about the page they are looking at")
    public ResponseEntity<ApiResponse<Map<String, Object>>> readVisited(@RequestBody Map<String, String> body) {
        Long userId = utilityService.getLoggedInDataContext().getId();
        String path = body == null ? null : body.get("path");
        String query = body == null ? null : body.get("query");
        return ResponseEntity.ok(ApiResponse.success(Map.of("updated", notificationService.markVisited(userId, path, query))));
    }
}