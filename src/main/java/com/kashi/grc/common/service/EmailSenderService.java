package com.kashi.grc.common.service;

import jakarta.activation.DataSource;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.UnsupportedEncodingException;
import java.util.Arrays;

/**
 * Low-level SMTP sender.
 * Do NOT call directly from business logic — use {@link MailService} instead
 * so subject/body come from DB templates.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EmailSenderService {

    private final JavaMailSender javaMailSender;

    @Value("${spring.mail.from:noreply@kashigrc.com}")
    private String emailFrom;

    @Value("${spring.mail.fromName:KashiGRC Platform}")
    private String emailFromName;

    @Async
    public void sendMail(String subject, String body, String mimeType, String email) {
        try {
            MimeMessage mimeMessage = javaMailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, "utf-8");
            setFrom(helper);
            helper.setSubject(subject);
            helper.setText(body, mimeType != null && mimeType.equalsIgnoreCase("text/html"));
            Arrays.stream(email.split(",")).map(String::trim).forEach(s -> {
                try {
                    if (StringUtils.hasText(s)) helper.addTo(s);
                } catch (MessagingException e) {
                    log.warn("Cannot add recipient {}: {}", s, e.getMessage());
                }
            });
            javaMailSender.send(mimeMessage);
            log.debug("Email sent to {} — subject: {}", email, subject);
        } catch (MessagingException e) {
            log.error("Failed to send email to {}: {}", email, e.getMessage(), e);
        }
    }

    @Async
    public void sendMailWithAttachment(String email, String subject, String body,
                                       String mimeType, DataSource attachment, String name) {
        try {
            MimeMessage mimeMessage = javaMailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
            setMimeMessageHelper(helper, subject, body, mimeType);
            if (attachment != null) helper.addAttachment(name, attachment);
            Arrays.stream(email.split(",")).map(String::trim).forEach(s -> {
                try {
                    if (StringUtils.hasText(s)) helper.addTo(s);
                } catch (MessagingException e) {
                    log.warn("Cannot add recipient {}: {}", s, e.getMessage());
                }
            });
            javaMailSender.send(mimeMessage);
        } catch (MessagingException e) {
            log.error("Failed to send email with attachment to {}: {}", email, e.getMessage(), e);
        }
    }

    public void setMimeMessageHelper(MimeMessageHelper helper, String subject,
                                     String body, String mimeType) throws MessagingException {
        setFrom(helper);
        helper.setSubject(subject);
        helper.setText(body, mimeType != null && mimeType.equalsIgnoreCase("text/html"));
    }

    private void setFrom(MimeMessageHelper helper) throws MessagingException {
        try {
            helper.setFrom(emailFrom, emailFromName);
        } catch (UnsupportedEncodingException e) {
            helper.setFrom(emailFrom);
        }
    }
}
