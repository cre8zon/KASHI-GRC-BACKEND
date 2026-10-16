package com.kashi.grc.firmverification.service;

import com.kashi.grc.notification.spi.NotificationRouteContributor;
import org.springframework.stereotype.Component;

/** Notification deep links — every recipient is a platform user, so all land on the review queue. */
@Component
public class FirmVerificationNotificationRoutes implements NotificationRouteContributor {

    @Override
    public boolean supports(String entityType) {
        return AuditFirmVerificationService.ENTITY_TYPE.equals(entityType);
    }

    @Override
    public String routeFor(String entityType, Long entityId, String type, Long userId) {
        return "/admin/firm-verifications?id=" + entityId;
    }
}
