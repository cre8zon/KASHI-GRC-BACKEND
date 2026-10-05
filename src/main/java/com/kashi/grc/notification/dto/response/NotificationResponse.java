package com.kashi.grc.notification.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NotificationResponse {
    private Long notificationId;
    private String type;
    private String message;
    private String entityType;
    private Long entityId;
    /**
     * Where this notification opens, written by the module that owns the entity
     * (see NotificationRouteContributor). Null on rows raised by a module with
     * no contributor, and on every row created before this existed — the client
     * falls back to its own entityType mapping in both cases, which is why this
     * can be adopted one module at a time.
     *
     * The column has existed since the table was created; nothing wrote it and
     * nothing read it, so this DTO never carried it and the client never had
     * the chance to prefer it.
     */
    private String actionUrl;
    private LocalDateTime sentAt;
    private LocalDateTime readAt;
}