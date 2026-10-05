package com.kashi.grc.audit.notification;

import com.kashi.grc.audit.repository.AuditControlInstanceRepository;
import com.kashi.grc.audit.repository.AuditPolicyInstanceRepository;
import com.kashi.grc.audit.repository.AuditTestInstanceRepository;
import com.kashi.grc.notification.spi.NotificationRouteContributor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Where a notification about an audit control / test / policy instance opens:
 * its ENGAGEMENT, on the Controls tab, with the instance open in a drawer — the
 * same place an inbox row for the same work opens (AuditObligationService), so
 * a notification and an action item about one control never disagree about
 * where the work is done.
 *
 *   /module/audit_engagement/{eid}?tab=controls
 *       &drawerType=AUDIT_CONTROL_INSTANCE&drawerId={id}&drawerTab=evidence
 *
 * The drawer tab is chosen by the notification type, because the same control
 * is sent to one person to provide evidence and to another to test it.
 *
 * Follows the rules on NotificationRouteContributor: repositories only, never
 * throws, and null (client fallback) rather than a guess when the instance or
 * its engagement cannot be found.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditInstanceNotificationRoutes implements NotificationRouteContributor {

    private final AuditControlInstanceRepository controlRepo;
    private final AuditTestInstanceRepository    testRepo;
    private final AuditPolicyInstanceRepository  policyRepo;

    @Override
    public boolean supports(String entityType) {
        return "AUDIT_CONTROL_INSTANCE".equals(entityType)
                || "AUDIT_TEST_INSTANCE".equals(entityType)
                || "AUDIT_POLICY_INSTANCE".equals(entityType);
    }

    @Override
    public String routeFor(String entityType, Long entityId, String type, Long userId) {
        if (entityId == null) return null;
        String t = type == null ? "" : type;
        try {
            switch (entityType) {
                case "AUDIT_CONTROL_INSTANCE": {
                    Long eid = controlRepo.findById(entityId).map(c -> c.getEngagementId()).orElse(null);
                    String tab = t.startsWith("AUDIT_EVIDENCE_") ? "evidence"
                            : "AUDIT_CONTROL_TEST_DELEGATED".equals(t) ? "fieldwork" : null;
                    return route(eid, entityType, entityId, tab);
                }
                case "AUDIT_TEST_INSTANCE": {
                    Long eid = testRepo.findById(entityId).map(x -> x.getEngagementId()).orElse(null);
                    return route(eid, entityType, entityId, null);
                }
                case "AUDIT_POLICY_INSTANCE": {
                    Long eid = policyRepo.findById(entityId).map(p -> p.getEngagementId()).orElse(null);
                    return route(eid, entityType, entityId,
                            "AUDIT_POLICY_REVIEW_DELEGATED".equals(t) ? "policy-content" : null);
                }
                default:
                    return null;
            }
        } catch (Exception ex) {
            log.warn("[AUDIT-NOTIFY-ROUTE] {}:{} — {}", entityType, entityId, ex.getMessage());
            return null;
        }
    }

    private static String route(Long engagementId, String entityType, Long id, String drawerTab) {
        if (engagementId == null) return null;
        return "/module/audit_engagement/" + engagementId
                + "?tab=controls&drawerType=" + entityType + "&drawerId=" + id
                + (drawerTab != null ? "&drawerTab=" + drawerTab : "");
    }
}