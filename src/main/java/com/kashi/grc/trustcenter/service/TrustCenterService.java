package com.kashi.grc.trustcenter.service;

import com.kashi.grc.common.exception.ForbiddenException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.exception.ValidationException;
import com.kashi.grc.trustcenter.domain.*;
import com.kashi.grc.trustcenter.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;

/**
 * The trust center: what a prospect sees, and what they have to do to get more.
 *
 * ── THE PUBLIC METHODS ARE THE SECURITY BOUNDARY ──────────────────────────
 * Everything below marked "public surface" runs with NO SESSION. There is no
 * tenant to read from a logged-in context, so the tenant is derived from the
 * slug or the token and never from anything the caller sends. That is the whole
 * discipline of this class: an anonymous caller may name a slug, and may
 * present a token, and nothing else they send is trusted.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrustCenterService {

    private final TrustCenterRepository          centerRepository;
    private final TrustCenterSectionRepository   sectionRepository;
    private final TrustCenterDocumentRepository  documentRepository;
    private final TrustAccessRequestRepository   requestRepository;
    private final TrustDocumentDownloadRepository downloadRepository;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** How long a grant lasts. Long enough for a procurement cycle, short
     *  enough that a token leaked in a forwarded email stops working. */
    private static final int TOKEN_DAYS = 30;

    // ═════════════════════════════════════════════════════════════════════════
    // PUBLIC SURFACE — NO SESSION
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * The page a prospect loads.
     *
     * Unpublished pages 404 rather than 403. A 403 confirms the slug exists,
     * which tells a stranger that this organisation is a customer of ours and
     * is preparing a trust page — neither of which is theirs to know.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> publicPage(String slug) {
        TrustCenter tc = centerRepository.findBySlugAndIsDeletedFalse(slug)
                .filter(TrustCenter::isPublished)
                .orElseThrow(() -> new ResourceNotFoundException("TrustCenter", "slug", slug));

        List<Map<String, Object>> sections = sectionRepository
                .findByTrustCenterIdAndIsVisibleTrueOrderBySortOrderAsc(tc.getId()).stream()
                .map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("type",  s.getSectionType());
                    m.put("title", s.getTitle());
                    m.put("body",  s.getBodyMd());
                    m.put("items", s.getItemsJson());
                    return m;
                }).toList();

        // ON_REQUEST documents are omitted entirely rather than shown locked.
        // "We have one, ask us" is a different posture from advertising a
        // document and refusing it, and the tenant chose the former.
        List<Map<String, Object>> docs = documentRepository
                .findByTrustCenterIdOrderBySortOrderAsc(tc.getId()).stream()
                .filter(TrustCenterDocument::isCurrentlyOffered)
                .filter(d -> !"ON_REQUEST".equals(d.getAccessLevel()))
                .map(d -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",          d.getId());
                    m.put("title",       d.getTitle());
                    m.put("description", d.getDescription());
                    m.put("category",    d.getCategory());
                    m.put("accessLevel", d.getAccessLevel());
                    // Never the storage id. A prospect has no business knowing
                    // our internal document identifiers, and publishing them
                    // invites someone to try the authenticated endpoint.
                    return m;
                }).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("slug",         tc.getSlug());
        out.put("title",        tc.getTitle());
        out.put("headline",     tc.getHeadline());
        out.put("intro",        tc.getIntroMd());
        out.put("primaryColor", tc.getPrimaryColor());
        out.put("contactEmail", tc.getContactEmail());
        out.put("sections",     sections);
        out.put("documents",    docs);
        return out;
    }

    /**
     * Somebody asks for a document.
     *
     * Auto-approved for EMAIL_GATED when the tenant has chosen that; everything
     * else waits for a human. NDA_REQUIRED never auto-approves regardless of the
     * setting — the tenant turned on auto-approval for email gating, and
     * silently extending that to a pentest report is not a decision they made.
     */
    @Transactional
    public Map<String, Object> requestAccess(String slug, Map<String, Object> body,
                                             String ip, String userAgent) {
        TrustCenter tc = centerRepository.findBySlugAndIsDeletedFalse(slug)
                .filter(TrustCenter::isPublished)
                .orElseThrow(() -> new ResourceNotFoundException("TrustCenter", "slug", slug));

        String email = str(body.get("email"));
        if (email == null || !email.contains("@")) {
            throw new ValidationException("A work email address is required.");
        }

        Long docId = asLong(body.get("documentId"));
        TrustCenterDocument doc = null;
        if (docId != null) {
            doc = documentRepository.findById(docId)
                    .filter(d -> d.getTrustCenterId().equals(tc.getId()))
                    .orElseThrow(() -> new ResourceNotFoundException("Document", docId));
        }

        TrustAccessRequest req = TrustAccessRequest.builder()
                .tenantId(tc.getTenantId())          // from the slug, never the caller
                .trustCenterId(tc.getId())
                .trustDocumentId(docId)
                .requesterEmail(email.toLowerCase())
                .requesterName(str(body.get("name")))
                .company(str(body.get("company")))
                .jobTitle(str(body.get("jobTitle")))
                .purpose(str(body.get("purpose")))
                .ndaAcknowledged(Boolean.TRUE.equals(body.get("ndaAcknowledged")))
                .status(TrustAccessRequest.Status.PENDING)
                .sourceIp(ip)
                .userAgent(trim(userAgent, 400))
                .build();

        boolean emailGatedOnly = doc != null && "EMAIL_GATED".equals(doc.getAccessLevel());
        if (tc.isAutoApproveEmailGated() && emailGatedOnly) {
            grant(req, null);
        }
        requestRepository.save(req);

        log.info("[TRUST] Access requested | slug={} | email={} | doc={} | status={}",
                slug, email, docId, req.getStatus());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", req.getStatus());
        // The token goes back only on auto-approval. For anything else it is
        // emailed after a human decides, and must not be inferable from this
        // response.
        out.put("accessToken", req.getStatus() == TrustAccessRequest.Status.APPROVED
                ? req.getAccessToken() : null);
        out.put("message", req.getStatus() == TrustAccessRequest.Status.APPROVED
                ? "Approved. Your download link is valid for " + TOKEN_DAYS + " days."
                : "Request received. You will hear from us by email.");
        return out;
    }

    /**
     * Resolves a token to a document the holder may fetch.
     *
     * Checked on EVERY fetch rather than once at approval, because the point of
     * a token over a signed URL is that revoking it works immediately. Returns
     * the internal document id for the caller to stream; the token holder never
     * sees it.
     */
    @Transactional
    public Long resolveDownload(String token, Long trustDocumentId, String ip) {
        TrustAccessRequest req = requestRepository.findByAccessToken(token)
                .orElseThrow(() -> new ForbiddenException("That link is not valid."));

        if (!req.isUsable()) {
            // One message for expired, revoked and denied. Distinguishing them
            // tells a stranger which of those happened, which is not theirs to
            // know and is useful to somebody probing.
            throw new ForbiddenException("That link is no longer valid.");
        }

        TrustCenterDocument doc = documentRepository.findById(trustDocumentId)
                .orElseThrow(() -> new ForbiddenException("That link is not valid."));

        // The tenant comes off the REQUEST row, which came off the slug. Nothing
        // the caller sent decides which tenant's documents are reachable.
        if (!req.getTenantId().equals(doc.getTenantId())
                || !req.getTrustCenterId().equals(doc.getTrustCenterId())) {
            throw new ForbiddenException("That link is not valid.");
        }
        // A grant for one specific document does not open the others.
        if (req.getTrustDocumentId() != null
                && !req.getTrustDocumentId().equals(trustDocumentId)) {
            throw new ForbiddenException("That link does not cover this document.");
        }
        if (!doc.isCurrentlyOffered()) {
            throw new ForbiddenException("That document is no longer available.");
        }

        downloadRepository.save(TrustDocumentDownload.builder()
                .tenantId(req.getTenantId())
                .requestId(req.getId())
                .trustDocumentId(trustDocumentId)
                .downloadedAt(LocalDateTime.now())
                .sourceIp(ip)
                .build());

        log.info("[TRUST] Download | tenant={} | doc={} | email={}",
                req.getTenantId(), trustDocumentId, req.getRequesterEmail());
        return doc.getDocumentId();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // AUTHENTICATED SURFACE
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Creates the tenant's trust center, or updates it.
     *
     * ── THIS WAS MISSING ENTIRELY IN THE FIRST VERSION ────────────────────
     * publish(), the sections and the documents all assumed a trust center
     * already existed, and only the demo seed ever created one. So every tenant
     * except META had no page, no way to make one, and publish() threw a
     * not-found they could do nothing about.
     *
     * One per tenant, hence upsert rather than create: there is no scenario
     * where an organisation wants two public trust pages, and a create endpoint
     * would need a duplicate check that says exactly this.
     */
    @Transactional
    public TrustCenter upsert(Map<String, Object> req, Long userId, Long tenantId) {
        TrustCenter tc = centerRepository.findByTenantIdAndIsDeletedFalse(tenantId).orElse(null);

        String slug = slugify(str(req.get("slug")));
        if (tc == null) {
            if (slug == null) throw new ValidationException(
                    "A trust page needs a web address — the slug that becomes /trust/<slug>.");
            if (centerRepository.existsBySlug(slug)) {
                // Unique across the PLATFORM, not the tenant: /trust/acme can
                // only belong to one organisation.
                throw new ValidationException(
                        "\"" + slug + "\" is already taken. Public addresses are unique across "
                                + "the platform, so pick another.");
            }
            tc = TrustCenter.builder()
                    .tenantId(tenantId)
                    .slug(slug)
                    .title(orDefault(str(req.get("title")), "Trust Center"))
                    .isPublished(false)
                    .createdBy(userId)
                    .build();
        } else if (slug != null && !slug.equals(tc.getSlug())) {
            if (centerRepository.existsBySlug(slug)) {
                throw new ValidationException("\"" + slug + "\" is already taken.");
            }
            // Changing the slug breaks every link already shared with a
            // prospect, which is worth saying out loud rather than discovering.
            if (tc.isPublished()) {
                log.warn("[TRUST] Slug changed on a PUBLISHED page | {} -> {} | tenant={}",
                        tc.getSlug(), slug, tenantId);
            }
            tc.setSlug(slug);
        }

        if (req.containsKey("title"))        tc.setTitle(orDefault(str(req.get("title")), tc.getTitle()));
        if (req.containsKey("headline"))     tc.setHeadline(str(req.get("headline")));
        if (req.containsKey("introMd"))      tc.setIntroMd(str(req.get("introMd")));
        if (req.containsKey("contactEmail")) tc.setContactEmail(str(req.get("contactEmail")));
        if (req.containsKey("primaryColor")) tc.setPrimaryColor(str(req.get("primaryColor")));
        if (req.containsKey("customDomain")) tc.setCustomDomain(str(req.get("customDomain")));
        if (req.containsKey("autoApproveEmailGated")) {
            tc.setAutoApproveEmailGated(Boolean.TRUE.equals(req.get("autoApproveEmailGated")));
        }
        if (req.containsKey("logoDocumentId")) tc.setLogoDocumentId(asLong(req.get("logoDocumentId")));

        return centerRepository.save(tc);
    }

    /** A section, created or updated. */
    @Transactional
    public TrustCenterSection saveSection(Map<String, Object> req, Long tenantId) {
        TrustCenter tc = requireCenter(tenantId);
        Long id = asLong(req.get("id"));

        TrustCenterSection sec = id == null
                ? TrustCenterSection.builder().tenantId(tenantId).trustCenterId(tc.getId()).build()
                : sectionRepository.findById(id)
                  .filter(x -> x.getTrustCenterId().equals(tc.getId()))
                  .orElseThrow(() -> new ResourceNotFoundException("Section", id));

        String title = str(req.get("title"));
        if (title == null && sec.getTitle() == null) {
            throw new ValidationException("A section needs a title.");
        }
        if (title != null) sec.setTitle(title);
        if (req.containsKey("sectionType")) sec.setSectionType(orDefault(str(req.get("sectionType")), "CUSTOM"));
        if (req.containsKey("bodyMd"))      sec.setBodyMd(str(req.get("bodyMd")));
        if (req.containsKey("itemsJson"))   sec.setItemsJson(str(req.get("itemsJson")));
        if (req.containsKey("sortOrder"))   sec.setSortOrder(asInt(req.get("sortOrder"), sec.getSortOrder()));
        if (req.containsKey("isVisible"))   sec.setVisible(!Boolean.FALSE.equals(req.get("isVisible")));

        return sectionRepository.save(sec);
    }

    @Transactional
    public void deleteSection(Long id, Long tenantId) {
        TrustCenter tc = requireCenter(tenantId);
        sectionRepository.findById(id)
                .filter(x -> x.getTrustCenterId().equals(tc.getId()))
                .ifPresent(sectionRepository::delete);
    }

    /**
     * Puts an existing document on the trust page.
     *
     * Takes a documentId from the tenant's own document store rather than an
     * upload, so the SOC 2 report on the trust page is the SAME file as the one
     * attached to the audit that produced it. Uploading a second copy is how a
     * page ends up serving last year's report.
     */
    @Transactional
    public TrustCenterDocument saveDocument(Map<String, Object> req, Long tenantId) {
        TrustCenter tc = requireCenter(tenantId);
        Long id = asLong(req.get("id"));

        TrustCenterDocument doc = id == null
                ? TrustCenterDocument.builder().tenantId(tenantId).trustCenterId(tc.getId()).build()
                : documentRepository.findById(id)
                  .filter(x -> x.getTrustCenterId().equals(tc.getId()))
                  .orElseThrow(() -> new ResourceNotFoundException("TrustDocument", id));

        if (id == null) {
            Long documentId = asLong(req.get("documentId"));
            if (documentId == null) throw new ValidationException("Choose a document to publish.");
            doc.setDocumentId(documentId);
        }

        String title = str(req.get("title"));
        if (title == null && doc.getTitle() == null) {
            throw new ValidationException("The document needs a title for the page.");
        }
        if (title != null) doc.setTitle(title);
        if (req.containsKey("description")) doc.setDescription(str(req.get("description")));
        if (req.containsKey("category"))    doc.setCategory(str(req.get("category")));
        if (req.containsKey("accessLevel")) doc.setAccessLevel(orDefault(str(req.get("accessLevel")), "EMAIL_GATED"));
        if (req.containsKey("sortOrder"))   doc.setSortOrder(asInt(req.get("sortOrder"), doc.getSortOrder()));
        if (req.containsKey("isVisible"))   doc.setVisible(!Boolean.FALSE.equals(req.get("isVisible")));
        if (req.containsKey("validUntil")) {
            String v = str(req.get("validUntil"));
            doc.setValidUntil(v == null ? null : java.time.LocalDate.parse(v.substring(0, 10)));
        }

        return documentRepository.save(doc);
    }

    @Transactional
    public void deleteDocument(Long id, Long tenantId) {
        TrustCenter tc = requireCenter(tenantId);
        documentRepository.findById(id)
                .filter(x -> x.getTrustCenterId().equals(tc.getId()))
                .ifPresent(documentRepository::delete);
        // The underlying document is untouched — it belongs to the document
        // store and is very likely attached to an audit too. Removing it from
        // the page is not deleting the file.
    }

    /** The whole page as the tenant edits it, published or not. */
    @Transactional(readOnly = true)
    public Map<String, Object> adminView(Long tenantId) {
        TrustCenter tc = centerRepository.findByTenantIdAndIsDeletedFalse(tenantId).orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("center", tc);
        if (tc == null) {
            // Not an error. A tenant that has never set one up gets empty lists
            // and a null centre, and the page offers to create it.
            out.put("sections",  List.of());
            out.put("documents", List.of());
            return out;
        }
        out.put("sections",  sectionRepository.findByTrustCenterIdOrderBySortOrderAsc(tc.getId()));
        out.put("documents", documentRepository.findByTrustCenterIdOrderBySortOrderAsc(tc.getId()));
        return out;
    }

    @Transactional
    public TrustAccessRequest approve(Long requestId, Long userId, Long tenantId) {
        TrustAccessRequest req = requireRequest(requestId, tenantId);
        if (req.getStatus() != TrustAccessRequest.Status.PENDING) {
            throw new ValidationException("Only a pending request can be approved.");
        }
        grant(req, userId);
        return requestRepository.save(req);
    }

    @Transactional
    public TrustAccessRequest deny(Long requestId, String reason, Long userId, Long tenantId) {
        TrustAccessRequest req = requireRequest(requestId, tenantId);
        req.setStatus(TrustAccessRequest.Status.DENIED);
        req.setDeniedReason(str(reason));
        req.setApprovedBy(userId);
        req.setApprovedAt(LocalDateTime.now());
        return requestRepository.save(req);
    }

    /**
     * Kills a live grant.
     *
     * The token is cleared, not just the status, so a copy of the link stops
     * resolving even if some future code path forgets to check the status.
     */
    @Transactional
    public TrustAccessRequest revoke(Long requestId, Long tenantId) {
        TrustAccessRequest req = requireRequest(requestId, tenantId);
        req.setStatus(TrustAccessRequest.Status.REVOKED);
        req.setAccessToken(null);
        req.setTokenExpiresAt(null);
        req.setRevokedAt(LocalDateTime.now());
        return requestRepository.save(req);
    }

    /**
     * Publishing is a deliberate act and refuses on an empty page.
     *
     * A trust center with no sections and no documents says "we have nothing to
     * show you about our security", which is a worse message than having no
     * trust center at all.
     */
    @Transactional
    public TrustCenter publish(Long userId, Long tenantId) {
        TrustCenter tc = centerRepository.findByTenantIdAndIsDeletedFalse(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("TrustCenter", "tenant", tenantId));

        boolean empty = sectionRepository.findByTrustCenterIdOrderBySortOrderAsc(tc.getId()).isEmpty()
                && documentRepository.findByTrustCenterIdOrderBySortOrderAsc(tc.getId()).isEmpty();
        if (empty) {
            throw new ValidationException(
                    "There is nothing on this page yet. Publishing it would tell visitors you have "
                            + "nothing to show about your security, which is worse than having no "
                            + "trust page at all.");
        }

        tc.setPublished(true);
        tc.setPublishedAt(LocalDateTime.now());
        tc.setPublishedBy(userId);
        log.info("[TRUST] Published | slug={} | tenant={} | by={}", tc.getSlug(), tenantId, userId);
        return centerRepository.save(tc);
    }

    @Transactional
    public TrustCenter unpublish(Long tenantId) {
        TrustCenter tc = centerRepository.findByTenantIdAndIsDeletedFalse(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("TrustCenter", "tenant", tenantId));
        tc.setPublished(false);
        return centerRepository.save(tc);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getStats(Long tenantId) {
        List<TrustAccessRequest> reqs = requestRepository.findByTenantId(tenantId);

        Map<String, Long> byStatus = new LinkedHashMap<>();
        Map<String, Long> byCompany = new LinkedHashMap<>();
        long live = 0;
        for (TrustAccessRequest r : reqs) {
            byStatus.merge(r.getStatus().name(), 1L, Long::sum);
            if (r.getCompany() != null) byCompany.merge(r.getCompany(), 1L, Long::sum);
            if (r.isUsable()) live++;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalRequests",  reqs.size());
        out.put("pending",        byStatus.getOrDefault("PENDING", 0L));
        // Live grants is the number worth watching: every one is somebody
        // outside the organisation who can currently fetch a confidential
        // document.
        out.put("liveGrants",     live);
        out.put("byStatus",       series(byStatus));
        out.put("distinctCompanies", byCompany.size());
        return out;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    /** Sets the grant. 32 bytes from SecureRandom — not a UUID, which is not
     *  specified to be unpredictable and has been guessed before. */
    private void grant(TrustAccessRequest req, Long approver) {
        byte[] buf = new byte[32];
        RANDOM.nextBytes(buf);
        req.setAccessToken(Base64.getUrlEncoder().withoutPadding().encodeToString(buf));
        req.setTokenExpiresAt(LocalDateTime.now().plusDays(TOKEN_DAYS));
        req.setStatus(TrustAccessRequest.Status.APPROVED);
        req.setApprovedBy(approver);
        req.setApprovedAt(LocalDateTime.now());
    }

    private TrustCenter requireCenter(Long tenantId) {
        return centerRepository.findByTenantIdAndIsDeletedFalse(tenantId)
                .orElseThrow(() -> new ValidationException(
                        "Set up your trust page before adding anything to it."));
    }

    /** URL-safe, lowercase, no leading or trailing dashes. */
    private String slugify(String raw) {
        if (raw == null) return null;
        String s = raw.toLowerCase().replaceAll("[^a-z0-9-]+", "-").replaceAll("(^-+|-+$)", "");
        return s.isEmpty() ? null : (s.length() > 80 ? s.substring(0, 80) : s);
    }

    private int asInt(Object o, Integer fallback) {
        try { return o == null ? (fallback == null ? 0 : fallback) : Integer.parseInt(String.valueOf(o)); }
        catch (Exception e) { return fallback == null ? 0 : fallback; }
    }

    private TrustAccessRequest requireRequest(Long id, Long tenantId) {
        TrustAccessRequest r = requestRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("TrustAccessRequest", id));
        if (!tenantId.equals(r.getTenantId())) {
            throw new ResourceNotFoundException("TrustAccessRequest", id);
        }
        return r;
    }

    private List<Map<String, Object>> series(Map<String, Long> counts) {
        List<Map<String, Object>> out = new ArrayList<>(counts.size());
        counts.forEach((k, v) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", k);
            row.put("value", v);
            out.add(row);
        });
        return out;
    }

    private String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * Falls back when a field was not supplied.
     *
     * Used by upsert and the section/document writers, and it was missing: the
     * helper set here grew from the public read path, where nothing needed a
     * default, and the write methods added later were written against
     * ExceptionService's helpers from memory rather than this class's.
     */
    private String orDefault(String s, String fallback) { return s == null ? fallback : s; }

    private String trim(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private Long asLong(Object o) {
        try { return o == null ? null : Long.parseLong(String.valueOf(o)); }
        catch (Exception e) { return null; }
    }
}