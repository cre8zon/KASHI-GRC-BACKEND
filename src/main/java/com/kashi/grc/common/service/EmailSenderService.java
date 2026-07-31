package com.kashi.grc.common.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.activation.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Low-level HTTP Email sender using Resend API.
 * Do NOT call directly from business logic — use {@link MailService} instead
 * so subject/body come from DB templates (and sends flow through Kafka).
 *
 * TWO SEND VARIANTS — the difference matters:
 *
 *  sendMail (@Async, swallows exceptions)
 *      Legacy fire-and-forget path, used only when Kafka is disabled.
 *      A failure is logged and LOST — no retry, no record.
 *
 *  sendMailSync (same thread, THROWS on failure)
 *      Used by EmailEventConsumer. Must run on the Kafka listener thread
 *      and must propagate exceptions — that is what triggers the
 *      retry-with-backoff → dead-letter-topic machinery. Wrapping this
 *      in @Async or a try/catch would silently disable all retries.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EmailSenderService {

    private final OkHttpClient client = new OkHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${resend.api.key}")
    private String apiKey;

    @Value("${resend.base.url}")
    private String resendUrl;

    @Value("${app.mail.from}")
    private String emailFrom;

    @Value("${app.mail.from.name:}")
    private String emailFromName;

    /**
     * Synchronous send using Resend API that PROPAGATES failures.
     * Called by EmailEventConsumer so Kafka's error handler can retry / DLT.
     *
     * @throws MailDeliveryException on any HTTP or API payload failure
     */
    public void sendMailSync(String subject, String body, String mimeType, String email) {
        sendMailWithAttachmentSync(email, subject, body, mimeType, null, null);
    }

    /**
     * Core Synchronous send method handling standard mail & attachments via Resend API.
     */
    public void sendMailWithAttachmentSync(String email, String subject, String body,
                                           String mimeType, DataSource attachment, String fileName) {
        try {
            Map<String, Object> payload = new HashMap<>();

            // Set 'from' field (Supports "Name <email@domain.com>" format if name is configured)
            if (StringUtils.hasText(emailFromName)) {
                payload.put("from", String.format("%s <%s>", emailFromName, emailFrom));
            } else {
                payload.put("from", emailFrom);
            }

            // Parse comma-separated emails into a List
            List<String> recipients = Arrays.stream(email.split(","))
                    .map(String::trim)
                    .filter(StringUtils::hasText)
                    .collect(Collectors.toList());

            if (recipients.isEmpty()) {
                throw new MailDeliveryException("No valid recipient email address found", null);
            }

            payload.put("to", recipients);
            payload.put("subject", subject);

            if ("text/html".equalsIgnoreCase(mimeType)) {
                payload.put("html", body);
            } else {
                payload.put("text", body);
            }

            // Handle attachment via Resend JSON Payload (Base64 Encoded)
            if (attachment != null) {
                try (InputStream inputStream = attachment.getInputStream()) {
                    byte[] bytes = StreamUtils.copyToByteArray(inputStream);
                    String base64Content = Base64.getEncoder().encodeToString(bytes);

                    Map<String, Object> attachmentMap = new HashMap<>();
                    attachmentMap.put("filename", StringUtils.hasText(fileName) ? fileName : "attachment");
                    attachmentMap.put("content", base64Content);

                    payload.put("attachments", List.of(attachmentMap));
                } catch (IOException e) {
                    log.error("Failed to process attachment for email to {}", email, e);
                    throw new MailDeliveryException("Failed to encode attachment", e);
                }
            }

            RequestBody requestBody = RequestBody.create(
                    mapper.writeValueAsString(payload),
                    MediaType.parse("application/json")
            );

            Request request = new Request.Builder()
                    .url(resendUrl)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String error = response.body() != null ? response.body().string() : "Unknown error";
                    throw new MailDeliveryException("Resend API Error: " + error, null);
                }
                log.debug("Email sent successfully via Resend to {} — subject: {}", email, subject);
            }

        } catch (IOException e) {
            log.error("Failed sending email via Resend to {}", email, e);
            throw new MailDeliveryException("Resend HTTP request failed", e);
        }
    }

    /** Unchecked wrapper so callers up the Kafka stack see a single exception type. */
    public static class MailDeliveryException extends RuntimeException {
        public MailDeliveryException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // ── Legacy fire-and-forget (Kafka-disabled fallback) ─────────

    @Async
    public void sendMail(String subject, String body, String mimeType, String email) {
        try {
            sendMailSync(subject, body, mimeType, email);
        } catch (MailDeliveryException e) {
            log.error("Failed to send email to {}: {}", email, e.getMessage(), e);
        }
    }

    @Async
    public void sendMailWithAttachment(String email, String subject, String body,
                                       String mimeType, DataSource attachment, String name) {
        try {
            sendMailWithAttachmentSync(email, subject, body, mimeType, attachment, name);
        } catch (MailDeliveryException e) {
            log.error("Failed to send email with attachment to {}: {}", email, e.getMessage(), e);
        }
    }
}