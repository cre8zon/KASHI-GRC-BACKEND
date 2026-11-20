package com.kashi.grc.notification.repository;

import com.kashi.grc.notification.domain.NotificationDispatchLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationDispatchLogRepository extends JpaRepository<NotificationDispatchLog, Long> {

    /**
     * Idempotency probe. DISPATCHED and SKIPPED are both terminal — a redelivered
     * event must not re-fan-out either way. FAILED is NOT terminal: the record is
     * being retried by the container, and the fanout should run again.
     */
    boolean existsByEventIdAndStatusIn(String eventId,
                                       java.util.Collection<NotificationDispatchLog.Status> statuses);

    /**
     * The row for an event, if any. event_id is unique, so every write for an
     * event updates this one row: a FAILED attempt followed by a successful
     * retry becomes DISPATCHED instead of colliding on uk_notif_dispatch_event_id.
     */
    java.util.Optional<NotificationDispatchLog> findByEventId(String eventId);
}