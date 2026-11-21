package com.kashi.grc.siteassistant.service;

import com.kashi.grc.common.service.MailService;
import com.kashi.grc.enquiry.service.EnquiryService;
import com.kashi.grc.siteassistant.domain.SiteChatMessage;
import com.kashi.grc.siteassistant.repository.SiteChatMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.util.HtmlUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Emails the visitor when the team replies to their website chat.
 *
 * AFTER_COMMIT, so a reply that failed to save never produces an email.
 *
 * Template: site-chat-reply (seeded by SQL into emailtemplate). Variables:
 *   firstName, reference, replyHtml, siteUrl
 * MailService.merge inserts values into the HTML as they are, so every value
 * that came from a person is HTML-escaped here first; replyHtml is the
 * escaped reply with its line breaks kept.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SiteChatNotifyService {

    private final EnquiryService            enquiryService;
    private final SiteChatMessageRepository messageRepository;
    private final MailService               mailService;

    @Value("${kashi.site-assistant.site-url:https://www.kashiguard.com}")
    private String siteUrl;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStaffReplied(SiteChatEvents.StaffReplied ev) {
        try {
            Map<String, Object> e = enquiryService.mailView(ev.enquiryId());
            SiteChatMessage msg = messageRepository.findById(ev.messageId()).orElse(null);
            String email = s(e.get("email"));
            if (msg == null || email.isBlank()) return;
            String name = s(e.get("fullName"));
            String first = name.isBlank() ? "there" : name.trim().split("\\s+")[0];

            Map<String, String> v = new LinkedHashMap<>();
            v.put("firstName", HtmlUtils.htmlEscape(first));
            v.put("reference", HtmlUtils.htmlEscape(s(e.get("reference"))));
            v.put("replyHtml", HtmlUtils.htmlEscape(msg.getBody()).replace("\r\n", "\n").replace("\n", "<br>"));
            v.put("siteUrl", HtmlUtils.htmlEscape(siteUrl == null ? "" : siteUrl.replaceAll("/+$", "")));
            mailService.send("site-chat-reply", email, v, null);
        } catch (Exception ex) {
            log.warn("[SITE-CHAT-MAIL] reply email failed for enquiry {}: {}", ev.enquiryId(), ex.getMessage());
        }
    }

    private static String s(Object o) { return o == null ? "" : o.toString(); }
}
