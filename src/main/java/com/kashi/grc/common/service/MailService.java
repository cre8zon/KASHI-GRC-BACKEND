package com.kashi.grc.common.service;

import com.kashi.grc.common.repository.EmailTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Centralised, template-aware email service.
 *
 * ALL email sends project-wide must go through this class.
 * Templates are stored in the {@code emailtemplate} table, looked up by name.
 * Placeholders in content use {{key}} syntax.
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
@RequiredArgsConstructor
public class MailService {

    private final EmailTemplateRepository emailTemplateRepository;
    private final EmailSenderService      emailSenderService;

    /**
     * Core send method.
     * Looks up template by name, merges variables, then sends asynchronously.
     */
    public void send(String templateName, String to, Map<String, String> variables) {
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

    public void send(String templateName, String to) {
        send(templateName, to, Map.of());
    }

    // ── Convenience helpers ───────────────────────────────────────

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

    // ── Private ──────────────────────────────────────────────────

    private String merge(String template, Map<String, String> vars) {
        if (template == null || vars == null) return template;
        String result = template;
        for (var entry : vars.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}",
                    entry.getValue() != null ? entry.getValue() : "");
        }
        return result;
    }
}
