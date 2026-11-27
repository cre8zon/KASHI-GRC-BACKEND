package com.kashi.grc.siteassistant.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashi.grc.ai.chat.AiChatService;
import com.kashi.grc.ai.domain.AiEnums.ChunkSourceType;
import com.kashi.grc.ai.domain.AiEnums.TaskType;
import com.kashi.grc.ai.guardrail.PromptInjectionScanner;
import com.kashi.grc.ai.policy.BritishSpelling;
import com.kashi.grc.ai.rag.RetrievalService;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.content.domain.ContentEnums.ContentType;
import com.kashi.grc.content.domain.Post;
import com.kashi.grc.content.repository.PostRepository;
import com.kashi.grc.enquiry.domain.Enquiry;
import com.kashi.grc.enquiry.dto.EnquirySubmitRequest;
import com.kashi.grc.enquiry.repository.EnquiryRepository;
import com.kashi.grc.enquiry.service.EnquiryRateLimiter;
import com.kashi.grc.enquiry.service.EnquiryService;
import com.kashi.grc.siteassistant.domain.SiteChatMessage;
import com.kashi.grc.siteassistant.domain.SiteChatSession;
import com.kashi.grc.siteassistant.domain.SiteChatSourcePage;
import com.kashi.grc.siteassistant.dto.SiteChatDtos.*;
import com.kashi.grc.siteassistant.repository.SiteChatMessageRepository;
import com.kashi.grc.siteassistant.repository.SiteChatSessionRepository;
import com.kashi.grc.siteassistant.repository.SiteChatSourcePageRepository;
import com.kashi.grc.usermanagement.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The website assistant on www.kashiguard.com: answers anonymous visitors
 * from the website's own pages and posts, and hands the conversation to the
 * team (as an Enquiry) when a person should take over.
 *
 * ── WHAT IT MAY READ ─────────────────────────────────────────────────────────
 * Only WEBSITE_PAGE and WEBSITE_POST chunks — the public website. Never a
 * tenant's data: retrieval runs with tenantId null and these two types only.
 *
 * ── WHEN A PERSON TAKES OVER ─────────────────────────────────────────────────
 * The widget always shows "Talk to a person". On top of that the reply
 * carries handover.suggest when any of these holds:
 *   - the visitor asks for a person, a demo, a call or a quote (rule);
 *   - the topic is contracts, a DPA, a security questionnaire, a pen-test
 *     report, procurement, a discount… (rule — never left to the model);
 *   - the model flags it (support problem, partnership, …);
 *   - the assistant could not answer twice in a row.
 * Handing over creates an Enquiry (channel CHAT) through EnquiryService —
 * same validation, duplicate folding and acknowledgement email as the forms —
 * with the AI's summary as its message. Later visitor messages go to the
 * team, not the AI.
 *
 * ── COST AND ABUSE ───────────────────────────────────────────────────────────
 * Public AI calls have no tenant budget, so the assistant keeps its own:
 *   20 messages per IP per 10 minutes, 40 per conversation, a site-wide daily
 *   cap of answers (kashi.site-assistant.daily-answer-cap), 1,000 characters
 *   per message, a honeypot field. Past a limit the visitor is offered a
 *   person instead of an answer.
 *
 * Deliberately not one transaction: the model call takes seconds, and holding
 * a database transaction open across it would tie up a connection per visitor.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SiteAssistantService {

    private static final List<ChunkSourceType> WEB = List.of(ChunkSourceType.WEBSITE_PAGE, ChunkSourceType.WEBSITE_POST);
    private static final int TOP_K = 6;
    private static final int MAX_MESSAGE = 1000;
    private static final int HISTORY_TURNS = 6;
    private static final List<String> HANDOVER_TYPES =
            List.of("DEMO", "CONTACT_SALES", "SUPPORT", "PARTNERSHIP", "AUDIT_FIRM", "GENERAL");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern LABEL = Pattern.compile("\\[(W\\d+)]");

    // Handover rules that never depend on the model.
    private static final Pattern WANTS_PERSON = Pattern.compile(
            "\\b(talk|speak|chat)\\s+(to|with)\\s+(a\\s+|an\\s+|the\\s+|someone|somebody|sales|support|your\\s+team|human|person|agent|representative)"
            + "|\\b(human|real person|someone from|call me|contact me|get in touch|reach out to me|callback|call back)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern WANTS_DEMO = Pattern.compile(
            "\\b(book|schedule|arrange|get|see|want|request)\\s+(a\\s+|an\\s+)?(demo|walkthrough|trial|call|meeting)\\b|\\bdemo\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SALES_ONLY = Pattern.compile(
            "\\b(quote|quotation|discount|custom pric|negotiat|invoice|purchase order|procurement|rfp|rfq|tender|"
            + "contract|msa|master services|dpa|data processing agreement|nda|terms of service|sla\\b|"
            + "pen ?test|penetration test|security questionnaire|vendor assessment of kashiguard|soc ?2 report|iso certificate|"
            + "reseller|resell|partner(ship)? programme|partner program)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SUPPORT = Pattern.compile(
            "\\b(can'?t|cannot|unable to)\\s+(log ?in|sign ?in|access)|\\b(password reset|forgot (my )?password|"
            + "bug|error message|not working|is down|outage|my account|my tenant|our workspace)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AUDIT_FIRM = Pattern.compile(
            "\\b(audit firm|auditing firm|ca firm|chartered accountant|we are auditors|our audit practice)\\b",
            Pattern.CASE_INSENSITIVE);

    private final SiteChatSessionRepository    sessionRepository;
    private final SiteChatMessageRepository    messageRepository;
    private final SiteChatSourcePageRepository pageRepository;
    private final PostRepository               postRepository;
    private final EnquiryService               enquiryService;
    private final EnquiryRepository            enquiryRepository;
    private final EnquiryRateLimiter           rateLimiter;
    private final RetrievalService             retrievalService;
    private final AiChatService                chatService;
    private final PromptInjectionScanner       injectionScanner;
    private final UserRepository               userRepository;
    private final ApplicationEventPublisher    events;
    private final ObjectMapper                 mapper;

    @Value("${kashi.site-assistant.enabled:true}")
    private boolean enabled;

    @Value("${kashi.site-assistant.site-url:https://www.kashiguard.com}")
    private String siteUrl;

    @Value("${kashi.site-assistant.daily-answer-cap:1500}")
    private long dailyAnswerCap;

    // ══════════════════════ PUBLIC: ASK ═══════════════════════════════════════

    public AskResponse ask(AskRequest req, String token, String ip, String userAgent) {
        if (!enabled) {
            throw new BusinessException("SITE_ASSISTANT_OFF",
                    "The assistant is offline right now. You can still reach the team through the contact form.",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
        String text = req == null || req.message() == null ? "" : req.message().trim();
        if (text.isEmpty()) throw bad("message", "Type a question first.");
        if (text.length() > MAX_MESSAGE) text = text.substring(0, MAX_MESSAGE);

        // Bots: a plausible answer, nothing stored, nothing spent.
        if (req.website() != null && !req.website().isBlank()) {
            return new AskResponse(null, SiteChatSession.ACTIVE, null,
                    view(SiteChatMessage.builder().role(SiteChatMessage.ASSISTANT)
                            .body("Thanks — someone from the team can help with that.").build(), List.of(), null),
                    new Handover(true, "GENERAL", "honeypot"), null);
        }

        String ipHash = enquiryService.hashIp(ip);
        if (!rateLimiter.allow("chat:ip:" + ipHash, 20, Duration.ofMinutes(10))) {
            throw new BusinessException("SITE_ASSISTANT_RATE_LIMITED",
                    "You're sending messages faster than I can keep up. Please wait a few minutes.",
                    HttpStatus.TOO_MANY_REQUESTS);
        }

        // ── the session: existing (token) or new ────────────────────────────
        String newToken = null;
        SiteChatSession s = token == null || token.isBlank() ? null
                : sessionRepository.findByTokenHash(sha256(token.trim())).orElse(null);
        if (s == null) {
            newToken = newToken();
            s = SiteChatSession.builder()
                    .tokenHash(sha256(newToken))
                    .firstPage(trim(req.page(), 512))
                    .landingPage(trim(req.landingPage(), 512))
                    .referrer(trim(req.referrer(), 512))
                    .utmSource(trim(req.utmSource(), 100))
                    .utmMedium(trim(req.utmMedium(), 100))
                    .utmCampaign(trim(req.utmCampaign(), 150))
                    .utmTerm(trim(req.utmTerm(), 150))
                    .utmContent(trim(req.utmContent(), 150))
                    .ipHash(ipHash)
                    .userAgent(trim(userAgent, 255))
                    .build();
        }
        if (req.page() != null && !req.page().isBlank()) s.setLastPage(trim(req.page(), 512));
        s.setVisitorMessages((s.getVisitorMessages() == null ? 0 : s.getVisitorMessages()) + 1);
        s.setLastMessageAt(LocalDateTime.now());
        s = sessionRepository.save(s);

        SiteChatMessage asked = messageRepository.save(SiteChatMessage.builder()
                .sessionId(s.getId()).role(SiteChatMessage.VISITOR).body(text).build());

        // ── handed over: the team answers from here on ──────────────────────
        if (SiteChatSession.HANDED_OVER.equals(s.getStatus())) {
            String ref = reference(s.getEnquiryId());
            if (s.getEnquiryId() != null) enquiryService.recordChatFollowUp(s.getEnquiryId(), text);
            return new AskResponse(newToken, s.getStatus(), ref, null, null,
                    "Added to your request" + (ref == null ? "" : " " + ref)
                            + ". The team will reply here and by email.");
        }

        // ── limits that end in a person, not an answer ───────────────────────
        String ruleType = ruleHandoverType(text);
        if (s.getVisitorMessages() > 40) {
            return limitReply(newToken, s, asked, "We've covered a lot — the team can take it from here. "
                    + "Leave your details and they'll reply by email.", ruleType);
        }
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        if (messageRepository.countByRoleSince(SiteChatMessage.ASSISTANT, dayStart) >= dailyAnswerCap) {
            log.warn("[SITE-CHAT] daily answer cap {} reached — offering a person instead", dailyAnswerCap);
            return limitReply(newToken, s, asked, "I can't answer more questions today, but the team can. "
                    + "Leave your details and they'll reply by email.", ruleType);
        }

        // ── retrieve: the public website only ───────────────────────────────
        RetrievalService.RetrievalResult rag = retrievalService.retrieve(text, null, WEB, TOP_K);
        Map<String, Citation> labels = new LinkedHashMap<>();       // W1 -> citation
        Map<String, String> labelBySource = new HashMap<>();         // "PAGE:12" -> W1
        Map<Long, String> pageUrls = pageUrls(rag);
        Map<Long, String> postUrls = postUrls(rag);
        StringBuilder sources = new StringBuilder();
        for (RetrievalService.RetrievedChunk c : rag.chunks()) {
            String key = c.sourceType().name() + ":" + c.sourceId();
            String url = c.sourceType() == ChunkSourceType.WEBSITE_PAGE ? pageUrls.get(c.sourceId()) : postUrls.get(c.sourceId());
            if (url == null) continue;                    // a page since removed: never cite a dead link
            String label = labelBySource.computeIfAbsent(key, k -> "W" + (labelBySource.size() + 1));
            labels.putIfAbsent(label, new Citation(label, c.sourceRef(), url));
            sources.append('[').append(label).append("] ").append(c.sourceRef() == null ? "" : c.sourceRef())
                   .append(" — ").append(url).append('\n')
                   .append(injectionScanner.scan(trim(c.content(), 1800)).sanitised()).append("\n\n");
        }
        String sourcesBlock = sources.length() == 0 ? "(no page of the website matched this question)"
                : injectionScanner.wrapUntrusted("website-pages", sources.toString().trim());

        // ── the model ───────────────────────────────────────────────────────
        String answer;
        Boolean confident;
        boolean aiSuggests = false;
        String aiType = null, aiReason = null;
        Long interactionId = null;
        try {
            AiChatService.AiResult r = chatService.completeJson(
                    AiChatService.AiCall.of("site.answer", TaskType.SITE_ASSISTANT_ANSWER)
                            .tenant(null)
                            .entity("SITE_CHAT", s.getId())
                            .chunks(rag.chunkIds())
                            .var("question", injectionScanner.scan(text).sanitised())
                            .var("history", history(s.getId(), asked.getId()))
                            .var("page", req.page() == null || req.page().isBlank() ? "the home page" : trim(req.page(), 200))
                            .var("today", LocalDate.now().toString())
                            .var("sources", sourcesBlock));
            interactionId = r.interactionId();
            JsonNode json = r.json();
            answer = json == null ? r.content() : json.path("answer").asText(r.content());
            confident = json == null || !json.has("confident") || json.path("confident").asBoolean(true);
            JsonNode h = json == null ? null : json.path("handover");
            if (h != null && h.isObject()) {
                aiSuggests = h.path("suggest").asBoolean(false);
                aiType = h.path("type").asText(null);
                aiReason = h.path("reason").asText(null);
            }
        } catch (Exception ex) {
            log.warn("[SITE-CHAT] answer failed for session {}: {}", s.getId(), ex.getMessage());
            answer = "I can't answer that right now. The team can — leave your details and they'll reply by email.";
            confident = null;                 // a fault, not a gap in the website's content
            aiSuggests = true;
            aiReason = "assistant unavailable";
        }
        answer = BritishSpelling.text(answer == null ? "" : answer.trim());

        // Cited labels in order of first use; unknown labels removed from the text.
        List<Citation> cited = new ArrayList<>();
        Matcher m = LABEL.matcher(answer);
        StringBuilder cleaned = new StringBuilder();
        while (m.find()) {
            Citation c = labels.get(m.group(1));
            if (c != null && !cited.contains(c)) cited.add(c);
            m.appendReplacement(cleaned, c == null ? "" : Matcher.quoteReplacement(m.group(0)));
        }
        m.appendTail(cleaned);
        answer = cleaned.toString().replaceAll("[ \\t]+([.,;:])", "$1").trim();
        if (answer.isEmpty()) {
            answer = "I don't have that on the website. The team can answer it — leave your details and they'll reply by email.";
            confident = false;
        }

        // ── handover decision ───────────────────────────────────────────────
        int streak = Boolean.FALSE.equals(confident) ? s.getUnansweredStreak() + 1 : 0;
        s.setUnansweredStreak(streak);
        sessionRepository.save(s);
        boolean suggest = ruleType != null || aiSuggests || streak >= 2;
        String type = ruleType != null ? ruleType : HANDOVER_TYPES.contains(up(aiType)) ? up(aiType) : "GENERAL";
        String reason = ruleType != null ? "asked for something the team handles"
                : streak >= 2 ? "could not answer twice" : aiReason;

        SiteChatMessage reply = messageRepository.save(SiteChatMessage.builder()
                .sessionId(s.getId()).role(SiteChatMessage.ASSISTANT).body(answer)
                .citationsJson(json(cited)).confident(confident).handoverSuggested(suggest)
                .inReplyTo(asked.getId()).interactionId(interactionId).build());

        return new AskResponse(newToken, s.getStatus(), null, view(reply, cited, null),
                new Handover(suggest, type, reason), null);
    }

    private AskResponse limitReply(String newToken, SiteChatSession s, SiteChatMessage asked, String text, String ruleType) {
        SiteChatMessage reply = messageRepository.save(SiteChatMessage.builder()
                .sessionId(s.getId()).role(SiteChatMessage.ASSISTANT).body(text)
                .handoverSuggested(true).inReplyTo(asked.getId()).build());
        return new AskResponse(newToken, s.getStatus(), null, view(reply, List.of(), null),
                new Handover(true, ruleType == null ? "GENERAL" : ruleType, "limit"), null);
    }

    /** The handover type the visitor's own words call for, or null. */
    static String ruleHandoverType(String text) {
        if (AUDIT_FIRM.matcher(text).find()) return "AUDIT_FIRM";
        if (SUPPORT.matcher(text).find()) return "SUPPORT";
        if (WANTS_DEMO.matcher(text).find()) return "DEMO";
        if (SALES_ONLY.matcher(text).find()) {
            String t = text.toLowerCase(Locale.ROOT);
            return t.contains("partner") || t.contains("resell") ? "PARTNERSHIP" : "CONTACT_SALES";
        }
        if (WANTS_PERSON.matcher(text).find()) return "GENERAL";
        return null;
    }

    // ══════════════════════ PUBLIC: HANDOVER ══════════════════════════════════

    public HandoverResponse handover(HandoverRequest req, String token, String ip, String userAgent) {
        SiteChatSession s = requireSession(token);
        if (req == null) throw bad("fullName", "Please tell us your name.");
        if (req.website() != null && !req.website().isBlank()) {
            return new HandoverResponse(SiteChatSession.HANDED_OVER, null,
                    "Thanks — we'll be in touch within one working day.");
        }
        if (SiteChatSession.HANDED_OVER.equals(s.getStatus())) {
            String ref = reference(s.getEnquiryId());
            return new HandoverResponse(s.getStatus(), ref,
                    "You're already with the team" + (ref == null ? "" : " (" + ref + ")") + ". They'll reply here and by email.");
        }
        if (!Boolean.TRUE.equals(req.privacyAccepted())) {
            throw new BusinessException("ENQUIRY_INVALID", "Please confirm we may use your details to reply.",
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    Map.of("fieldErrors", Map.of("privacyAccepted", "Please confirm we may use your details to reply.")));
        }
        String ipHash = enquiryService.hashIp(ip);
        if (!rateLimiter.allow("chat:handover:" + ipHash, 5, Duration.ofMinutes(10))) {
            throw new BusinessException("ENQUIRY_RATE_LIMITED",
                    "Too many requests from your network. Please try again in a few minutes.", HttpStatus.TOO_MANY_REQUESTS);
        }

        String chosen = HANDOVER_TYPES.contains(up(req.type())) ? up(req.type()) : "GENERAL";
        List<SiteChatMessage> msgs = messageRepository.findRecentBySession(s.getId(), 30);

        // The team reads a summary, not the scroll. The model writes it; if it
        // cannot, the visitor's own questions stand in.
        String summary = null, topic = null, type = chosen, priority = "NORMAL";
        try {
            AiChatService.AiResult r = chatService.completeJson(
                    AiChatService.AiCall.of("site.handover", TaskType.SITE_ASSISTANT_SUMMARY)
                            .tenant(null).entity("SITE_CHAT", s.getId())
                            .var("transcript", injectionScanner.wrapUntrusted("website-chat", transcript(msgs)))
                            .var("chosenType", chosen));
            JsonNode j = r.json();
            if (j != null) {
                summary = blankToNull(j.path("summary").asText(null));
                topic = blankToNull(j.path("topic").asText(null));
                String t = up(j.path("type").asText(null));
                if (HANDOVER_TYPES.contains(t)) type = t;
                String p = up(j.path("priority").asText(null));
                if (List.of("LOW", "NORMAL", "HIGH").contains(p)) priority = p;
            }
        } catch (Exception ex) {
            log.warn("[SITE-CHAT] handover summary failed for session {}: {}", s.getId(), ex.getMessage());
        }
        List<String> questions = msgs.stream().filter(x -> SiteChatMessage.VISITOR.equals(x.getRole()))
                .map(SiteChatMessage::getBody).toList();
        if (summary == null) {
            summary = questions.isEmpty() ? "The visitor asked to talk to the team."
                    : "The visitor asked:\n" + questions.stream().map(q -> "- " + trim(q, 300)).collect(Collectors.joining("\n"));
        }
        if (topic == null && !questions.isEmpty()) topic = trim(questions.get(questions.size() - 1), 60);
        summary = BritishSpelling.text(summary);

        String note = req.note() == null ? "" : req.note().trim();
        EnquirySubmitRequest e = new EnquirySubmitRequest();
        e.setType(type);
        e.setFullName(req.fullName());
        e.setEmail(req.email());
        e.setCompany(req.company());
        e.setSubject(trim("Website chat" + (topic == null ? "" : ": " + topic), 200));
        e.setMessage(trim(summary + (note.isEmpty() ? "" : "\n\nTheir note: " + note), 5000));
        e.setSourcePage(trim(req.page() != null && !req.page().isBlank() ? req.page() : s.getLastPage(), 512));
        e.setLandingPage(s.getLandingPage());
        e.setReferrer(s.getReferrer());
        e.setUtmSource(s.getUtmSource());
        e.setUtmMedium(s.getUtmMedium());
        e.setUtmCampaign(s.getUtmCampaign());
        e.setUtmTerm(s.getUtmTerm());
        e.setUtmContent(s.getUtmContent());
        e.setMarketingConsent(Boolean.TRUE.equals(req.marketingConsent()));

        Map<String, Object> created = enquiryService.submitFromChat(e, priority, ipHash, userAgent);

        s.setStatus(SiteChatSession.HANDED_OVER);
        s.setEnquiryId(((Number) created.get("id")).longValue());
        s.setSummary(summary);
        s.setTopic(trim(topic, 100));
        s.setHandedOverAt(LocalDateTime.now());
        sessionRepository.save(s);
        log.info("[SITE-CHAT] handed over | session={} enquiry={} type={}", s.getId(), created.get("reference"), type);
        return new HandoverResponse(s.getStatus(), String.valueOf(created.get("reference")), String.valueOf(created.get("message")));
    }

    // ══════════════════════ PUBLIC: THE CONVERSATION ══════════════════════════

    @Transactional(readOnly = true)
    public SessionView session(String token) {
        SiteChatSession s = requireSession(token);
        return new SessionView(s.getStatus(), reference(s.getEnquiryId()),
                views(messageRepository.findBySession(s.getId()), true));
    }

    // ══════════════════════ ADMIN ═════════════════════════════════════════════

    @Transactional(readOnly = true)
    public AdminChatView adminChat(Long enquiryId) {
        SiteChatSession s = sessionRepository.findByEnquiryId(enquiryId).orElseThrow(() ->
                new BusinessException("SITE_CHAT_NOT_FOUND", "This enquiry did not come from the website chat.", HttpStatus.NOT_FOUND));
        return new AdminChatView(s.getId(), s.getStatus(), s.getSummary(), s.getTopic(), s.getCreatedAt(),
                s.getHandedOverAt(), s.getFirstPage(), s.getLastPage(), views(messageRepository.findBySession(s.getId()), false));
    }

    /** The team's reply: shown in the visitor's chat and emailed to them (after commit). */
    @Transactional
    public AdminChatView reply(Long enquiryId, String body, Long actorId) {
        String text = body == null ? "" : body.trim();
        if (text.isEmpty()) throw bad("body", "Write a reply first.");
        if (text.length() > 5000) throw bad("body", "Please keep the reply under 5,000 characters.");
        SiteChatSession s = sessionRepository.findByEnquiryId(enquiryId).orElseThrow(() ->
                new BusinessException("SITE_CHAT_NOT_FOUND", "This enquiry did not come from the website chat.", HttpStatus.NOT_FOUND));
        SiteChatMessage msg = messageRepository.save(SiteChatMessage.builder()
                .sessionId(s.getId()).role(SiteChatMessage.STAFF).body(text).authorId(actorId).build());
        s.setLastMessageAt(LocalDateTime.now());
        sessionRepository.save(s);
        enquiryService.recordChatReply(enquiryId, text, actorId);
        events.publishEvent(new SiteChatEvents.StaffReplied(enquiryId, msg.getId()));
        return adminChat(enquiryId);
    }

    /** What visitors asked that the website could not answer: the content backlog. */
    @Transactional(readOnly = true)
    public List<UnansweredItem> unanswered(int days, int limit) {
        LocalDateTime since = LocalDateTime.now().minusDays(Math.max(1, Math.min(days, 365)));
        List<SiteChatMessage> answers = messageRepository.findUnconfidentSince(since, Math.max(1, Math.min(limit, 500)));
        Map<Long, SiteChatMessage> questions = new HashMap<>();
        messageRepository.findAllById(answers.stream().map(SiteChatMessage::getInReplyTo).filter(Objects::nonNull)
                .collect(Collectors.toSet())).forEach(q -> questions.put(q.getId(), q));
        Map<Long, SiteChatSession> sessions = new HashMap<>();
        sessionRepository.findAllById(answers.stream().map(SiteChatMessage::getSessionId).collect(Collectors.toSet()))
                .forEach(x -> sessions.put(x.getId(), x));
        List<UnansweredItem> out = new ArrayList<>();
        for (SiteChatMessage a : answers) {
            SiteChatMessage q = questions.get(a.getInReplyTo());
            if (q == null) continue;
            SiteChatSession s = sessions.get(a.getSessionId());
            out.add(new UnansweredItem(a.getId(), q.getBody(), a.getBody(),
                    s == null ? null : s.getLastPage(), a.getCreatedAt(), a.getSessionId(),
                    s == null ? null : s.getEnquiryId()));
        }
        return out;
    }

    // ══════════════════════ helpers ═══════════════════════════════════════════

    private SiteChatSession requireSession(String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException("SITE_CHAT_NOT_FOUND", "This conversation has ended. Start a new one.", HttpStatus.NOT_FOUND);
        }
        return sessionRepository.findByTokenHash(sha256(token.trim())).orElseThrow(() ->
                new BusinessException("SITE_CHAT_NOT_FOUND", "This conversation has ended. Start a new one.", HttpStatus.NOT_FOUND));
    }

    private String history(Long sessionId, Long currentId) {
        List<SiteChatMessage> recent = messageRepository.findRecentBySession(sessionId, HISTORY_TURNS + 1).stream()
                .filter(x -> !x.getId().equals(currentId)).toList();
        if (recent.isEmpty()) return "(this is the first message)";
        return injectionScanner.wrapUntrusted("conversation", recent.stream()
                .map(x -> label(x.getRole()) + ": " + trim(x.getBody(), 600))
                .collect(Collectors.joining("\n")));
    }

    private String transcript(List<SiteChatMessage> msgs) {
        return msgs.stream().map(x -> label(x.getRole()) + ": " + trim(x.getBody(), 800))
                .collect(Collectors.joining("\n"));
    }

    private static String label(String role) {
        return SiteChatMessage.VISITOR.equals(role) ? "Visitor"
                : SiteChatMessage.STAFF.equals(role) ? "KashiGuard team" : "Assistant";
    }

    private Map<Long, String> pageUrls(RetrievalService.RetrievalResult rag) {
        Set<Long> ids = rag.chunks().stream().filter(c -> c.sourceType() == ChunkSourceType.WEBSITE_PAGE)
                .map(RetrievalService.RetrievedChunk::sourceId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> m = new HashMap<>();
        if (ids.isEmpty()) return m;
        for (SiteChatSourcePage p : pageRepository.findAllById(ids)) {
            if (Boolean.TRUE.equals(p.getActive())) m.put(p.getId(), base() + p.getPath());
        }
        return m;
    }

    private Map<Long, String> postUrls(RetrievalService.RetrievalResult rag) {
        Set<Long> ids = rag.chunks().stream().filter(c -> c.sourceType() == ChunkSourceType.WEBSITE_POST)
                .map(RetrievalService.RetrievedChunk::sourceId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> m = new HashMap<>();
        if (ids.isEmpty()) return m;
        for (Post p : postRepository.findAllById(ids)) {
            if (p.getStatus() != com.kashi.grc.content.domain.ContentEnums.PostStatus.PUBLISHED) continue;
            m.put(p.getId(), postUrl(p, base()));
        }
        return m;
    }

    /** The website's URL contract: pillar guides under /resources, every other post under /blog. */
    static String postUrl(Post p, String base) {
        return base + (p.getContentType() == ContentType.PILLAR ? "/resources/" : "/blog/") + p.getSlug();
    }

    private String base() { return siteUrl == null ? "" : siteUrl.replaceAll("/+$", ""); }

    private String reference(Long enquiryId) {
        return enquiryId == null ? null : enquiryRepository.findById(enquiryId).map(Enquiry::getReference).orElse(null);
    }

    private List<MessageView> views(List<SiteChatMessage> msgs, boolean forVisitor) {
        Set<Long> staff = msgs.stream().map(SiteChatMessage::getAuthorId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> names = new HashMap<>();
        if (!staff.isEmpty()) {
            userRepository.findAllById(staff).forEach(u -> {
                String first = u.getFirstName() == null ? "" : u.getFirstName().trim();
                String last = u.getLastName() == null ? "" : u.getLastName().trim();
                // The visitor sees a first name only; the team sees the full name.
                names.put(u.getId(), forVisitor ? (first.isEmpty() ? "KashiGuard team" : first + ", KashiGuard team")
                        : (first + " " + last).trim());
            });
        }
        return msgs.stream().map(x -> view(x, citations(x.getCitationsJson()),
                x.getAuthorId() == null ? null : names.getOrDefault(x.getAuthorId(), "KashiGuard team"))).toList();
    }

    private MessageView view(SiteChatMessage m, List<Citation> citations, String authorName) {
        return new MessageView(m.getId(), m.getRole(), m.getBody(), citations,
                m.getHandoverSuggested(), authorName, m.getCreatedAt());
    }

    private List<Citation> citations(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return mapper.readValue(json, new TypeReference<List<Citation>>() {}); }
        catch (Exception e) { return List.of(); }
    }

    private String json(Object o) {
        try { return mapper.writeValueAsString(o); } catch (Exception e) { return null; }
    }

    private static String newToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static BusinessException bad(String field, String message) {
        return new BusinessException("SITE_CHAT_INVALID", message, HttpStatus.UNPROCESSABLE_ENTITY,
                Map.of("fieldErrors", Map.of(field, message)));
    }

    private static String up(String s) { return s == null ? "" : s.trim().toUpperCase(Locale.ROOT); }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    private static String trim(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
