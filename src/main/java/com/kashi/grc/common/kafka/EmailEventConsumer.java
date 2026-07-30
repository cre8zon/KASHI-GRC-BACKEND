package com.kashi.grc.common.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashi.grc.common.config.multitenancy.TenantContext;
import com.kashi.grc.common.domain.EmailLog;
import com.kashi.grc.common.repository.EmailLogRepository;
import com.kashi.grc.common.repository.EmailTemplateRepository;
import com.kashi.grc.common.service.EmailSenderService;
import com.kashi.grc.common.service.MailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Consumer for kashigrc.email.requested — does the ACTUAL email work
 * that used to happen on the HTTP request thread.
 *
 * Handles two event types:
 *   EMAIL_TEMPLATE_REQUESTED  {templateName, to, variables}
 *       → template lookup from emailtemplate table, {{key}} merge, SMTP send
 *   EMAIL_RAW_REQUESTED       {subject, body, mimeType, to}
 *       → SMTP send as-is
 *
 * GUARANTEES:
 *   Idempotency — Kafka is at-least-once; on redelivery the EmailLog row
 *       with status SENT for this eventId short-circuits the send, so a
 *       customer never receives the same email twice.
 *   History — every outcome (SENT / FAILED + error) is recorded in email_log.
 *   Retry/DLT — sendMailSync THROWS on SMTP failure; the exception propagates
 *       to the container error handler → 3 retries with backoff → the record
 *       lands on kashigrc.email.requested.DLT. Never swallow exceptions here.
 *   Tenant isolation — TenantContext is set from the envelope FIRST and
 *       cleared in finally (listener threads are pooled and reused).
 *
 * MISSING TEMPLATE is deliberately NOT an exception: retrying won't create
 * the template. It's logged + recorded as FAILED, and the offset commits.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "kashi.kafka.enabled", havingValue = "true")
public class EmailEventConsumer {

    private final EmailTemplateRepository emailTemplateRepository;
    private final EmailSenderService      emailSenderService;
    private final EmailLogRepository      emailLogRepository;
    private final ObjectMapper            objectMapper;

    @KafkaListener(
            topics = KafkaTopics.EMAIL_REQUESTED,
            groupId = "kashigrc-email",
            containerFactory = "kafkaListenerContainerFactory")
    public void onEmailRequested(KafkaEventEnvelope envelope) {
        try {
            if (envelope.getTenantId() != null) {
                TenantContext.setCurrentTenant(envelope.getTenantId());
            }

            // Idempotency: already successfully sent → skip (redelivery)
            if (emailLogRepository.existsByEventIdAndStatus(
                    envelope.getEventId(), EmailLog.Status.SENT)) {
                log.info("Email event {} already SENT — skipping duplicate delivery",
                        envelope.getEventId());
                return;
            }

            Map<String, Object> p = envelope.getPayload();

            switch (envelope.getEventType()) {
                case "EMAIL_TEMPLATE_REQUESTED" -> handleTemplate(envelope, p);
                case "EMAIL_RAW_REQUESTED"      -> handleRaw(envelope, p);
                default -> log.warn("Unknown email eventType '{}' — eventId={}, ignoring",
                        envelope.getEventType(), envelope.getEventId());
            }

        } catch (Exception e) {
            // Record the failure, then RE-THROW so retries + DLT engage.
            recordOutcome(envelope, str(envelope.getPayload().get("to")),
                    str(envelope.getPayload().get("templateName")),
                    str(envelope.getPayload().get("subject")),
                    EmailLog.Status.FAILED, e.getMessage());
            throw e;
        } finally {
            TenantContext.clear();
        }
    }

    // ── Handlers ─────────────────────────────────────────────────

    private void handleTemplate(KafkaEventEnvelope envelope, Map<String, Object> p) {
        String templateName = str(p.get("templateName"));
        String to           = str(p.get("to"));

        @SuppressWarnings("unchecked")
        Map<String, String> variables = objectMapper.convertValue(
                p.getOrDefault("variables", Map.of()), Map.class);

        var templateOpt = emailTemplateRepository.findByNameAndIsActiveTrue(templateName);
        if (templateOpt.isEmpty()) {
            // Not retryable — record and move on (no throw).
            log.warn("Email template '{}' not found or inactive — eventId={}, to={}",
                    templateName, envelope.getEventId(), to);
            recordOutcome(envelope, to, templateName, null,
                    EmailLog.Status.FAILED, "Template not found or inactive: " + templateName);
            return;
        }

        var template   = templateOpt.get();
        String subject = MailService.merge(template.getSubject(), variables);
        String body    = MailService.merge(template.getContent(), variables);

        emailSenderService.sendMailSync(subject, body, template.getMimeType(), to); // throws on failure

        recordOutcome(envelope, to, templateName, subject, EmailLog.Status.SENT, null);
        log.info("Email sent — template={} to={} tenant={} eventId={}",
                templateName, to, envelope.getTenantId(), envelope.getEventId());
    }

    private void handleRaw(KafkaEventEnvelope envelope, Map<String, Object> p) {
        String to       = str(p.get("to"));
        String subject  = str(p.get("subject"));
        String body     = str(p.get("body"));
        String mimeType = str(p.get("mimeType"));

        emailSenderService.sendMailSync(subject, body, mimeType, to); // throws on failure

        recordOutcome(envelope, to, null, subject, EmailLog.Status.SENT, null);
        log.info("Email sent — raw to={} tenant={} eventId={}",
                to, envelope.getTenantId(), envelope.getEventId());
    }

    // ── History ──────────────────────────────────────────────────

    private void recordOutcome(KafkaEventEnvelope envelope, String to, String templateName,
                               String subject, EmailLog.Status status, String error) {
        try {
            // Lineage: originating event (e.g. notification fanout) if present
            String sourceEventId = envelope.getPayload() != null
                    ? str(envelope.getPayload().get("sourceEventId")) : null;
            EmailLog logRow = emailLogRepository.findByEventId(envelope.getEventId())
                    .orElseGet(() -> EmailLog.builder()
                            .eventId(envelope.getEventId())
                            .tenantId(envelope.getTenantId())
                            .recipient(to != null ? to : "unknown")
                            .templateName(templateName)
                            .sourceEventId(sourceEventId)
                            .attempts(0)
                            .build());
            logRow.setStatus(status);
            logRow.setSubject(truncate(subject, 500));
            logRow.setErrorMessage(truncate(error, 1000));
            logRow.setAttempts(logRow.getAttempts() + 1);
            if (status == EmailLog.Status.SENT) logRow.setSentAt(LocalDateTime.now());
            emailLogRepository.save(logRow);
        } catch (Exception e) {
            // History write must never break email processing or mask the real error.
            log.error("Failed to write email_log for eventId={}: {}",
                    envelope.getEventId(), e.getMessage());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────

    private static String str(Object o)              { return o != null ? o.toString() : null; }
    private static String truncate(String s, int n)  { return s == null || s.length() <= n ? s : s.substring(0, n); }
}