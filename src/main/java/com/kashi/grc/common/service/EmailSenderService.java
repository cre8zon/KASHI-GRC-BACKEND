package com.kashi.grc.common.service;

import jakarta.activation.DataSource;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.*;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * Low-level Email sender using AWS SES v2 SDK.
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
 *      retry-with-backoff → dead-letter-topic machinery.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EmailSenderService {

    private final SesV2Client sesV2Client;

    @Value("${app.mail.from}")
    private String emailFrom;

    @Value("${app.mail.from.name:}")
    private String emailFromName;

    /**
     * Synchronous send using AWS SES API that PROPAGATES failures.
     * Called by EmailEventConsumer so Kafka's error handler can retry / DLT.
     *
     * @throws MailDeliveryException on any SES API failure
     */
    public void sendMailSync(String subject, String body, String mimeType, String email) {
        try {
            List<String> recipients = parseRecipients(email);
            if (recipients.isEmpty()) {
                throw new MailDeliveryException("No valid recipient email address found", null);
            }

            String formattedFrom = StringUtils.hasText(emailFromName)
                    ? String.format("%s <%s>", emailFromName, emailFrom)
                    : emailFrom;

            Body bodyPayload;
            if ("text/html".equalsIgnoreCase(mimeType)) {
                bodyPayload = Body.builder().html(Content.builder().data(body).build()).build();
            } else {
                bodyPayload = Body.builder().text(Content.builder().data(body).build()).build();
            }

            SendEmailRequest request = SendEmailRequest.builder()
                    .fromEmailAddress(formattedFrom)
                    .destination(Destination.builder().toAddresses(recipients).build())
                    .content(EmailContent.builder()
                            .simple(software.amazon.awssdk.services.sesv2.model.Message.builder() // <-- Yahan fully qualified name use karein
                                    .subject(Content.builder().data(subject).build())
                                    .body(bodyPayload)
                                    .build())
                            .build())
                    .build();

            sesV2Client.sendEmail(request);
            log.debug("Email sent successfully via AWS SES to {} — subject: {}", email, subject);

        } catch (SesV2Exception e) {
            log.error("AWS SES API Error while sending email to {}: {}", email, e.awsErrorDetails().errorMessage());
            throw new MailDeliveryException("AWS SES Send Failed: " + e.awsErrorDetails().errorMessage(), e);
        } catch (Exception e) {
            log.error("Failed sending email via AWS SES to {}", email, e);
            throw new MailDeliveryException("SES Send Failed", e);
        }
    }

    /**
     * Synchronous send for emails WITH ATTACHMENTS using Raw MIME message via AWS SES.
     */
    public void sendMailWithAttachmentSync(String email, String subject, String body,
                                           String mimeType, DataSource attachment, String fileName) {
        try {
            List<String> recipients = parseRecipients(email);
            if (recipients.isEmpty()) {
                throw new MailDeliveryException("No valid recipient email address found", null);
            }

            // Create MIME message
            Session session = Session.getDefaultInstance(new Properties());
            MimeMessage mimeMessage = new MimeMessage(session);

            if (StringUtils.hasText(emailFromName)) {
                mimeMessage.setFrom(new InternetAddress(emailFrom, emailFromName));
            } else {
                mimeMessage.setFrom(new InternetAddress(emailFrom));
            }

            for (String recipient : recipients) {
                mimeMessage.addRecipient(Message.RecipientType.TO, new InternetAddress(recipient));
            }
            mimeMessage.setSubject(subject, "UTF-8");

            // Multipart body setup
            MimeMultipart multipart = new MimeMultipart("mixed");

            // Text/HTML Part
            MimeBodyPart bodyPart = new MimeBodyPart();
            if ("text/html".equalsIgnoreCase(mimeType)) {
                bodyPart.setContent(body, "text/html; charset=UTF-8");
            } else {
                bodyPart.setText(body, "UTF-8");
            }
            multipart.addBodyPart(bodyPart);

            // Attachment Part
            if (attachment != null) {
                MimeBodyPart attachmentPart = new MimeBodyPart();
                attachmentPart.setDataHandler(new jakarta.activation.DataHandler(attachment));
                attachmentPart.setFileName(StringUtils.hasText(fileName) ? fileName : "attachment");
                multipart.addBodyPart(attachmentPart);
            }

            mimeMessage.setContent(multipart);

            // Convert MIME message to Raw SES Payload
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            mimeMessage.writeTo(outputStream);

            SendEmailRequest request = SendEmailRequest.builder()
                    .content(EmailContent.builder()
                            .raw(RawMessage.builder()
                                    .data(SdkBytes.fromByteArray(outputStream.toByteArray()))
                                    .build())
                            .build())
                    .build();

            sesV2Client.sendEmail(request);
            log.debug("Email with attachment sent successfully via AWS SES to {}", email);

        } catch (SesV2Exception e) {
            log.error("AWS SES API Error while sending attachment email to {}: {}", email, e.awsErrorDetails().errorMessage());
            throw new MailDeliveryException("AWS SES Attachment Send Failed: " + e.awsErrorDetails().errorMessage(), e);
        } catch (Exception e) {
            log.error("Failed to send email with attachment via AWS SES to {}", email, e);
            throw new MailDeliveryException("SES Raw Email Send Failed", e);
        }
    }

    private List<String> parseRecipients(String email) {
        return Arrays.stream(email.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toList());
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