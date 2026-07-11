package com.kashi.grc.common.service;

import com.kashi.grc.common.config.multitenancy.TenantContext;
import com.kashi.grc.common.kafka.KafkaEventPublisher;
import com.kashi.grc.common.kafka.KafkaTopics;
import com.kashi.grc.common.repository.EmailTemplateRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Centralised, template-aware email service — now the single Kafka PRODUCER
 * for the kashigrc.email.requested topic.
 *
 * ALL email sends project-wide must go through this class.
 *
 * FLOW (Kafka enabled):
 *   send()    → publish EMAIL_TEMPLATE_REQUESTED {templateName, to, variables}
 *               → EmailEventConsumer looks up template, merges, sends SMTP
 *   sendRaw() → publish EMAIL_RAW_REQUESTED {subject, body, mimeType, to}
 *               → EmailEventConsumer sends SMTP directly
 *
 *   The HTTP request thread returns immediately after the publish —
 *   no template DB lookup, no SMTP round-trip on the request path.
 *
 * FLOW (Kafka disabled — kashi.kafka.enabled=false):
 *   Falls back to the original synchronous-lookup + @Async-send path.
 *   Zero behaviour change for environments without a broker.
 *
 * Registered template names:
 *   user-invitation       — new user created, includes set-password URL
 *   password-reset        — forgot password flow
 *   task-assigned         — workflow task assigned to a user
 *   assessment-submitted  — vendor submitted assessment to reviewer
 *   assessment-sent-back  — reviewer sent assessment back to vendor
 *   workflow-completed    — workflow instance completed
 *   vendor-onboarded      — vendor onboarding completed
 */
@Slf4j
@Service
public class MailService {

    private final EmailTemplateRepository emailTemplateRepository;
    private final EmailSenderService      emailSenderService;
    /** Present only when kashi.kafka.enabled=true (bean is @ConditionalOnProperty). */
    private final KafkaEventPublisher     kafkaPublisher;   // nullable

    public MailService(EmailTemplateRepository emailTemplateRepository,
                       EmailSenderService emailSenderService,
                       ObjectProvider<KafkaEventPublisher> kafkaPublisherProvider) {
        this.emailTemplateRepository = emailTemplateRepository;
        this.emailSenderService      = emailSenderService;
        this.kafkaPublisher          = kafkaPublisherProvider.getIfAvailable();
    }

    // ── Template-based sends ─────────────────────────────────────

    /**
     * Core template send. Template lookup + merge happens in the CONSUMER
     * when Kafka is enabled — this method just publishes and returns.
     */
    public void send(String templateName, String to, Map<String, String> variables) {
        if (kafkaPublisher != null) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("templateName", templateName);
            payload.put("to", to);
            payload.put("variables", variables);
            kafkaPublisher.publish(KafkaTopics.EMAIL_REQUESTED,
                    "EMAIL_TEMPLATE_REQUESTED", to, payload);
            return;
        }
        sendDirect(templateName, to, variables);
    }

    public void send(String templateName, String to) {
        send(templateName, to, Map.of());
    }

    // ── Raw (pre-rendered) sends ─────────────────────────────────
    // For callers that build subject/body themselves (e.g. SLA escalation).

    /** Raw send from a request thread — tenant taken from TenantContext. */
    public void sendRaw(String subject, String body, String mimeType, String to) {
        sendRaw(subject, body, mimeType, to, TenantContext.getCurrentTenant());
    }

    /**
     * Raw send with EXPLICIT tenant — REQUIRED from schedulers and other
     * non-request threads where TenantContext is empty
     * (e.g. IssueService SLA escalation: pass issue.getTenantId()).
     */
    public void sendRaw(String subject, String body, String mimeType, String to, Long tenantId) {
        if (kafkaPublisher != null) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("subject", subject);
            payload.put("body", body);
            payload.put("mimeType", mimeType);
            payload.put("to", to);
            kafkaPublisher.publish(KafkaTopics.EMAIL_REQUESTED,
                    "EMAIL_RAW_REQUESTED", to, payload, tenantId, null);
            return;
        }
        emailSenderService.sendMail(subject, body, mimeType, to);
    }

    // ── Convenience helpers (unchanged signatures) ───────────────

    public void sendUserInvitation(String to, String firstName, String resetUrl) {
        send("user-invitation", to, Map.of(
                "firstName", firstName,
                "resetUrl",  resetUrl
        ));
    }

    public void sendPasswordReset(String to, String firstName, String resetUrl) {
        send("password-reset", to, Map.of(
                "firstName", firstName,
                "resetUrl",  resetUrl
        ));
    }

    public void sendTaskAssigned(String to, String firstName, String stepName,
                                 String entityName, String taskUrl) {
        send("task-assigned", to, Map.of(
                "firstName",  firstName,
                "stepName",   stepName,
                "entityName", entityName,
                "taskUrl",    taskUrl
        ));
    }

    public void sendAssessmentSubmitted(String to, String reviewerName,
                                        String vendorName, String assessmentUrl) {
        send("assessment-submitted", to, Map.of(
                "reviewerName",  reviewerName,
                "vendorName",    vendorName,
                "assessmentUrl", assessmentUrl
        ));
    }

    public void sendAssessmentSentBack(String to, String firstName,
                                       String vendorName, String reason, String assessmentUrl) {
        send("assessment-sent-back", to, Map.of(
                "firstName",     firstName,
                "vendorName",    vendorName,
                "reason",        reason,
                "assessmentUrl", assessmentUrl
        ));
    }

    public void sendWorkflowCompleted(String to, String firstName, String vendorName) {
        send("workflow-completed", to, Map.of(
                "firstName",  firstName,
                "vendorName", vendorName
        ));
    }

    // ── Legacy direct path (Kafka disabled) ──────────────────────

    private void sendDirect(String templateName, String to, Map<String, String> variables) {
        var templateOpt = emailTemplateRepository.findByNameAndIsActiveTrue(templateName);
        if (templateOpt.isEmpty()) {
            log.warn("Email template '{}' not found or inactive — skipping send to {}", templateName, to);
            return;
        }
        var template = templateOpt.get();
        String subject = merge(template.getSubject(), variables);
        String body    = merge(template.getContent(),  variables);
        emailSenderService.sendMail(subject, body, template.getMimeType(), to);
    }

    // ── Shared merge (also used by EmailEventConsumer) ───────────

    public static String merge(String template, Map<String, String> vars) {
        if (template == null || vars == null) return template;
        String result = template;
        for (var entry : vars.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}",
                    entry.getValue() != null ? entry.getValue() : "");
        }
        return result;
    }
}