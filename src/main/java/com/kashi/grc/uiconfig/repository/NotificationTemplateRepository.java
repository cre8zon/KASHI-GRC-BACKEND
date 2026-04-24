package com.kashi.grc.uiconfig.repository;

import com.kashi.grc.uiconfig.domain.NotificationTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, Long> {
    Optional<NotificationTemplate> findByEventKeyAndIsActiveTrue(String eventKey);
}
