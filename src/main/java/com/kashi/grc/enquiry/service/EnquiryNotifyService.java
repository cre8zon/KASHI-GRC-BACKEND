package com.kashi.grc.enquiry.service;

import com.kashi.grc.common.service.MailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Two emails per enquiry, both AFTER_COMMIT so a rolled-back submission never
 * produces a "we got your request" for a row that does not exist:
 *
 *   enquiry-received        to the sender — their reference and what happens next
 *   enquiry-internal-alert  to kashi.enquiries.notify-to (comma-separated);
 *                           unset means no alert, logged once per enquiry
 *
 * The acknowledgement is transactional mail answering their own request, not
 * marketing — it is sent whatever they chose for marketing consent.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnquiryNotifyService {

    private final EnquiryService enquiryService;
    private final MailService    mailService;

    @Value("${kashi.enquiries.notify-to:}")
    private String notifyTo;

    @Value("${kashi.enquiries.app-base-url:https://app.kashiguard.com}")
    private String appBaseUrl;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReceived(EnquiryEvents.Received ev) {
        Map<String, Object> e;
        try {
            e = enquiryService.mailView(ev.enquiryId());
        } catch (Exception ex) {
            log.warn("[ENQUIRY-MAIL] could not load enquiry {}: {}", ev.enquiryId(), ex.getMessage());
            return;
        }
        String name = s(e.get("fullName"));
        String first = name.isBlank() ? "there" : name.split("\\s+")[0];
        boolean support = "SUPPORT".equals(e.get("type"));

        try {
            Map<String, String> v = new LinkedHashMap<>();
            v.put("firstName", first);
            v.put("reference", s(e.get("reference")));
            v.put("typeLabel", s(e.get("typeLabel")));
            v.put("nextStep", support
                    ? "Someone from our team will reply to this address within one working day."
                    : "We'll reply within one working day to set up a time that suits you.");
            mailService.send("enquiry-received", s(e.get("email")), v, null);
        } catch (Exception ex) {
            log.warn("[ENQUIRY-MAIL] acknowledgement failed for {}: {}", e.get("reference"), ex.getMessage());
        }

        if (notifyTo == null || notifyTo.isBlank()) {
            log.info("[ENQUIRY-MAIL] kashi.enquiries.notify-to is not set — no internal alert for {}", e.get("reference"));
            return;
        }
        Map<String, String> v = new LinkedHashMap<>();
        v.put("reference", s(e.get("reference")));
        v.put("typeLabel", s(e.get("typeLabel")));
        v.put("fullName", name);
        v.put("email", s(e.get("email")));
        v.put("company", s(e.get("company")).isBlank() ? "—" : s(e.get("company")));
        v.put("plan", s(e.get("planCode")).isBlank() ? "—" : s(e.get("planCode")));
        v.put("source", s(e.get("source")).isBlank() ? "—" : s(e.get("source")));
        v.put("message", s(e.get("message")).isBlank() ? "(no message)" : s(e.get("message")));
        v.put("flags", (Boolean.TRUE.equals(e.get("existingCustomer")) ? "Existing customer. " : "")
                + (Boolean.TRUE.equals(e.get("duplicate")) ? "Has another open enquiry." : ""));
        v.put("inboxUrl", appBaseUrl + "/admin/enquiries?open=" + ev.enquiryId());
        Arrays.stream(notifyTo.split(",")).map(String::trim).filter(x -> !x.isEmpty()).forEach(to -> {
            try {
                mailService.send("enquiry-internal-alert", to, v, null);
            } catch (Exception ex) {
                log.warn("[ENQUIRY-MAIL] internal alert to {} failed: {}", to, ex.getMessage());
            }
        });
    }

    private static String s(Object o) { return o == null ? "" : o.toString(); }
}
