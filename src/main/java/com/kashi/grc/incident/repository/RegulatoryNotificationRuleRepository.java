package com.kashi.grc.incident.repository;

import com.kashi.grc.incident.domain.RegulatoryNotificationRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RegulatoryNotificationRuleRepository
        extends JpaRepository<RegulatoryNotificationRule, Long> {

    /**
     * Two plain methods rather than one name with an embedded Or.
     * Spring Data resolves Or across properties without parentheses, so
     * findBy...TenantIdIsNullOrIsActiveTrueAndTenantId parses as
     * (a AND b) OR (c AND d) only by luck of ordering — and silently returns
     * inactive platform rules if the parse goes the other way. The service
     * merges these two, letting a tenant row of the same frameworkRef win.
     */
    List<RegulatoryNotificationRule> findByIsActiveTrueAndTenantIdIsNull();

    List<RegulatoryNotificationRule> findByIsActiveTrueAndTenantId(Long tenantId);
}
