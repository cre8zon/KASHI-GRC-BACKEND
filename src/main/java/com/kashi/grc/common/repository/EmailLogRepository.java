package com.kashi.grc.common.repository;

import com.kashi.grc.common.domain.EmailLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EmailLogRepository extends JpaRepository<EmailLog, Long> {

    Optional<EmailLog> findByEventId(String eventId);

    boolean existsByEventIdAndStatus(String eventId, EmailLog.Status status);
}