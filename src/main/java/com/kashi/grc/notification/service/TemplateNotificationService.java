package com.kashi.grc.notification.service;

import com.kashi.grc.notification.domain.Notification;
import com.kashi.grc.notification.repository.NotificationRepository;
import com.kashi.grc.uiconfig.domain.NotificationTemplate;
import com.kashi.grc.uiconfig.repository.NotificationTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TemplateNotificationService — drop-in replacement for NotificationService.
 *
 * Extends the original with template resolution:
 *   1. Looks up NotificationTemplate by eventKey
 *   2. Replaces {{placeholder}} tokens with values from the context map
 *   3. Falls back to raw message string if no template found (backward compat)
 *   4. Persists Notification with resolved title, body, icon, colorTag, actionUrl
 *
 * ── HOW TO MIGRATE ────────────────────────────────────────────────────────────
 *
 * Replace all existing NotificationService.send() calls with the new signature:
 *
 *   // OLD (still works — backward compatible)
 *   notificationService.send(userId, "TASK_ASSIGNED", "Task assigned to you", "RISK", 42L)
 *
 *   // NEW (preferred — uses template)
 *   notificationService.send(userId, "TASK_ASSIGNED", Map.of(
 *       "stepName",      "Risk Approval",
 *       "workflowName",  "Risk Management",
 *       "taskId",        String.valueOf(task.getId()),
 *       "entityType",    "RISK",
 *       "entityId",      String.valueOf(risk.getId())
 *   ), "RISK", 42L)
 *
 * ── TEMPLATE PLACEHOLDER FORMAT ───────────────────────────────────────────────
 *
 * Templates use {{key}} syntax matching the context map keys:
 *   titleTemplate:  "Task assigned: {{stepName}}"
 *   bodyTemplate:   "{{workflowName}} requires your attention."
 *   actionUrl:      "/workflow/tasks/{{taskId}}"
 *
 * Unmatched placeholders are left as-is (won't crash).
 *
 * ── NOTIFICATION DOMAIN ADDITIONS NEEDED ─────────────────────────────────────
 *
 * Add these columns to the `notifications` table and Notification entity:
 *
 *   ALTER TABLE notifications
 *     ADD COLUMN title       VARCHAR(255) NULL,
 *     ADD COLUMN body        TEXT NULL,
 *     ADD COLUMN icon        VARCHAR(100) NULL DEFAULT 'Bell',
 *     ADD COLUMN color_tag   VARCHAR(30)  NULL DEFAULT 'blue',
 *     ADD COLUMN action_url  VARCHAR(500) NULL;
 *
 * And to Notification.java:
 *   @Column(name = "title") private String title;
 *   @Column(name = "body", columnDefinition = "TEXT") private String body;
 *   @Column(name = "icon", length = 100) private String icon;
 *   @Column(name = "color_tag", length = 30) private String colorTag;
 *   @Column(name = "action_url", length = 500) private String actionUrl;
 *
 * The existing `message` column is kept for backward compat.
 * Frontend should prefer `title` and `body` when present.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TemplateNotificationService {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{(\\w+)\\}\\}");

    private final NotificationRepository         notificationRepository;
    private final NotificationTemplateRepository templateRepository;

    // ── Primary send method (template-aware) ─────────────────────────────────

    /**
     * Send a notification to one user using a template.
     *
     * @param userId      Recipient user ID
     * @param eventKey    Matches NotificationTemplate.eventKey
     * @param context     Placeholder values: {"stepName": "Risk Approval", "taskId": "99"}
     * @param entityType  e.g. "RISK", "WORKFLOW_INSTANCE"
     * @param entityId    The primary record ID for deep-linking
     */
    @Transactional
    public void send(Long userId, String eventKey, Map<String, String> context,
                     String entityType, Long entityId) {

        Optional<NotificationTemplate> templateOpt = templateRepository.findByEventKeyAndIsActiveTrue(eventKey);

        Notification n = templateOpt.map(template -> buildFromTemplate(
                userId, eventKey, template, context, entityType, entityId))
                .orElseGet(() -> buildFallback(
                        userId, eventKey, context, entityType, entityId));

        notificationRepository.save(n);
        log.debug("Notification sent to user {} — [{}]", userId, eventKey);
    }

    /**
     * Send to multiple users.
     */
    @Transactional
    public void sendToUsers(List<Long> userIds, String eventKey, Map<String, String> context,
                             String entityType, Long entityId) {
        userIds.forEach(uid -> send(uid, eventKey, context, entityType, entityId));
    }

    // ── Backward-compatible overload (preserves existing callers) ────────────

    /**
     * Old signature — still works. Message used as fallback title if no template found.
     * Existing callers don't need to change.
     */
    @Transactional
    public void send(Long userId, String type, String message, String entityType, Long entityId) {
        send(userId, type, Map.of("message", message), entityType, entityId);
    }

    @Transactional
    public void sendToUsers(List<Long> userIds, String type, String message,
                             String entityType, Long entityId) {
        userIds.forEach(uid -> send(uid, type, message, entityType, entityId));
    }

    // ── Private builders ──────────────────────────────────────────────────────

    private Notification buildFromTemplate(Long userId, String eventKey,
                                            NotificationTemplate template,
                                            Map<String, String> context,
                                            String entityType, Long entityId) {
        String title     = resolve(template.getTitleTemplate(), context);
        String body      = resolve(template.getBodyTemplate(), context);
        String actionUrl = resolve(template.getActionUrl(), context);

        return Notification.builder()
                .userId(userId)
                .type(eventKey)
                .message(title)           // legacy field — keep populated
                .title(title)
                .body(body)
                .icon(template.getIcon())
                .colorTag(template.getColorTag())
                .actionUrl(actionUrl)
                .entityType(entityType)
                .entityId(entityId)
                .sentAt(LocalDateTime.now())
                .build();
    }

    private Notification buildFallback(Long userId, String eventKey,
                                        Map<String, String> context,
                                        String entityType, Long entityId) {
        // No template — use event key as title, raw message if present
        String message = context.getOrDefault("message", "Notification: " + eventKey);
        log.warn("No active notification template found for eventKey='{}' — falling back to raw message", eventKey);

        return Notification.builder()
                .userId(userId)
                .type(eventKey)
                .message(message)
                .title(message)
                .icon("Bell")
                .colorTag("blue")
                .entityType(entityType)
                .entityId(entityId)
                .sentAt(LocalDateTime.now())
                .build();
    }

    /**
     * Replace all {{key}} placeholders in a template string with context values.
     * Unmatched placeholders are left as-is.
     */
    private String resolve(String template, Map<String, String> context) {
        if (template == null || template.isBlank()) return template;
        StringBuffer result = new StringBuffer();
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = context.getOrDefault(key, matcher.group(0)); // leave unmatched as-is
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}

/*
 * ── ADMIN ENDPOINTS NEEDED ────────────────────────────────────────────────────
 *
 * Add to UiAdminController (or a new NotificationTemplateAdminController):
 *
 *   GET    /v1/admin/notification-templates
 *          params: search?, isActive?
 *
 *   POST   /v1/admin/notification-templates
 *          body: { eventKey, titleTemplate, bodyTemplate, icon, colorTag, actionUrl, isActive }
 *
 *   GET    /v1/admin/notification-templates/{id}
 *
 *   PUT    /v1/admin/notification-templates/{id}
 *
 *   DELETE /v1/admin/notification-templates/{id}
 *
 *   POST   /v1/admin/notification-templates/preview
 *          body: { titleTemplate, bodyTemplate, actionUrl, context: { key: value } }
 *          Returns: { title, body, actionUrl } with placeholders resolved
 *          (dry-run — does not send any notification)
 *
 * ── REGISTER IN APP.JSX ───────────────────────────────────────────────────────
 *
 *   import NotificationTemplateAdminPage from './pages/admin/notifications/NotificationTemplateAdminPage'
 *
 *   // Add route (Platform Admin only):
 *   <Route path="/admin/notifications" element={<NotificationTemplateAdminPage />} />
 *
 * ── ADD TO NAVIGATION ─────────────────────────────────────────────────────────
 *
 *   INSERT INTO ui_navigation (nav_key, label, icon, route, parent_key, sort_order, module, allowed_sides, is_active)
 *   VALUES ('admin_notifications', 'Notification templates', 'Bell', '/admin/notifications', 'admin_settings', 55, 'ADMIN', 'SYSTEM', true);
 *
 * ── HOW SIDES EXTENSION WORKS ─────────────────────────────────────────────────
 *
 * Your RoleSide enum already has: SYSTEM, ORGANIZATION, VENDOR, AUDITEE, AUDITOR
 *
 * To extend sides to VENDOR/AUDITEE/AUDITOR for new modules:
 *
 * 1. They already exist in the enum — no enum change needed.
 *
 * 2. For VENDOR side: users with RoleSide.VENDOR accessing a new module
 *    (e.g. a shared risk module) get scoped by vendorId automatically IF
 *    the module service filters on vendorId when callerSide = VENDOR.
 *    The TPRM pattern is the reference. New modules that aren't vendor-facing
 *    simply don't include VENDOR in ModuleBlueprint.allowedSides.
 *
 * 3. For AUDITEE side: users who are subjects of an audit (internal staff
 *    being audited). They see their own evidence/responses only.
 *    Scoping: filter by auditeeId (usually their userId or department).
 *
 * 4. For AUDITOR side: external/internal auditors. They see the module in
 *    read-only mode with evidence tabs. No edit capability.
 *    Scoping: auditors are invited per audit engagement — their access is
 *    bounded by the WorkflowInstance they're assigned to (existing pattern).
 *
 * 5. Side-based access in ModuleBlueprint.allowedSides controls nav visibility.
 *    Side-based access in WorkflowStep.side controls which side does each step.
 *    These are orthogonal — a module can be accessible to ORGANIZATION+AUDITOR,
 *    with steps assigned to AUDITOR role for evidence gathering steps.
 *
 * 6. No code change needed for the new sides — they work today.
 *    The only gap: AccessContext.resolveForModule() should check the caller's
 *    side against ModuleBlueprint.allowedSides and return DENIED if not included.
 */
