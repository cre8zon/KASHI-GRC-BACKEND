package com.kashi.grc.collab.service;

import com.kashi.grc.notification.spi.NotificationRouteContributor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Where Collaboration and Chat notifications open (see NotificationRouteContributor).
 *
 *   COLLAB_WORKSPACE   /collaboration/workspaces/{id}, on the tab the event is about:
 *                      requests (COLLAB_REQUEST*), plan (COLLAB_PLAN_*), meetings,
 *                      overview (membership)
 *   COLLAB_MEETING     /collaboration/meetings/{id}; a "join now" (COLLAB_CALL,
 *                      COLLAB_MEETING_STARTING) adds ?join=1 so the call opens
 *   COLLAB_ROOM        /collaboration/rooms/{id}
 *   CHAT_CONVERSATION  /chat/{id}
 *
 * The routes only need the id the notification already carries, so no lookups
 * — nothing to fail, nothing to inject. Opening still goes through the normal
 * access checks of each page.
 */
@Slf4j
@Component
public class CollabNotificationRoutes implements NotificationRouteContributor {

    @Override
    public boolean supports(String entityType) {
        return "COLLAB_WORKSPACE".equals(entityType) || "COLLAB_MEETING".equals(entityType)
                || "COLLAB_ROOM".equals(entityType) || "CHAT_CONVERSATION".equals(entityType);
    }

    @Override
    public String routeFor(String entityType, Long entityId, String type, Long userId) {
        if (entityId == null) return null;
        String t = type == null ? "" : type;
        switch (entityType) {
            case "COLLAB_WORKSPACE": {
                String tab = t.startsWith("COLLAB_REQUEST") ? "requests"
                        : t.startsWith("COLLAB_PLAN") ? "plan"
                          : t.startsWith("COLLAB_MEETING") ? "meetings"
                            : null;
                return "/collaboration/workspaces/" + entityId + (tab != null ? "?tab=" + tab : "");
            }
            case "COLLAB_MEETING":
                return "/collaboration/meetings/" + entityId
                        + ("COLLAB_CALL".equals(t) || "COLLAB_MEETING_STARTING".equals(t) ? "?join=1" : "");
            case "COLLAB_ROOM":
                return "/collaboration/rooms/" + entityId;
            case "CHAT_CONVERSATION":
                return "/chat/" + entityId;
            default:
                return null;
        }
    }
}