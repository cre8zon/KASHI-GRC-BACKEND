package com.kashi.grc.firmverification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.service.MailService;
import com.kashi.grc.document.domain.Document;
import com.kashi.grc.document.domain.DocumentLink;
import com.kashi.grc.document.repository.DocumentLinkRepository;
import com.kashi.grc.document.repository.DocumentRepository;
import com.kashi.grc.firmverification.domain.AuditFirmApplication;
import com.kashi.grc.firmverification.repository.AuditFirmApplicationRepository;
import com.kashi.grc.notification.service.NotificationService;
import com.kashi.grc.tenant.domain.Tenant;
import com.kashi.grc.tenant.repository.TenantRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The ONLY code path that sets tenants.is_audit_firm.
 *
 * ── WHO RUNS IT ───────────────────────────────────────────────────────────
 * Platform users only. Audit-firm access is a free-tier capability, so the
 * risk is impersonation, not revenue — and the place to stop an impersonator
 * is before the tenant exists in anyone's firm picker. The firm emails its
 * details and evidence; a platform user records them; the firm confirms by
 * clicking a one-time link sent to its own domain.
 *
 * ── WHAT "STRICT" MEANS HERE ──────────────────────────────────────────────
 *  1. Identity: legal name, registering body and registration number, which
 *     the reviewer checks against the public register (ICAI, AICPA / state
 *     board, NABCB / ANAB / UKAS, CERT-In empanelment list, PCI SSC QSA list)
 *     and records what they found.
 *  2. Evidence: at least one registration / accreditation document plus one
 *     more supporting document, uploaded into the application — enforced here.
 *  3. Mailbox control: the contact email must be on the firm's website domain
 *     (free-mail refused) and the firm must click the one-time link mailed to
 *     it. The link shows the details being confirmed, so the click is also the
 *     firm's attestation that they are accurate.
 *  4. Four eyes (optional): with kashi.firm-verification.four-eyes=true,
 *     whoever opened or submitted an application cannot approve it. Off by
 *     default while the platform has a single admin; turn it on once a
 *     second reviewer exists. Who opened, submitted and decided is recorded
 *     on the application either way.
 *  5. Expiry: approval lasts kashi.firm-verification.validity-months (default
 *     12), capped by the registration's own expiry. Lapse turns the flag off.
 *  6. Revocable at any time, with a reason; the flag goes off immediately.
 *
 * The flag is recomputed from the applications on every state change
 * (syncFlag) rather than flipped directly, so it can never disagree with the
 * evidence trail.
 *
 * ── WHAT IT DOES NOT TOUCH ───────────────────────────────────────────────
 * Existing firm_access_grants are not revoked when recognition lapses — that is
 * a client's relationship and lives in another module. With the flag off, the
 * firm cannot be newly admitted by anyone and cannot request access.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditFirmVerificationService {

    public static final String ENTITY_TYPE = "AUDIT_FIRM_APPLICATION";
    public static final String PERM_REVIEW = "platform.firm_verification.review";

    public static final Set<String> OPEN = Set.of("DRAFT", "SUBMITTED", "UNDER_REVIEW", "INFO_REQUESTED");
    public static final Set<String> EDITABLE = Set.of("DRAFT", "INFO_REQUESTED");
    public static final Set<String> FIRM_TYPES = Set.of(
            "CA_FIRM", "CPA_FIRM", "CERTIFICATION_BODY", "CERT_IN_EMPANELLED", "PCI_QSA",
            "INTERNAL_AUDIT_PRACTICE", "OTHER");
    /** linkType values used for evidence categories on document_links. */
    public static final Set<String> REGISTRATION_EVIDENCE = Set.of("REGISTRATION", "ACCREDITATION");
    public static final Set<String> EVIDENCE_TYPES = Set.of(
            "REGISTRATION", "ACCREDITATION", "PEER_REVIEW", "INSURANCE", "AUTHORISATION", "OTHER");

    private static final Set<String> FREE_MAIL = Set.of(
            "gmail.com", "googlemail.com", "yahoo.com", "yahoo.co.in", "outlook.com", "hotmail.com",
            "live.com", "icloud.com", "me.com", "aol.com", "proton.me", "protonmail.com", "zoho.com",
            "rediffmail.com", "yandex.com", "gmx.com", "mail.com");

    private final AuditFirmApplicationRepository repository;
    private final TenantRepository               tenantRepository;
    private final DocumentLinkRepository         documentLinkRepository;
    private final DocumentRepository             documentRepository;
    private final NotificationService            notificationService;
    private final MailService                    mailService;
    private final ObjectMapper                   objectMapper;

    @PersistenceContext
    private EntityManager em;

    @Value("${kashi.firm-verification.validity-months:12}")
    private int validityMonths;

    /** Off by default: a platform admin may approve an application they prepared. */
    @Value("${kashi.firm-verification.four-eyes:false}")
    private boolean fourEyes;

    public boolean isFourEyes() { return fourEyes; }

    @Value("${kashi.firm-verification.min-documents:2}")
    private int minDocuments;

    @Value("${kashi.firm-verification.link-valid-days:7}")
    private int linkValidDays;

    @Value("${kashi.firm-verification.max-confirmation-emails:5}")
    private int maxConfirmationEmails;

    @Value("${kashi.app.base-url:https://app.kashigrc.com}")
    private String appBaseUrl;

    private static final SecureRandom RANDOM = new SecureRandom();

    // ══════════════════════ READ ═════════════════════════════════════════════

    public AuditFirmApplication require(Long id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("AuditFirmApplication", id));
    }

    public List<AuditFirmApplication> forTenant(Long tenantId) {
        return repository.findByTenantIdOrderByIdDesc(tenantId);
    }

    /** Active evidence documents on the application, whoever uploaded them. */
    public List<Map<String, Object>> evidence(AuditFirmApplication a) {
        List<DocumentLink> links = documentLinkRepository.findByEntityTypeAndEntityIdIn(ENTITY_TYPE, List.of(a.getId()));
        if (links.isEmpty()) return List.of();
        Map<Long, Document> docs = documentRepository.findAllById(links.stream().map(DocumentLink::getDocumentId).toList())
                .stream().collect(Collectors.toMap(Document::getId, d -> d));
        List<Map<String, Object>> out = new ArrayList<>();
        for (DocumentLink l : links) {
            Document d = docs.get(l.getDocumentId());
            if (d == null || !"ACTIVE".equals(d.getStatus())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("linkId", l.getId());
            m.put("documentId", d.getId());
            m.put("category", l.getLinkType());
            m.put("title", d.getTitle() != null ? d.getTitle() : d.getFileName());
            m.put("fileName", d.getFileName());
            m.put("mimeType", d.getMimeType());
            m.put("contentLength", d.getContentLength());
            m.put("checksumSha256", d.getChecksumSha256());
            m.put("uploadedBy", d.getUploadedBy());
            m.put("createdAt", d.getCreatedAt());
            out.add(m);
        }
        return out;
    }

    public Document evidenceDocument(AuditFirmApplication a, Long documentId) {
        boolean linked = documentLinkRepository.findByEntityTypeAndEntityIdIn(ENTITY_TYPE, List.of(a.getId()))
                .stream().anyMatch(l -> l.getDocumentId().equals(documentId));
        if (!linked) throw new ResourceNotFoundException("Document", documentId);
        Document d = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document", documentId));
        if (!"ACTIVE".equals(d.getStatus())) {
            throw new BusinessException("DOCUMENT_NOT_ACTIVE", "Document is not active.", HttpStatus.CONFLICT);
        }
        return d;
    }

    // ══════════════════════ PREPARE (platform user) ══════════════════════════

    /**
     * Opens a draft for a tenant, or returns its open one. Re-verification of
     * an approved firm opens only in the last 60 days of its verification.
     */
    @Transactional
    public AuditFirmApplication openDraft(Long tenantId, Long actorId) {
        Tenant t = tenantRepository.findById(tenantId).orElseThrow(() -> new ResourceNotFoundException("Tenant", tenantId));
        List<AuditFirmApplication> all = repository.findByTenantIdOrderByIdDesc(tenantId);
        Optional<AuditFirmApplication> open = all.stream().filter(a -> OPEN.contains(a.getStatus())).findFirst();
        if (open.isPresent()) return open.get();

        Optional<AuditFirmApplication> approved = all.stream().filter(a -> "APPROVED".equals(a.getStatus())).findFirst();
        if (approved.isPresent() && approved.get().getVerifiedUntil() != null
                && approved.get().getVerifiedUntil().isAfter(LocalDate.now().plusDays(60))) {
            throw new BusinessException("ALREADY_VERIFIED",
                    "This organisation is verified until " + approved.get().getVerifiedUntil()
                            + ". Re-verification opens 60 days before that.", HttpStatus.CONFLICT);
        }

        AuditFirmApplication prev = all.isEmpty() ? null : all.get(0);
        AuditFirmApplication a = AuditFirmApplication.builder()
                .tenantId(tenantId)
                .status("DRAFT")
                .createdBy(actorId)
                .firmLegalName(prev != null ? prev.getFirmLegalName() : (t.getLegalName() != null ? t.getLegalName() : t.getName()))
                .previousApplicationId(prev != null ? prev.getId() : null)
                .build();
        if (prev != null) {
            // Renewal: carry the facts over; the evidence must be uploaded fresh.
            a.setFirmType(prev.getFirmType());
            a.setRegistrationBody(prev.getRegistrationBody());
            a.setRegistrationNumber(prev.getRegistrationNumber());
            a.setJurisdiction(prev.getJurisdiction());
            a.setWebsite(prev.getWebsite());
            a.setDomain(prev.getDomain());
            a.setContactName(prev.getContactName());
            a.setContactEmail(prev.getContactEmail());
            a.setContactDesignation(prev.getContactDesignation());
            a.setFrameworksCsv(prev.getFrameworksCsv());
            a.setPartnerCount(prev.getPartnerCount());
        }
        repository.save(a);
        log.info("[FIRM-VERIFY] Draft opened | tenant={} app={} by={}", tenantId, a.getId(), actorId);
        return a;
    }

    @Transactional
    public AuditFirmApplication saveDraft(AuditFirmApplication a, Map<String, Object> b) {
        if (!EDITABLE.contains(a.getStatus())) {
            throw new BusinessException("NOT_EDITABLE", "The application is " + a.getStatus() + " and cannot be edited.",
                    HttpStatus.CONFLICT);
        }
        String before = confirmedFacts(a);
        if (b.containsKey("firmLegalName"))      a.setFirmLegalName(str(b.get("firmLegalName")));
        if (b.containsKey("firmType")) {
            String ft = str(b.get("firmType"));
            if (ft != null && !FIRM_TYPES.contains(ft)) throw new BusinessException("BAD_FIRM_TYPE", "Unknown firm type " + ft);
            a.setFirmType(ft);
        }
        if (b.containsKey("registrationBody"))   a.setRegistrationBody(str(b.get("registrationBody")));
        if (b.containsKey("registrationNumber")) a.setRegistrationNumber(str(b.get("registrationNumber")));
        if (b.containsKey("registrationValidUntil")) {
            String d = str(b.get("registrationValidUntil"));
            a.setRegistrationValidUntil(d == null ? null : LocalDate.parse(d.substring(0, 10)));
        }
        if (b.containsKey("jurisdiction")) {
            String j = str(b.get("jurisdiction"));
            a.setJurisdiction(j == null ? null : j.toUpperCase());
        }
        if (b.containsKey("website")) {
            String w = str(b.get("website"));
            String newDomain = w == null ? null : domainOf(w);
            a.setWebsite(w);
            a.setDomain(newDomain);
        }
        if (b.containsKey("contactName"))        a.setContactName(str(b.get("contactName")));
        if (b.containsKey("contactEmail")) {
            String e = str(b.get("contactEmail"));
            a.setContactEmail(e == null ? null : e.toLowerCase());
        }
        if (b.containsKey("contactDesignation")) a.setContactDesignation(str(b.get("contactDesignation")));
        if (b.containsKey("frameworks")) {
            Object v = b.get("frameworks");
            a.setFrameworksCsv(v instanceof Collection<?> c
                    ? c.stream().map(Object::toString).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.joining(","))
                    : str(v));
        }
        if (b.containsKey("partnerCount")) {
            String p = str(b.get("partnerCount"));
            a.setPartnerCount(p == null ? null : (int) Double.parseDouble(p));
        }
        if (b.containsKey("statement"))          a.setStatement(str(b.get("statement")));
        // The firm confirmed the details it was shown. Any edit after that means
        // it has not confirmed THESE details, so the confirmation is withdrawn
        // and a fresh link has to go out.
        if (!before.equals(confirmedFacts(a)) && (a.getEmailConfirmedAt() != null || a.getEmailTokenHash() != null)) {
            a.setEmailConfirmedAt(null);
            a.setEmailConfirmedIp(null);
            a.setEmailTokenHash(null);
            a.setEmailExpiresAt(null);
            a.setAttested(false);
        }
        return repository.save(a);
    }

    /**
     * Mails the one-time confirmation link to the firm's contact. Re-sending
     * replaces the previous link. Capped per application so the endpoint cannot
     * be used to mail-bomb an address.
     */
    /** The facts the firm is shown and confirms. A change to any of them voids the confirmation. */
    private static String confirmedFacts(AuditFirmApplication a) {
        return String.join("|", nz(a.getFirmLegalName()), nz(a.getFirmType()), nz(a.getRegistrationBody()),
                nz(a.getRegistrationNumber()), String.valueOf(a.getRegistrationValidUntil()), nz(a.getJurisdiction()),
                nz(a.getDomain()), nz(a.getContactName()), nz(a.getContactEmail()), nz(a.getFrameworksCsv()));
    }

    @Transactional
    public AuditFirmApplication sendConfirmation(AuditFirmApplication a, Long actorId) {
        if (!EDITABLE.contains(a.getStatus())) {
            throw new BusinessException("NOT_EDITABLE", "Confirmation is sent while the application is being prepared.",
                    HttpStatus.CONFLICT);
        }
        List<String> problems = detailProblems(a);
        if (!problems.isEmpty()) {
            throw new BusinessException("DETAILS_INCOMPLETE",
                    "Complete the firm details before asking the firm to confirm them. " + String.join(" ", problems));
        }
        if (a.getEmailSendCount() >= maxConfirmationEmails) {
            throw new BusinessException("TOO_MANY_EMAILS",
                    "The confirmation email has been sent " + a.getEmailSendCount()
                            + " times. Check the address with the firm before sending again.", HttpStatus.TOO_MANY_REQUESTS);
        }
        String token = newToken();
        a.setEmailTokenHash(sha256(token));
        a.setEmailSentAt(LocalDateTime.now());
        a.setEmailSentTo(a.getContactEmail());
        a.setEmailExpiresAt(LocalDateTime.now().plusDays(linkValidDays));
        a.setEmailConfirmedAt(null);
        a.setAttested(false);
        a.setEmailSendCount(a.getEmailSendCount() + 1);
        repository.save(a);

        Map<String, String> vars = new HashMap<>();
        vars.put("contactName", nz(a.getContactName()));
        vars.put("firmName", nz(a.getFirmLegalName()));
        vars.put("registrationBody", nz(a.getRegistrationBody()));
        vars.put("registrationNumber", nz(a.getRegistrationNumber()));
        vars.put("jurisdiction", nz(a.getJurisdiction()));
        vars.put("website", nz(a.getWebsite()));
        vars.put("frameworks", nz(a.getFrameworksCsv()).replace(",", ", "));
        vars.put("confirmUrl", appBaseUrl + "/verify-audit-firm?token=" + token);
        vars.put("expiresOn", a.getEmailExpiresAt().toLocalDate().toString());
        mail("audit-firm-confirmation", a.getContactEmail(), vars, a.getTenantId());
        log.info("[FIRM-VERIFY] Confirmation mailed | app={} to={} by={} count={}",
                a.getId(), a.getContactEmail(), actorId, a.getEmailSendCount());
        return a;
    }

    /** What the public confirmation page shows before the click. Never confirms. */
    @Transactional(readOnly = true)
    public Map<String, Object> preview(String token) {
        AuditFirmApplication a = byToken(token);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("firmLegalName", a.getFirmLegalName());
        m.put("registrationBody", a.getRegistrationBody());
        m.put("registrationNumber", a.getRegistrationNumber());
        m.put("jurisdiction", a.getJurisdiction());
        m.put("website", a.getWebsite());
        m.put("frameworks", a.getFrameworksCsv() == null ? List.of() : List.of(a.getFrameworksCsv().split(",")));
        m.put("contactName", a.getContactName());
        m.put("contactEmail", mask(a.getContactEmail()));
        m.put("expiresAt", a.getEmailExpiresAt());
        return m;
    }

    /**
     * The firm's click. One-time: the token hash is cleared on use. A POST from
     * the confirmation page, never a GET on the link itself — mail scanners
     * (Safe Links, Mimecast) fetch every link in an email and would otherwise
     * confirm on the firm's behalf.
     */
    @Transactional
    public Map<String, Object> confirm(String token, String ip) {
        AuditFirmApplication a = byToken(token);
        a.setEmailConfirmedAt(LocalDateTime.now());
        a.setEmailConfirmedIp(ip);
        a.setEmailTokenHash(null);
        a.setAttested(true);
        repository.save(a);

        String firm = nz(a.getFirmLegalName());
        if (a.getCreatedBy() != null) {
            notifyUsers(List.of(a.getCreatedBy()), "FIRM_VERIFICATION_CONFIRMED",
                    firm + " confirmed its audit-firm details by email. The application can be submitted for review.",
                    a, Map.of("firmName", firm));
        }
        log.info("[FIRM-VERIFY] Mailbox confirmed | app={} ip={}", a.getId(), ip);
        return Map.of("confirmed", true, "firmLegalName", firm);
    }

    private AuditFirmApplication byToken(String token) {
        if (token == null || token.isBlank()) throw invalidLink();
        AuditFirmApplication a = repository.findByEmailTokenHash(sha256(token.trim())).orElseThrow(this::invalidLink);
        if (!EDITABLE.contains(a.getStatus())) throw invalidLink();
        if (a.getEmailExpiresAt() == null || a.getEmailExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException("LINK_EXPIRED",
                    "This confirmation link has expired. Ask KashiGRC to send a new one.", HttpStatus.GONE);
        }
        return a;
    }

    private BusinessException invalidLink() {
        // One answer for unknown, used and closed — the page cannot be used to probe.
        return new BusinessException("LINK_INVALID",
                "This confirmation link is not valid. It may already have been used.", HttpStatus.NOT_FOUND);
    }

    /** The firm details alone — what must be right before the firm is asked to confirm them. */
    public List<String> detailProblems(AuditFirmApplication a) {
        List<String> p = new ArrayList<>();
        if (blank(a.getFirmLegalName()))      p.add("Firm legal name is required.");
        if (blank(a.getFirmType()))           p.add("Firm type is required.");
        if (blank(a.getRegistrationBody()))   p.add("Registering or accrediting body is required.");
        if (blank(a.getRegistrationNumber())) p.add("Registration / accreditation number is required.");
        if (blank(a.getJurisdiction()))       p.add("Jurisdiction (country) is required.");
        if (blank(a.getDomain()))             p.add("Firm website is required.");
        if (blank(a.getContactName()))        p.add("Authorised contact name is required.");
        if (blank(a.getContactEmail()) || !a.getContactEmail().contains("@")) p.add("Authorised contact email is required.");
        else {
            String ed = a.getContactEmail().substring(a.getContactEmail().indexOf('@') + 1);
            if (FREE_MAIL.contains(ed)) p.add("Contact email must be on the firm's own domain, not " + ed + ".");
            else if (a.getDomain() != null && !(ed.equals(a.getDomain()) || ed.endsWith("." + a.getDomain()))) {
                p.add("Contact email domain (" + ed + ") must match the firm's website domain (" + a.getDomain() + ").");
            }
        }
        if (blank(a.getFrameworksCsv()))      p.add("Select at least one framework the firm audits.");
        if (a.getRegistrationValidUntil() != null && !a.getRegistrationValidUntil().isAfter(LocalDate.now())) {
            p.add("The registration has already expired (" + a.getRegistrationValidUntil() + ").");
        }
        return p;
    }

    /** Every rule for submission and approval. The UI checklist mirrors this method. */
    public List<String> submissionProblems(AuditFirmApplication a) {
        List<String> p = detailProblems(a);
        List<Map<String, Object>> ev = evidence(a);
        if (ev.size() < minDocuments) p.add("Upload at least " + minDocuments + " supporting documents.");
        if (ev.stream().noneMatch(d -> REGISTRATION_EVIDENCE.contains(String.valueOf(d.get("category"))))) {
            p.add("Upload the registration certificate or accreditation certificate.");
        }
        if (a.getEmailConfirmedAt() == null) {
            p.add(a.getEmailSentAt() == null
                    ? "Send the confirmation email to the firm's contact."
                    : "Waiting for the firm to click the confirmation link sent to " + a.getEmailSentTo() + ".");
        }
        return p;
    }

    @Transactional
    public AuditFirmApplication submit(AuditFirmApplication a, Long actorId) {
        if (!EDITABLE.contains(a.getStatus())) {
            throw new BusinessException("NOT_EDITABLE", "Only a draft can be submitted.", HttpStatus.CONFLICT);
        }
        List<String> problems = submissionProblems(a);
        if (!problems.isEmpty()) {
            throw new BusinessException("APPLICATION_INCOMPLETE", String.join(" ", problems));
        }
        a.setStatus("SUBMITTED");
        a.setSubmittedBy(actorId);
        a.setSubmittedAt(LocalDateTime.now());
        a.setInfoRequest(null);
        repository.save(a);

        String firm = tenantName(a.getTenantId());
        List<Long> reviewers = usersWithPermission(PERM_REVIEW).stream()
                .filter(id -> !fourEyes || (!id.equals(actorId) && !id.equals(a.getCreatedBy()))).toList();
        notifyUsers(reviewers, "FIRM_VERIFICATION_SUBMITTED",
                firm + " is ready for audit-firm verification review.", a, Map.of("firmName", firm));
        log.info("[FIRM-VERIFY] Submitted | app={} tenant={} by={}", a.getId(), a.getTenantId(), actorId);
        return a;
    }

    // ══════════════════════ REVIEWER ═════════════════════════════════════════

    @Transactional
    public AuditFirmApplication startReview(Long id, Long reviewerId) {
        AuditFirmApplication a = require(id);
        if (!"SUBMITTED".equals(a.getStatus())) {
            throw new BusinessException("BAD_STATUS", "Only a submitted application can be picked up.", HttpStatus.CONFLICT);
        }
        assertIndependent(a, reviewerId);
        a.setStatus("UNDER_REVIEW");
        a.setReviewerId(reviewerId);
        a.setReviewStartedAt(LocalDateTime.now());
        return repository.save(a);
    }

    @Transactional
    public AuditFirmApplication requestInfo(Long id, String message, Long reviewerId) {
        AuditFirmApplication a = require(id);
        if (!Set.of("SUBMITTED", "UNDER_REVIEW").contains(a.getStatus())) {
            throw new BusinessException("BAD_STATUS", "Information can only be requested on a submitted application.",
                    HttpStatus.CONFLICT);
        }
        if (blank(message)) throw new BusinessException("MESSAGE_REQUIRED", "Say what the firm needs to provide.");
        a.setStatus("INFO_REQUESTED");
        a.setInfoRequest(message);
        if (a.getReviewerId() == null) a.setReviewerId(reviewerId);
        repository.save(a);
        notifyPreparer(a, "FIRM_VERIFICATION_INFO_REQUESTED",
                "Reviewer needs more from " + nz(a.getFirmLegalName()) + ": " + message);
        return a;
    }

    /**
     * Approve. Requires: independence (four eyes), a complete application, and
     * a reviewer checklist with the registry check and evidence check done and
     * domain control either proven by DNS or explicitly waived with a reason.
     */
    @Transactional
    public AuditFirmApplication approve(Long id, Map<String, Object> checklist, LocalDate requestedUntil,
                                        String note, Long reviewerId) {
        AuditFirmApplication a = require(id);
        if (!Set.of("SUBMITTED", "UNDER_REVIEW").contains(a.getStatus())) {
            throw new BusinessException("BAD_STATUS", "Only a submitted application can be approved.", HttpStatus.CONFLICT);
        }
        assertIndependent(a, reviewerId);

        List<String> problems = submissionProblems(a);
        if (!problems.isEmpty()) throw new BusinessException("APPLICATION_INCOMPLETE", String.join(" ", problems));

        Map<String, Object> c = checklist == null ? Map.of() : checklist;
        List<String> missing = new ArrayList<>();
        if (!Boolean.TRUE.equals(c.get("registryChecked"))) missing.add("registration verified on the public register");
        if (blank(str(c.get("registryNote")))) missing.add("a note of what the register showed (URL / reference)");
        if (!Boolean.TRUE.equals(c.get("evidenceAuthentic"))) missing.add("evidence reviewed and consistent");
        if (!Boolean.TRUE.equals(c.get("sanctionsChecked"))) missing.add("sanctions / adverse-media check");
        if (!missing.isEmpty()) {
            throw new BusinessException("CHECKLIST_INCOMPLETE", "Before approving, record: " + String.join("; ", missing) + ".");
        }

        LocalDate cap = LocalDate.now().plusMonths(validityMonths);
        if (a.getRegistrationValidUntil() != null && a.getRegistrationValidUntil().isBefore(cap)) {
            cap = a.getRegistrationValidUntil();
        }
        LocalDate until = requestedUntil != null && requestedUntil.isBefore(cap) ? requestedUntil : cap;
        if (!until.isAfter(LocalDate.now())) throw new BusinessException("BAD_DATE", "Verified-until must be in the future.");

        Map<String, Object> record = new LinkedHashMap<>(c);
        record.put("mailboxConfirmedAt", String.valueOf(a.getEmailConfirmedAt()));
        record.put("mailboxConfirmedTo", a.getEmailSentTo());
        try { a.setChecklistJson(objectMapper.writeValueAsString(record)); }
        catch (Exception e) { a.setChecklistJson(record.toString()); }

        a.setStatus("APPROVED");
        a.setDecidedBy(reviewerId);
        a.setDecidedAt(LocalDateTime.now());
        a.setDecisionNote(note);
        a.setVerifiedUntil(until);
        if (a.getReviewerId() == null) a.setReviewerId(reviewerId);
        repository.save(a);

        // A renewal supersedes the approval it re-verifies.
        repository.findByTenantIdOrderByIdDesc(a.getTenantId()).stream()
                .filter(x -> !x.getId().equals(a.getId()) && "APPROVED".equals(x.getStatus()))
                .forEach(x -> { x.setStatus("SUPERSEDED"); repository.save(x); });

        syncFlag(a.getTenantId());
        notifyPreparer(a, "FIRM_VERIFICATION_APPROVED",
                nz(a.getFirmLegalName()) + " is verified as an audit firm until " + until + ".");
        tellFirm(a, "audit-firm-verified", "verified", until);
        log.info("[FIRM-VERIFY] APPROVED | app={} tenant={} until={} by={}", a.getId(), a.getTenantId(), until, reviewerId);
        return a;
    }

    @Transactional
    public AuditFirmApplication reject(Long id, String note, Long reviewerId) {
        AuditFirmApplication a = require(id);
        if (!Set.of("SUBMITTED", "UNDER_REVIEW", "INFO_REQUESTED").contains(a.getStatus())) {
            throw new BusinessException("BAD_STATUS", "This application cannot be rejected.", HttpStatus.CONFLICT);
        }
        if (blank(note)) throw new BusinessException("REASON_REQUIRED", "Give the reason for rejecting.");
        a.setStatus("REJECTED");
        a.setDecidedBy(reviewerId);
        a.setDecidedAt(LocalDateTime.now());
        a.setDecisionNote(note);
        repository.save(a);
        syncFlag(a.getTenantId());
        notifyPreparer(a, "FIRM_VERIFICATION_REJECTED", nz(a.getFirmLegalName()) + " was not approved: " + note);
        return a;
    }

    /**
     * Withdraw an application that has not reached a reviewer — opened by
     * mistake, or the firm stopped responding. Kept as WITHDRAWN rather than
     * deleted, so the trail survives; an outstanding confirmation link stops
     * working. Never touches is_audit_firm (only approval sets it, and a draft
     * never had it).
     */
    @Transactional
    public AuditFirmApplication withdraw(Long id, String reason, Long actorId) {
        AuditFirmApplication a = require(id);
        if (!EDITABLE.contains(a.getStatus())) {
            throw new BusinessException("BAD_STATUS",
                    "Only an application still being prepared can be withdrawn (this one is " + a.getStatus() + ").",
                    HttpStatus.CONFLICT);
        }
        a.setStatus("WITHDRAWN");
        a.setDecidedBy(actorId);
        a.setDecidedAt(LocalDateTime.now());
        a.setDecisionNote(blank(reason) ? "Withdrawn before review" : reason.trim());
        a.setEmailTokenHash(null);   // the firm's link, if one was sent, no longer works
        repository.save(a);
        log.info("[FIRM-VERIFY] Withdrawn | app={} tenant={} by={}", a.getId(), a.getTenantId(), actorId);
        return a;
    }

    @Transactional
    public AuditFirmApplication revoke(Long id, String reason, Long actorId) {
        AuditFirmApplication a = require(id);
        if (!"APPROVED".equals(a.getStatus())) {
            throw new BusinessException("BAD_STATUS", "Only an approved verification can be revoked.", HttpStatus.CONFLICT);
        }
        if (blank(reason)) throw new BusinessException("REASON_REQUIRED", "Give the reason for revoking.");
        a.setStatus("REVOKED");
        a.setRevokedBy(actorId);
        a.setRevokedAt(LocalDateTime.now());
        a.setRevokeReason(reason);
        repository.save(a);
        syncFlag(a.getTenantId());
        notifyPreparer(a, "FIRM_VERIFICATION_REVOKED", nz(a.getFirmLegalName()) + " audit-firm verification revoked: " + reason);
        tellFirm(a, "audit-firm-verification-ended", "revoked", null);
        log.warn("[FIRM-VERIFY] REVOKED | app={} tenant={} by={} reason={}", a.getId(), a.getTenantId(), actorId, reason);
        return a;
    }

    /** Daily: expire lapsed approvals; remind 30 days before. */
    @Transactional
    public Map<String, Integer> expirySweep(LocalDate today) {
        int expired = 0, reminded = 0;
        // verifiedUntil is the last valid day (syncFlag counts it as verified), so expire the day after.
        for (AuditFirmApplication a : repository.findByStatusAndVerifiedUntilBefore("APPROVED", today)) {
            a.setStatus("EXPIRED");
            repository.save(a);
            syncFlag(a.getTenantId());
            notifyReviewersAndPreparer(a, "FIRM_VERIFICATION_EXPIRED",
                    nz(a.getFirmLegalName()) + " audit-firm verification expired on " + a.getVerifiedUntil() + ".");
            tellFirm(a, "audit-firm-verification-ended", "expired", a.getVerifiedUntil());
            expired++;
        }
        for (AuditFirmApplication a : repository.findByStatusAndVerifiedUntilBetween("APPROVED", today, today.plusDays(30))) {
            if (a.getExpiryReminderSentAt() != null) continue;
            a.setExpiryReminderSentAt(LocalDateTime.now());
            repository.save(a);
            notifyReviewersAndPreparer(a, "FIRM_VERIFICATION_EXPIRING",
                    nz(a.getFirmLegalName()) + " audit-firm verification expires on " + a.getVerifiedUntil()
                            + ". Ask the firm for current evidence and open a re-verification.");
            reminded++;
        }
        return Map.of("expired", expired, "reminded", reminded);
    }

    // ══════════════════════ THE FLAG ═════════════════════════════════════════

    /** is_audit_firm = has an APPROVED application whose verifiedUntil is today or later. */
    @Transactional
    public boolean syncFlag(Long tenantId) {
        LocalDate today = LocalDate.now();
        boolean verified = repository.findByTenantIdOrderByIdDesc(tenantId).stream()
                .anyMatch(a -> "APPROVED".equals(a.getStatus())
                        && a.getVerifiedUntil() != null && !a.getVerifiedUntil().isBefore(today));
        tenantRepository.findById(tenantId).ifPresent(t -> {
            if (t.isAuditFirm() != verified) {
                t.setAuditFirm(verified);
                tenantRepository.save(t);
                log.info("[FIRM-VERIFY] tenants.is_audit_firm {} → {} | tenant={}", !verified, verified, tenantId);
            }
        });
        return verified;
    }

    // ══════════════════════ INTERNALS ════════════════════════════════════════

    private void assertIndependent(AuditFirmApplication a, Long reviewerId) {
        if (!fourEyes) return;
        if (reviewerId.equals(a.getSubmittedBy()) || reviewerId.equals(a.getCreatedBy())) {
            throw new BusinessException("FOUR_EYES",
                    "You opened or submitted this application, so a different platform admin must review it.",
                    HttpStatus.FORBIDDEN);
        }
    }

    /** The platform user who opened the application. */
    private void notifyPreparer(AuditFirmApplication a, String type, String message) {
        if (a.getCreatedBy() == null) return;
        notifyUsers(List.of(a.getCreatedBy()), type, message, a, Map.of("firmName", nz(a.getFirmLegalName())));
    }

    private void notifyReviewersAndPreparer(AuditFirmApplication a, String type, String message) {
        Set<Long> ids = new LinkedHashSet<>(usersWithPermission(PERM_REVIEW));
        if (a.getCreatedBy() != null) ids.add(a.getCreatedBy());
        notifyUsers(new ArrayList<>(ids), type, message, a, Map.of("firmName", nz(a.getFirmLegalName())));
    }

    /** Email to the firm's confirmed contact on approve / revoke / expire. */
    private void tellFirm(AuditFirmApplication a, String template, String outcome, LocalDate date) {
        String to = a.getEmailSentTo() != null ? a.getEmailSentTo() : a.getContactEmail();
        if (to == null) return;
        Map<String, String> vars = new HashMap<>();
        vars.put("contactName", nz(a.getContactName()));
        vars.put("firmName", nz(a.getFirmLegalName()));
        vars.put("outcome", outcome);
        vars.put("date", date == null ? "" : date.toString());
        vars.put("reason", nz(a.getRevokeReason()));
        mail(template, to, vars, a.getTenantId());
    }

    private void mail(String template, String to, Map<String, String> vars, Long tenantId) {
        try {
            mailService.send(template, to, vars, tenantId);
        } catch (Exception e) {
            log.warn("[FIRM-VERIFY] Mail {} to {} failed: {}", template, to, e.getMessage());
        }
    }

    private void notifyUsers(List<Long> ids, String type, String message, AuditFirmApplication a, Map<String, String> ctx) {
        for (Long uid : ids) {
            try {
                notificationService.send(uid, type, message, ENTITY_TYPE, a.getId(), null, ctx);
            } catch (Exception e) {
                log.warn("[FIRM-VERIFY] Notification {} to user {} failed: {}", type, uid, e.getMessage());
            }
        }
    }

    /** Users holding a permission — by permission code, never by role name. */
    @SuppressWarnings("unchecked")
    private List<Long> usersWithPermission(String code) {
        String sql = "SELECT DISTINCT u.id FROM users u "
                + "JOIN user_roles ur ON ur.user_id = u.id "
                + "JOIN role_permissions rp ON rp.role_id = ur.role_id "
                + "JOIN permissions p ON p.id = rp.permission_id "
                + "WHERE u.is_deleted = false AND p.code = :code";
        return ((List<Object>) em.createNativeQuery(sql).setParameter("code", code).getResultList())
                .stream().map(o -> ((Number) o).longValue()).toList();
    }

    private String tenantName(Long id) {
        return tenantRepository.findById(id).map(Tenant::getName).orElse("Tenant #" + id);
    }

    static String domainOf(String website) {
        String w = website.trim().toLowerCase();
        if (!w.startsWith("http://") && !w.startsWith("https://")) w = "https://" + w;
        try {
            String host = URI.create(w).getHost();
            if (host == null) throw new IllegalArgumentException();
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (IllegalArgumentException e) {
            throw new BusinessException("BAD_WEBSITE", "Enter the firm's website, e.g. https://examplefirm.com");
        }
    }

    private static String newToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String sha256(String s) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** a.sharma@firm.com → a.s****@firm.com */
    static String mask(String email) {
        if (email == null || !email.contains("@")) return email;
        String local = email.substring(0, email.indexOf('@'));
        String keep = local.length() <= 3 ? local.substring(0, 1) : local.substring(0, 3);
        return keep + "****" + email.substring(email.indexOf('@'));
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static String str(Object o) { if (o == null) return null; String s = o.toString().trim(); return s.isEmpty() ? null : s; }
}
