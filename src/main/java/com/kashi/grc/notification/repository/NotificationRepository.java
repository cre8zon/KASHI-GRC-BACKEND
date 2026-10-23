package com.kashi.grc.notification.repository;

import com.kashi.grc.notification.domain.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long>, NotificationRepositoryCustom {
    List<Notification> findByUserIdOrderBySentAtDesc(Long userId);
    long countByUserIdAndReadAtIsNull(Long userId);
    /** Unread, for matching against the page just visited (a user has tens, not thousands). */
    List<Notification> findByUserIdAndReadAtIsNull(Long userId);
    // Filtered list via CriteriaQueryHelper
}