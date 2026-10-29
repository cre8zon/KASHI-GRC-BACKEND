package com.kashi.grc.enquiry.service;

import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.enquiry.domain.Enquiry;
import com.kashi.grc.enquiry.domain.EnquiryActivity;
import com.kashi.grc.enquiry.dto.EnquirySubmitRequest;
import com.kashi.grc.enquiry.repository.EnquiryActivityRepository;
import com.kashi.grc.enquiry.repository.EnquiryRepository;
import com.kashi.grc.enquiry.repository.EnquiryRepositoryCustom.Filter;
import com.kashi.grc.tenant.domain.Tenant;
import com.kashi.grc.tenant.repository.TenantRepository;
import com.kashi.grc.tenant.service.TenantProvisionedEvent;
import com.kashi.grc.usermanagement.domain.User;
import com.kashi.grc.usermanagement.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Website enquiries: the public submission, the platform inbox, and the link
 * from an enquiry to the tenant it became.
 *
 * ── THE PUBLIC ENDPOINT ──────────────────────────────────────────────────────
 * Unauthenticated and on the open internet, so:
 *   • honeypot — a filled hidden field is answered normally and dropped;
 *   • rate limit — 5 per IP per 10 minutes (429), and the same form sent again
 *     from the same email within the hour is added to the open enquiry as a
 *     note instead of opening a second row;
 *   • the response never says whether the email is a customer or has written
 *     before — matching to an existing tenant is shown only in the inbox.
 *
 * ── STATUSES ─────────────────────────────────────────────────────────────────
 *   NEW → IN_PROGRESS → QUALIFIED → CONVERTED   (sales types)
 *   NEW → IN_PROGRESS → CLOSED (RESOLVED)       (support)
 * CLOSED needs a reason. CONVERTED is never set by hand: it is set when a
 * tenant is provisioned from the enquiry (TenantProvisionedEvent, same
 * transaction) or when the enquiry is linked to an existing tenant.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnquiryService {

    /** Platform permissions — read the inbox / act on enquiries. Granted through roles, never by role name. */
    public static final String PERM_VIEW   = "platform:enquiries:view";
    public static final String PERM_MANAGE = "platform:enquiries:manage";

    public static final List<String> TYPES = List.of(
            "DEMO", "TRIAL", "CONTACT_SALES", "AUDIT_FIRM", "PARTNERSHIP", "SUPPORT", "GENERAL",
            "WORKFLOW_REQUEST");
    public static final List<String> STATUSES = List.of("NEW", "IN_PROGRESS", "QUALIFIED", "CONVERTED", "CLOSED");
    public static final List<String> OPEN = List.of("NEW", "IN_PROGRESS", "QUALIFIED");
    public static final List<String> PRIORITIES = List.of("LOW", "NORMAL", "HIGH");
    public static final List<String> CLOSE_REASONS = List.of(
            "RESOLVED", "NOT_A_FIT", "NO_RESPONSE", "DUPLICATE", "SPAM", "LOST_TO_COMPETITOR", "BUDGET", "OTHER");
    /** Types that can end in a tenant. */
    public static final Set<String> CONVERTIBLE = Set.of("DEMO", "TRIAL", "CONTACT_SALES", "AUDIT_FIRM", "PARTNERSHIP", "GENERAL");

    private static final Set<String> BANDS = Set.of("1-50", "51-200", "201-1000", "1001-5000", "5000+");
    private static final String REF_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";   // no 0/O/1/I
    private static final SecureRandom RANDOM = new SecureRandom();

    private final EnquiryRepository         enquiryRepository;
    private final EnquiryActivityRepository activityRepository;
    private final EnquiryRateLimiter        rateLimiter;
    private final UserRepository            userRepository;
    private final TenantRepository          tenantRepository;
    private final ApplicationEventPublisher events;

    /** Salt for ip_hash, so the stored hash cannot be reversed by hashing the IPv4 space. */
    @Value("${kashi.enquiries.ip-salt:change-me}")
    private String ipSalt;

    // ══════════════════════ PUBLIC SUBMISSION ═════════════════════════════════

    @Transactional
    public Map<String, Object> submit(EnquirySubmitRequest r, String ip, String userAgent) {
        String type = r.getType() == null ? "DEMO" : r.getType().trim().toUpperCase();
        if (!TYPES.contains(type)) type = "GENERAL";

        // Bots: same answer, nothing stored.
        if (r.getWebsite() != null && !r.getWebsite().isBlank()) {
            log.info("[ENQUIRY] honeypot hit | type={}", type);
            return accepted(newReference(), type);
        }

        String ipHash = hash(ip);
        if (!rateLimiter.allow("ip:" + ipHash, 5, Duration.ofMinutes(10))) {
            throw new BusinessException("ENQUIRY_RATE_LIMITED",
                    "Too many requests from your network. Please try again in a few minutes.", HttpStatus.TOO_MANY_REQUESTS);
        }

        Map<String, String> errors = validate(r, type);
        if (!errors.isEmpty()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("fieldErrors", errors);
            throw new BusinessException("ENQUIRY_INVALID",
                    errors.size() == 1 ? errors.values().iterator().next() : "Please check the highlighted fields.",
                    HttpStatus.UNPROCESSABLE_ENTITY, details);
        }

        String email = r.getEmail().trim().toLowerCase();
        Optional<Enquiry> open = enquiryRepository.findFirstByEmailAndStatusInOrderByIdDesc(email, OPEN);

        // The same person pressing submit again inside the hour: add to what is
        // already open rather than opening a second thread for the same request.
        if (open.isPresent() && open.get().getEnquiryType().equals(type)
                && open.get().getCreatedAt() != null
                && open.get().getCreatedAt().isAfter(LocalDateTime.now().minusHours(1))) {
            Enquiry e = open.get();
            activity(e, "NOTE", "Submitted the " + label(type) + " form again"
                    + (blank(r.getMessage()) ? "." : ":\n" + trim(r.getMessage(), 5000)), null, null, null);
            touch(e);
            enquiryRepository.save(e);
            return accepted(e.getReference(), type);
        }

        Enquiry e = Enquiry.builder()
                .reference(uniqueReference())
                .enquiryType(type)
                .priority("NORMAL")
                .fullName(trim(r.getFullName(), 150))
                .email(email)
                .phone(trim(r.getPhone(), 40))
                .company(trim(r.getCompany(), 200))
                .jobTitle(trim(r.getJobTitle(), 150))
                .country(blank(r.getCountry()) || r.getCountry().trim().length() != 2 ? null : r.getCountry().trim().toUpperCase())
                .employeeBand(band(r.getEmployeeBand()))
                .vendorCount(r.getVendorCount() == null || r.getVendorCount() < 0 ? null : r.getVendorCount())
                .planCode(blank(r.getPlanCode()) ? null : trim(r.getPlanCode().trim().toUpperCase(), 50))
                .topic(trim(r.getTopic(), 60))
                .subject(trim(r.getSubject(), 200))
                .message(trim(r.getMessage(), 5000))
                .heardAbout(trim(r.getHeardAbout(), 255))
                .sourcePage(trim(r.getSourcePage(), 512))
                .landingPage(trim(r.getLandingPage(), 512))
                .referrer(trim(r.getReferrer(), 512))
                .utmSource(trim(r.getUtmSource(), 100))
                .utmMedium(trim(r.getUtmMedium(), 100))
                .utmCampaign(trim(r.getUtmCampaign(), 150))
                .utmTerm(trim(r.getUtmTerm(), 150))
                .utmContent(trim(r.getUtmContent(), 150))
                .referralCode(blank(r.getReferralCode()) ? null : trim(r.getReferralCode().trim().toUpperCase(), 50))
                .marketingConsent(Boolean.TRUE.equals(r.getMarketingConsent()))
                .consentAt(Boolean.TRUE.equals(r.getMarketingConsent()) ? LocalDateTime.now() : null)
                .ipHash(ipHash)
                .userAgent(trim(userAgent, 255))
                .duplicateOfId(open.map(Enquiry::getId).orElse(null))
                .lastActivityAt(LocalDateTime.now())
                .build();

        // An existing customer writing in (usually support): shown in the inbox only.
        userRepository.findByEmail(email).map(User::getTenantId).ifPresent(e::setMatchedTenantId);

        enquiryRepository.save(e);
        activity(e, "RECEIVED", "Submitted from " + (e.getSourcePage() == null ? "the website" : e.getSourcePage()),
                null, "NEW", null);
        events.publishEvent(new EnquiryEvents.Received(e.getId()));
        log.info("[ENQUIRY] received | ref={} type={} plan={} source={}", e.getReference(), type, e.getPlanCode(), e.getUtmSource());
        return accepted(e.getReference(), type);
    }

    // ══════════════════════ SIGNED-IN: CUSTOM WORKFLOW REQUEST ════════════════

    /**
     * A customer asking for a workflow the catalogue does not have.
     *
     * Lands in the same inbox as website enquiries, as WORKFLOW_REQUEST, already
     * matched to the customer's tenant — so it is quoted and tracked like any
     * other lead. Who asked comes from the session, never the payload.
     */
    @Transactional
    public Map<String, Object> submitWorkflowRequest(User requester, String entityType, String moduleLabel,
                                                     String startFrom, String neededBy, String message) {
        if (requester == null) throw bad("requester", "Sign in to request a workflow.");
        if (blank(message) || message.trim().length() < 10)
            throw bad("message", "Describe what the workflow should do (at least a sentence).");

        String company = requester.getTenantId() == null ? null
                : tenantRepository.findById(requester.getTenantId()).map(Tenant::getName).orElse(null);
        String module = blank(moduleLabel) ? humanise(blank(entityType) ? "GENERAL" : entityType.trim().toUpperCase()) : moduleLabel.trim();

        StringBuilder body = new StringBuilder();
        body.append("Module: ").append(module);
        if (!blank(startFrom)) body.append("\nStart from: ").append(trim(startFrom, 255));
        if (!blank(neededBy))  body.append("\nNeeded by: ").append(trim(neededBy, 40));
        body.append("\n\n").append(message.trim());

        Enquiry e = Enquiry.builder()
                .reference(uniqueReference())
                .enquiryType("WORKFLOW_REQUEST")
                .priority("NORMAL")
                .fullName(trim(blank(requester.getFullName()) ? requester.getEmail() : requester.getFullName(), 150))
                .email(requester.getEmail() == null ? null : requester.getEmail().trim().toLowerCase())
                .company(trim(company, 200))
                .topic(trim(blank(entityType) ? null : entityType.trim().toUpperCase(), 60))
                .subject(trim("Custom workflow for " + module, 200))
                .message(trim(body.toString(), 5000))
                .sourcePage("/workflows/catalogue")
                .matchedTenantId(requester.getTenantId())
                .lastActivityAt(LocalDateTime.now())
                .build();
        enquiryRepository.save(e);
        activity(e, "RECEIVED", "Requested from the workflow catalogue", null, "NEW", requester.getId());
        events.publishEvent(new EnquiryEvents.Received(e.getId()));
        log.info("[ENQUIRY] workflow request | ref={} tenant={} module={}", e.getReference(), requester.getTenantId(), module);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reference", e.getReference());
        m.put("message", "Thanks — we have your request as " + e.getReference()
                + ". We'll reply by email with a scope and quote within two working days.");
        return m;
    }

    private Map<String, Object> accepted(String reference, String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reference", reference);
        m.put("message", "SUPPORT".equals(type)
                ? "Thanks — we've logged your request as " + reference + " and will reply by email within one working day."
                : "Thanks — we'll be in touch within one working day. Your reference is " + reference + ".");
        return m;
    }

    private Map<String, String> validate(EnquirySubmitRequest r, String type) {
        Map<String, String> e = new LinkedHashMap<>();
        if (blank(r.getFullName()) || r.getFullName().trim().length() < 2) e.put("fullName", "Please tell us your name.");
        String email = r.getEmail() == null ? "" : r.getEmail().trim();
        if (!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$") || email.length() > 320) e.put("email", "Please enter a valid email address.");
        if ("SUPPORT".equals(type) && blank(r.getMessage())) e.put("message", "Please describe what you need help with.");
        if (!"SUPPORT".equals(type) && !"GENERAL".equals(type) && blank(r.getCompany())) e.put("company", "Please tell us your company.");
        if (r.getMessage() != null && r.getMessage().length() > 5000) e.put("message", "Please keep the message under 5,000 characters.");
        return e;
    }

    // ══════════════════════ INBOX ═════════════════════════════════════════════

    @Transactional(readOnly = true)
    public Map<String, Object> list(Collection<String> types, Collection<String> statuses, String q,
                                    Long assignedTo, int page, int size) {
        size = Math.min(Math.max(size, 1), 100);
        Filter f = new Filter(types, statuses, q, assignedTo, null, null);
        List<Enquiry> rows = enquiryRepository.search(f, page, size);
        long total = enquiryRepository.count(f);

        Map<Long, String> users = userNames(rows.stream().map(Enquiry::getAssignedTo).filter(Objects::nonNull).collect(Collectors.toSet()));
        Set<Long> tenantIds = new HashSet<>();
        rows.forEach(x -> { if (x.getConvertedTenantId() != null) tenantIds.add(x.getConvertedTenantId());
                            if (x.getMatchedTenantId() != null) tenantIds.add(x.getMatchedTenantId()); });
        Map<Long, String> tenants = tenantNames(tenantIds);

        Map<String, Object> pagination = new LinkedHashMap<>();
        pagination.put("page", page + 1);
        pagination.put("size", size);
        pagination.put("totalItems", total);
        pagination.put("totalPages", (int) Math.ceil(total / (double) size));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", rows.stream().map(x -> summary(x, users, tenants)).toList());
        out.put("pagination", pagination);
        out.put("countsByStatus", enquiryRepository.countBy("status", null));
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(Long id) {
        Enquiry e = require(id);
        List<EnquiryActivity> acts = activityRepository.findByEnquiryIdOrderByIdAsc(id);

        Set<Long> userIds = new HashSet<>();
        if (e.getAssignedTo() != null) userIds.add(e.getAssignedTo());
        if (e.getConvertedBy() != null) userIds.add(e.getConvertedBy());
        acts.stream().map(EnquiryActivity::getAuthorId).filter(Objects::nonNull).forEach(userIds::add);
        Map<Long, String> users = userNames(userIds);
        Set<Long> tIds = new HashSet<>();
        if (e.getConvertedTenantId() != null) tIds.add(e.getConvertedTenantId());
        if (e.getMatchedTenantId() != null) tIds.add(e.getMatchedTenantId());
        Map<Long, String> tenants = tenantNames(tIds);

        Map<String, Object> m = summary(e, users, tenants);
        m.put("phone", e.getPhone());
        m.put("jobTitle", e.getJobTitle());
        m.put("country", e.getCountry());
        m.put("employeeBand", e.getEmployeeBand());
        m.put("vendorCount", e.getVendorCount());
        m.put("topic", e.getTopic());
        m.put("subject", e.getSubject());
        m.put("message", e.getMessage());
        m.put("heardAbout", e.getHeardAbout());
        Map<String, Object> src = new LinkedHashMap<>();
        src.put("sourcePage", e.getSourcePage());
        src.put("landingPage", e.getLandingPage());
        src.put("referrer", e.getReferrer());
        src.put("utmSource", e.getUtmSource());
        src.put("utmMedium", e.getUtmMedium());
        src.put("utmCampaign", e.getUtmCampaign());
        src.put("utmTerm", e.getUtmTerm());
        src.put("utmContent", e.getUtmContent());
        src.put("referralCode", e.getReferralCode());
        src.put("referralTenant", e.getReferralCode() == null ? null
                : tenantRepository.findByCode(e.getReferralCode()).map(Tenant::getName).orElse(null));
        m.put("source", src);
        m.put("marketingConsent", e.isMarketingConsent());
        m.put("consentAt", e.getConsentAt());
        m.put("closedAt", e.getClosedAt());
        m.put("convertedAt", e.getConvertedAt());
        m.put("convertedByName", e.getConvertedBy() == null ? null : users.get(e.getConvertedBy()));
        m.put("firstResponseAt", e.getFirstResponseAt());
        m.put("duplicateOfId", e.getDuplicateOfId());
        m.put("activities", acts.stream().map(a -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", a.getId());
            x.put("kind", a.getKind());
            x.put("body", a.getBody());
            x.put("fromValue", a.getFromValue());
            x.put("toValue", a.getToValue());
            x.put("authorId", a.getAuthorId());
            x.put("authorName", a.getAuthorId() == null ? "Website" : users.getOrDefault(a.getAuthorId(), "User #" + a.getAuthorId()));
            x.put("createdAt", a.getCreatedAt());
            return x;
        }).toList());
        m.put("otherEnquiries", enquiryRepository.findByEmailOrderByIdDesc(e.getEmail()).stream()
                .filter(x -> !x.getId().equals(e.getId())).limit(10)
                .map(x -> Map.<String, Object>of("id", x.getId(), "reference", x.getReference(),
                        "type", x.getEnquiryType(), "status", x.getStatus(), "createdAt", x.getCreatedAt()))
                .toList());
        m.put("provisionDefaults", CONVERTIBLE.contains(e.getEnquiryType()) ? provisionDefaults(e) : null);
        return m;
    }

    /**
     * Status, priority, owner, type, plan and close reason. Each change is
     * written to the timeline. Moving out of NEW stamps the first response.
     */
    @Transactional
    public Map<String, Object> update(Long id, Map<String, Object> body, Long actorId) {
        Enquiry e = require(id);
        if (body.containsKey("status")) {
            String to = up(body.get("status"));
            if (!STATUSES.contains(to)) throw bad("status", "Unknown status " + to);
            if ("CONVERTED".equals(to)) {
                throw new BusinessException("ENQUIRY_CONVERT_VIA_TENANT",
                        "Use Convert to tenant (or Link existing tenant) — converted is set when the tenant exists.");
            }
            if (!to.equals(e.getStatus())) {
                if ("CONVERTED".equals(e.getStatus())) {
                    throw new BusinessException("ENQUIRY_ALREADY_CONVERTED", "This enquiry is already converted to a tenant.");
                }
                if ("CLOSED".equals(to)) {
                    String reason = up(body.get("closedReason"));
                    if (!CLOSE_REASONS.contains(reason)) throw bad("closedReason", "Choose why this is being closed.");
                    e.setClosedReason(reason);
                    e.setClosedAt(LocalDateTime.now());
                } else {
                    e.setClosedReason(null);
                    e.setClosedAt(null);
                }
                activity(e, "STATUS", "CLOSED".equals(to) ? "Closed — " + humanise(e.getClosedReason()) : null,
                        e.getStatus(), to, actorId);
                e.setStatus(to);
                firstResponse(e);
            }
        }
        if (body.containsKey("priority")) {
            String to = up(body.get("priority"));
            if (!PRIORITIES.contains(to)) throw bad("priority", "Priority is LOW, NORMAL or HIGH.");
            if (!to.equals(e.getPriority())) { activity(e, "PRIORITY", null, e.getPriority(), to, actorId); e.setPriority(to); }
        }
        if (body.containsKey("assignedTo")) {
            Long to = body.get("assignedTo") == null || body.get("assignedTo").toString().isBlank()
                    ? null : Long.valueOf(body.get("assignedTo").toString());
            if (!Objects.equals(to, e.getAssignedTo())) {
                Map<Long, String> n = userNames(new HashSet<>(Arrays.asList(to, e.getAssignedTo())));
                activity(e, "ASSIGNED", null, e.getAssignedTo() == null ? "—" : n.get(e.getAssignedTo()),
                        to == null ? "—" : n.get(to), actorId);
                e.setAssignedTo(to);
            }
        }
        if (body.containsKey("enquiryType")) {
            String to = up(body.get("enquiryType"));
            if (!TYPES.contains(to)) throw bad("enquiryType", "Unknown type " + to);
            if (!to.equals(e.getEnquiryType())) { activity(e, "STATUS", "Type changed", e.getEnquiryType(), to, actorId); e.setEnquiryType(to); }
        }
        if (body.containsKey("planCode")) {
            String to = body.get("planCode") == null ? null : up(body.get("planCode"));
            if (to != null && to.isBlank()) to = null;
            if (!Objects.equals(to, e.getPlanCode())) { activity(e, "STATUS", "Plan of interest changed", e.getPlanCode(), to, actorId); e.setPlanCode(to); }
        }
        touch(e);
        enquiryRepository.save(e);
        return get(id);
    }

    @Transactional
    public Map<String, Object> addNote(Long id, String body, Long actorId) {
        if (blank(body)) throw bad("body", "Write something first.");
        Enquiry e = require(id);
        activity(e, "NOTE", trim(body, 10_000), null, null, actorId);
        firstResponse(e);
        if ("NEW".equals(e.getStatus())) {
            activity(e, "STATUS", null, "NEW", "IN_PROGRESS", actorId);
            e.setStatus("IN_PROGRESS");
        }
        touch(e);
        enquiryRepository.save(e);
        return get(id);
    }

    /** The enquirer is already a customer (or was provisioned another way): link instead of creating. */
    @Transactional
    public Map<String, Object> linkTenant(Long id, Long tenantId, Long actorId) {
        Enquiry e = require(id);
        Tenant t = tenantRepository.findById(tenantId).orElseThrow(() -> new ResourceNotFoundException("Tenant", tenantId));
        markConverted(e, t.getId(), t.getName(), actorId, "Linked to existing tenant " + t.getName());
        return get(id);
    }

    /**
     * Provisioning from the inbox. Runs inside TenantProvisioningService's
     * transaction: if the enquiry cannot be converted the tenant is not created.
     */
    @EventListener
    public void onTenantProvisioned(TenantProvisionedEvent ev) {
        if (ev.enquiryId() == null) return;
        Enquiry e = enquiryRepository.findById(ev.enquiryId())
                .orElseThrow(() -> new ResourceNotFoundException("Enquiry", ev.enquiryId()));
        markConverted(e, ev.tenantId(), ev.tenantName(), ev.actorId(),
                "Converted to tenant " + ev.tenantName() + " on " + ev.planCode());
    }

    private void markConverted(Enquiry e, Long tenantId, String tenantName, Long actorId, String note) {
        if ("CONVERTED".equals(e.getStatus()) && !Objects.equals(e.getConvertedTenantId(), tenantId)) {
            throw new BusinessException("ENQUIRY_ALREADY_CONVERTED",
                    "Enquiry " + e.getReference() + " is already converted to another tenant.", HttpStatus.CONFLICT);
        }
        if (!CONVERTIBLE.contains(e.getEnquiryType())) {
            throw new BusinessException("ENQUIRY_NOT_CONVERTIBLE", "Support requests are not converted to tenants.");
        }
        activity(e, "CONVERTED", note, e.getStatus(), "CONVERTED", actorId);
        e.setStatus("CONVERTED");
        e.setConvertedTenantId(tenantId);
        e.setConvertedAt(LocalDateTime.now());
        e.setConvertedBy(actorId);
        e.setClosedReason(null);
        e.setClosedAt(null);
        firstResponse(e);
        touch(e);
        enquiryRepository.save(e);
        log.info("[ENQUIRY] converted | ref={} tenant={} ({})", e.getReference(), tenantId, tenantName);
    }

    /** Sidebar badge: enquiries nobody has picked up yet. */
    @Transactional(readOnly = true)
    public long badge() { return enquiryRepository.countByStatus("NEW"); }

    /** Funnel for the inbox header: last {@code days} days. */
    /**
     * Funnel for the inbox header: last {@code days} days. Every figure is an
     * aggregate query (counts grouped in the database), so this costs the same
     * with ten enquiries as with ten thousand — only the response-time list
     * reads rows, and only two columns of them.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> stats(int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(Math.max(1, days));
        long received  = enquiryRepository.count(new Filter(null, null, null, null, since, null));
        long sales     = enquiryRepository.count(new Filter(CONVERTIBLE, null, null, null, since, null));
        long converted = enquiryRepository.count(new Filter(null, List.of("CONVERTED"), null, null, since, null));
        List<Long> responseMinutes = enquiryRepository.responseTimes(since).stream()
                .filter(x -> x[0] != null && x[1] != null)
                .map(x -> Duration.between(x[0], x[1]).toMinutes()).sorted().toList();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("days", days);
        m.put("received", received);
        m.put("byType", enquiryRepository.countBy("enquiryType", since));
        m.put("salesEnquiries", sales);
        m.put("converted", converted);
        m.put("conversionRate", sales == 0 ? null : Math.round(converted * 1000.0 / sales) / 10.0);
        m.put("medianFirstResponseMinutes", responseMinutes.isEmpty() ? null : responseMinutes.get(responseMinutes.size() / 2));
        m.put("bySource", enquiryRepository.countBySource(since));
        return m;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> mailView(Long id) {
        Enquiry e = require(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reference", e.getReference());
        m.put("type", e.getEnquiryType());
        m.put("typeLabel", label(e.getEnquiryType()));
        m.put("fullName", e.getFullName());
        m.put("email", e.getEmail());
        m.put("company", e.getCompany());
        m.put("planCode", e.getPlanCode());
        m.put("message", e.getMessage());
        m.put("source", e.getReferralCode() != null ? "Referral " + e.getReferralCode()
                : e.getUtmSource() != null ? e.getUtmSource() + (e.getUtmCampaign() == null ? "" : " / " + e.getUtmCampaign())
                : e.getSourcePage());
        m.put("existingCustomer", e.getMatchedTenantId() != null);
        m.put("duplicate", e.getDuplicateOfId() != null);
        return m;
    }

    // ── views ────────────────────────────────────────────────────────────────

    private Map<String, Object> summary(Enquiry e, Map<Long, String> users, Map<Long, String> tenants) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("reference", e.getReference());
        m.put("type", e.getEnquiryType());
        m.put("status", e.getStatus());
        m.put("priority", e.getPriority());
        m.put("fullName", e.getFullName());
        m.put("email", e.getEmail());
        m.put("company", e.getCompany());
        m.put("planCode", e.getPlanCode());
        m.put("utmSource", e.getUtmSource());
        m.put("referralCode", e.getReferralCode());
        m.put("assignedTo", e.getAssignedTo());
        m.put("assignedToName", e.getAssignedTo() == null ? null : users.get(e.getAssignedTo()));
        m.put("closedReason", e.getClosedReason());
        m.put("matchedTenantId", e.getMatchedTenantId());
        m.put("matchedTenantName", e.getMatchedTenantId() == null ? null : tenants.get(e.getMatchedTenantId()));
        m.put("convertedTenantId", e.getConvertedTenantId());
        m.put("convertedTenantName", e.getConvertedTenantId() == null ? null : tenants.get(e.getConvertedTenantId()));
        m.put("isDuplicate", e.getDuplicateOfId() != null);
        m.put("createdAt", e.getCreatedAt());
        m.put("lastActivityAt", e.getLastActivityAt());
        return m;
    }

    /**
     * What Create Tenant is pre-filled with. Kept here so the channel rules live
     * in one place: an audit firm's ?ref= link is the audit-firm channel, paid
     * UTM mediums are paid ads, everything else from the site is inbound.
     */
    private Map<String, Object> provisionDefaults(Enquiry e) {
        String[] parts = e.getFullName() == null ? new String[]{""} : e.getFullName().trim().split("\\s+", 2);
        String medium = e.getUtmMedium() == null ? "" : e.getUtmMedium().toLowerCase();
        String channel = e.getReferralCode() != null ? "AUDIT_FIRM_CHANNEL"
                : Set.of("cpc", "ppc", "paid", "paidsocial", "paid_social", "display", "ads").contains(medium) ? "PAID_ADS"
                : "PARTNERSHIP".equals(e.getEnquiryType()) ? "PARTNER"
                : "INBOUND";
        String motion = e.getReferralCode() != null ? "AUDIT_FIRM_CHANNEL" : "SALES_LED";

        Map<String, Object> admin = new LinkedHashMap<>();
        admin.put("firstName", parts[0]);
        admin.put("lastName", parts.length > 1 ? parts[1] : "");
        admin.put("email", e.getEmail());
        admin.put("jobTitle", e.getJobTitle());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", e.getCompany());
        m.put("country", e.getCountry() == null ? "IN" : e.getCountry());
        m.put("employeeBand", e.getEmployeeBand());
        m.put("admin", admin);
        m.put("plan", e.getPlanCode());
        m.put("startAs", "TRIAL".equals(e.getEnquiryType()) ? "TRIAL" : null);
        m.put("acquisitionChannel", channel);
        m.put("salesMotion", motion);
        m.put("isAuditFirm", "AUDIT_FIRM".equals(e.getEnquiryType()));
        m.put("description", "From website enquiry " + e.getReference()
                + (e.getMessage() == null ? "" : ": " + trim(e.getMessage(), 600)));
        return m;
    }

    // ── internals ────────────────────────────────────────────────────────────

    public Enquiry require(Long id) {
        return enquiryRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Enquiry", id));
    }

    private void activity(Enquiry e, String kind, String body, String from, String to, Long actorId) {
        activityRepository.save(EnquiryActivity.builder()
                .enquiryId(e.getId()).kind(kind).body(body)
                .fromValue(from == null ? null : trim(from, 60))
                .toValue(to == null ? null : trim(to, 60))
                .authorId(actorId).build());
    }

    private static void firstResponse(Enquiry e) {
        if (e.getFirstResponseAt() == null) e.setFirstResponseAt(LocalDateTime.now());
    }

    private static void touch(Enquiry e) { e.setLastActivityAt(LocalDateTime.now()); }

    private Map<Long, String> userNames(Set<Long> ids) {
        ids.remove(null);
        if (ids.isEmpty()) return Map.of();
        Map<Long, String> m = new HashMap<>();
        userRepository.findAllById(ids).forEach(u -> m.put(u.getId(),
                ((u.getFirstName() == null ? "" : u.getFirstName()) + " " + (u.getLastName() == null ? "" : u.getLastName())).trim()));
        return m;
    }

    private Map<Long, String> tenantNames(Set<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        Map<Long, String> m = new HashMap<>();
        tenantRepository.findAllById(ids).forEach(t -> m.put(t.getId(), t.getName()));
        return m;
    }

    private String uniqueReference() {
        for (int i = 0; i < 10; i++) {
            String r = newReference();
            if (!enquiryRepository.existsByReference(r)) return r;
        }
        return "KG-" + System.currentTimeMillis();
    }

    private static String newReference() {
        StringBuilder sb = new StringBuilder("KG-");
        for (int i = 0; i < 6; i++) sb.append(REF_ALPHABET.charAt(RANDOM.nextInt(REF_ALPHABET.length())));
        return sb.toString();
    }

    private String hash(String ip) {
        if (ip == null || ip.isBlank()) return null;
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(d.digest((ipSalt + "|" + ip.trim()).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            return null;
        }
    }

    private static String band(String raw) {
        if (blank(raw)) return null;
        String b = raw.trim().replace('–', '-').replace('—', '-').replace(",", "").replace(" ", "");
        return BANDS.contains(b) ? b : null;
    }

    public static String label(String type) {
        return switch (type == null ? "" : type) {
            case "DEMO" -> "demo request";
            case "TRIAL" -> "free trial";
            case "CONTACT_SALES" -> "sales enquiry";
            case "AUDIT_FIRM" -> "audit-firm application";
            case "PARTNERSHIP" -> "partnership enquiry";
            case "SUPPORT" -> "support request";
            case "WORKFLOW_REQUEST" -> "custom workflow request";
            default -> "enquiry";
        };
    }

    private static String humanise(String s) {
        return s == null ? "" : s.charAt(0) + s.substring(1).toLowerCase().replace('_', ' ');
    }

    private static BusinessException bad(String field, String msg) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("fieldErrors", Map.of(field, msg));
        return new BusinessException("ENQUIRY_INVALID", msg, HttpStatus.UNPROCESSABLE_ENTITY, details);
    }

    private static String up(Object o) { return o == null ? "" : o.toString().trim().toUpperCase(); }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static String trim(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }
}
