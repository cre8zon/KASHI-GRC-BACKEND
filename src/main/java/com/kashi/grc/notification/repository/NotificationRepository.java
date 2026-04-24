package com.kashi.grc.notification.repository;

import com.kashi.grc.notification.domain.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    List<Notification> findByUserIdOrderBySentAtDesc(Long userId);
    long countByUserIdAndReadAtIsNull(Long userId);
    // Filtered list via CriteriaQueryHelper
}
