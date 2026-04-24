package com.kashi.grc.notification.service;

import com.kashi.grc.notification.domain.Notification;
import com.kashi.grc.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;

    @Transactional
    public void send(Long userId, String type, String message, String entityType, Long entityId) {
        Notification n = Notification.builder()
                .userId(userId)
                .type(type)
                .message(message)
                .entityType(entityType)
                .entityId(entityId)
                .sentAt(LocalDateTime.now())
                .build();
        notificationRepository.save(n);
        log.debug("Notification sent to user {} — [{}] {}", userId, type, message);
    }

    @Transactional
    public void sendToUsers(List<Long> userIds, String type, String message, String entityType, Long entityId) {
        userIds.forEach(uid -> send(uid, type, message, entityType, entityId));
    }
}
