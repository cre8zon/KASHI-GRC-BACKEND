package com.kashi.grc.notification.repository;

import com.kashi.grc.notification.domain.NotificationEmailRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationEmailRuleRepository extends JpaRepository<NotificationEmailRule, Long> {

    /** Tenant-specific rules — take full precedence over global when non-empty. */
    List<NotificationEmailRule> findByEventKeyAndTenantIdAndIsActiveTrue(String eventKey, Long tenantId);

    /** Global platform rules (tenant_id IS NULL). */
    List<NotificationEmailRule> findByEventKeyAndTenantIdIsNullAndIsActiveTrue(String eventKey);

    /** Admin listing. */
    List<NotificationEmailRule> findByEventKeyOrderByTenantIdAsc(String eventKey);
}
