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
    private LocalDateTime sentAt;
    private LocalDateTime readAt;
}
