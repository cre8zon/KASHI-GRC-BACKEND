package com.kashi.grc.notification.repository;

import java.time.LocalDateTime;
import java.util.Collection;

/**
 * Bulk "mark read" updates — one statement each, so marking hundreds of
 * notifications read never loads them. Criteria API, see the Impl.
 */
public interface NotificationRepositoryCustom {

    /** Every unread notification of this user. */
    int markAllRead(Long userId, LocalDateTime at);

    /** These notifications, if they belong to this user and are unread. */
    int markRead(Long userId, Collection<Long> ids, LocalDateTime at);

    /** This user's unread notifications about one entity (e.g. one chat conversation). */
    int markReadForEntity(Long userId, String entityType, Long entityId, LocalDateTime at);
}