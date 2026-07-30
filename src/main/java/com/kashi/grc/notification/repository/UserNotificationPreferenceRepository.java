package com.kashi.grc.notification.repository;

import com.kashi.grc.notification.domain.UserNotificationPreference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserNotificationPreferenceRepository
        extends JpaRepository<UserNotificationPreference, Long> {

    /** All rows for the preference-center screen of one user. */
    List<UserNotificationPreference> findByUserId(Long userId);

    /** Batch load for the consumer — one query per fanout, not per user. */
    List<UserNotificationPreference> findByUserIdIn(Collection<Long> userIds);

    /** Upsert target for the controller. */
    Optional<UserNotificationPreference> findByUserIdAndEventKey(Long userId, String eventKey);
}
