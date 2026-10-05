package com.kashi.grc.exception_register.service;

import com.kashi.grc.common.exception.ForbiddenException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.exception.ValidationException;
import com.kashi.grc.exception_register.domain.ExceptionLink;
import com.kashi.grc.exception_register.domain.ExceptionRecord;
import com.kashi.grc.exception_register.domain.ExceptionRenewal;
import com.kashi.grc.exception_register.repository.ExceptionLinkRepository;
import com.kashi.grc.exception_register.repository.ExceptionRecordRepository;
import com.kashi.grc.exception_register.repository.ExceptionRenewalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * The exception register.
 *
 * ── WHY THE RULES ARE ENFORCED RATHER THAN DOCUMENTED ─────────────────────
 * Risk acceptance already exists four times in this codebase — Issue,
 * ActionItem, AuditFinding and the risk:accept permission — and every one of
 * them is a boolean with a note. None expires, none requires a second person,
 * none asks what compensates. The result is a pile of permanent acceptances
 * nobody can defend.
 *
 * A register that merely ASKS for those three things produces the same pile
 * with more fields. So each is a refusal:
 *
 *   1. expiresAt is mandatory and capped
 *   2. the approver cannot be the requester
 *   3. a compensating control, or an explicit statement there is none
 *
 * Each refusal below says why in the message, because an error a user does not
 * understand is one they route around.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExceptionService {

    private final ExceptionRecordRepository  exceptionRepository;
    private final ExceptionLinkRepository    linkRepository;
    private final ExceptionRenewalRepository renewalRepository;

    /**
     * The longest an exception may run before somebody looks again.
     *
     * A year, because that is the review cycle every framework assumes, and
     * because a two-year exception is a decision to change the policy that
     * nobody wrote down. Renewal is available and counted — the cap forces the
     * conversation, it does not prevent the outcome.
     */
    private static final int MAX_MONTHS = 12;

    /** Reviewed before it lapses, not after. */
    private static final int REVIEW_LEAD_DAYS = 30;

    // ═════════════════════════════════════════════════════════════════════════
    // CREATE AND SUBMIT
    // ═════════════════════════════════════════════════════════════════════════

    @Transactional
    public ExceptionRecord create(Map<String, Object> req, Long userId, Long tenantId) {
        String title = str(req.get("title"));
        String justification = str(req.get("justification"));
        if (title == null)         throw new ValidationException("An exception needs a title.");
        if (justification == null) throw new ValidationException(
                "An exception needs a justification. Approved with no reason is a decision "
                        + "nobody can defend a year from now.");

        LocalDateTime expiry = parseExpiry(req.get("expiresAt"));
        requireCompensation(req);

        ExceptionRecord e = ExceptionRecord.builder()
                .tenantId(tenantId)
                .exceptionRef(nextRef(tenantId))
                .title(title)
                .exceptionType(orDefault(str(req.get("exceptionType")), "POLICY"))
                .scopeDescription(str(req.get("scopeDescription")))
                .justification(justification)
                .compensatingControls(str(req.get("compensatingControls")))
                .hasNoCompensatingControl(Boolean.TRUE.equals(req.get("hasNoCompensatingControl")))
                .riskLevel(orDefault(str(req.get("riskLevel")), "MEDIUM"))
                .status(ExceptionRecord.Status.DRAFT)
                .expiresAt(expiry)
                .reviewDueAt(expiry.minusDays(REVIEW_LEAD_DAYS))
                .controlTags(str(req.get("controlTags")))
                .frameworkRefs(str(req.get("frameworkRefs")))
                .requestedBy(userId)
                .createdBy(userId)
                .build();

        exceptionRepository.save(e);
        log.info("[EXCEPTION] Raised | ref={} | type={} | expires={} | by={}",
                e.getExceptionRef(), e.getExceptionType(), e.getExpiresAt(), userId);
        return e;
    }

    @Transactional
    public ExceptionRecord submit(Long id, Long userId, Long tenantId) {
        ExceptionRecord e = require(id, tenantId);
        if (e.getStatus() != ExceptionRecord.Status.DRAFT) {
            throw new ValidationException("Only a draft can be submitted for approval.");
        }
        e.setStatus(ExceptionRecord.Status.PENDING_APPROVAL);
        e.setRequestedBy(e.getRequestedBy() == null ? userId : e.getRequestedBy());
        e.setRequestedAt(LocalDateTime.now());
        return exceptionRepository.save(e);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // APPROVE AND REJECT
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Approves it — unless the approver raised it.
     *
     * Self-approval is precisely how an exception register becomes a rubber
     * stamp, and it is the first thing an auditor samples for. Refusing is the
     * only version of this rule that survives a deadline.
     */
    @Transactional
    public ExceptionRecord approve(Long id, String note, Long userId, Long tenantId) {
        ExceptionRecord e = require(id, tenantId);

        if (e.getStatus() != ExceptionRecord.Status.PENDING_APPROVAL) {
            throw new ValidationException("Only an exception awaiting approval can be approved.");
        }
        if (Objects.equals(e.getRequestedBy(), userId)) {
            throw new ForbiddenException(
                    "You raised this exception, so you cannot approve it. Someone else holding "
                            + "exception:approve has to — that separation is the point of the register.");
        }
        // Re-checked at approval, not only at creation: the expiry may have
        // been edited in draft, and this is the last moment anybody looks.
        if (e.getExpiresAt() == null || e.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new ValidationException(
                    "The expiry is missing or already past. Set a future date before approving.");
        }

        e.setStatus(ExceptionRecord.Status.APPROVED);
        e.setApprovedBy(userId);
        e.setApprovedAt(LocalDateTime.now());
        e.setApprovalNote(str(note));
        if (e.getReviewDueAt() == null) {
            e.setReviewDueAt(e.getExpiresAt().minusDays(REVIEW_LEAD_DAYS));
        }

        log.info("[EXCEPTION] Approved | ref={} | by={} | requestedBy={} | expires={}",
                e.getExceptionRef(), userId, e.getRequestedBy(), e.getExpiresAt());
        return exceptionRepository.save(e);
    }

    @Transactional
    public ExceptionRecord reject(Long id, String reason, Long userId, Long tenantId) {
        if (str(reason) == null) {
            throw new ValidationException(
                    "A rejection needs a reason — the requester has to know what to change.");
        }
        ExceptionRecord e = require(id, tenantId);
        if (e.getStatus() != ExceptionRecord.Status.PENDING_APPROVAL) {
            throw new ValidationException("Only an exception awaiting approval can be rejected.");
        }
        e.setStatus(ExceptionRecord.Status.REJECTED);
        e.setRejectedReason(reason.trim());
        e.setApprovedBy(userId);
        e.setApprovedAt(LocalDateTime.now());
        return exceptionRepository.save(e);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // RENEW AND REVOKE
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Extends the expiry, recording what it was.
     *
     * renewalCount is incremented and every extension is kept, because
     * "extended four times" is a different fact from "expires in March" and
     * only the history shows it. An exception on its fifth extension is not an
     * exception, and the register should make that impossible to miss.
     */
    @Transactional
    public ExceptionRecord renew(Long id, Object newExpiryRaw, String reason,
                                 Long userId, Long tenantId) {
        if (str(reason) == null) {
            throw new ValidationException(
                    "A renewal needs a reason. Why is this still needed, and what changed?");
        }
        ExceptionRecord e = require(id, tenantId);
        if (e.getStatus() != ExceptionRecord.Status.APPROVED
                && e.getStatus() != ExceptionRecord.Status.EXPIRED) {
            throw new ValidationException(
                    "Only an approved or lapsed exception can be renewed.");
        }

        LocalDateTime previous = e.getExpiresAt();
        LocalDateTime next = parseExpiry(newExpiryRaw);

        renewalRepository.save(ExceptionRenewal.builder()
                .tenantId(tenantId)
                .exceptionId(e.getId())
                .previousExpiry(previous)
                .newExpiry(next)
                .reason(reason.trim())
                .renewedBy(userId)
                .renewedAt(LocalDateTime.now())
                .build());

        e.setExpiresAt(next);
        e.setReviewDueAt(next.minusDays(REVIEW_LEAD_DAYS));
        e.setRenewalCount((e.getRenewalCount() == null ? 0 : e.getRenewalCount()) + 1);
        // A lapsed exception that is renewed becomes live again rather than
        // needing a fresh approval — the approval already happened and the
        // renewal is itself a recorded decision.
        e.setStatus(ExceptionRecord.Status.APPROVED);

        log.info("[EXCEPTION] Renewed | ref={} | {} -> {} | renewal #{} | by={}",
                e.getExceptionRef(), previous, next, e.getRenewalCount(), userId);
        return exceptionRepository.save(e);
    }

    /** Ends it early. The reason matters more here than anywhere else: something
     *  changed, and the next person needs to know what. */
    @Transactional
    public ExceptionRecord revoke(Long id, String reason, Long userId, Long tenantId) {
        if (str(reason) == null) {
            throw new ValidationException("Revoking needs a reason — what changed?");
        }
        ExceptionRecord e = require(id, tenantId);
        if (e.getStatus() != ExceptionRecord.Status.APPROVED) {
            throw new ValidationException("Only an approved exception can be revoked.");
        }
        e.setStatus(ExceptionRecord.Status.REVOKED);
        e.setRevokedAt(LocalDateTime.now());
        e.setRevokedBy(userId);
        e.setRevokedReason(reason.trim());
        return exceptionRepository.save(e);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // THE SWEEP
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Moves approved exceptions past their date to EXPIRED.
     *
     * ── WHY THIS ONE IS SAFE TO SCHEDULE ──────────────────────────────────
     * GAPS item 4 records that the Issues SLA escalation re-escalates every
     * breached item every 24 hours forever, which is why no sweep was wired
     * into Incidents. This one cannot behave that way: it only ever moves
     * APPROVED to EXPIRED, and the query selects on status = APPROVED, so a row
     * it has already handled is outside its own input on the next run. Running
     * it repeatedly produces the same result as running it once.
     */
    @Transactional
    public int expireLapsed() {
        List<ExceptionRecord> lapsed = exceptionRepository.findLapsed(LocalDateTime.now());
        for (ExceptionRecord e : lapsed) {
            e.setStatus(ExceptionRecord.Status.EXPIRED);
            exceptionRepository.save(e);
            log.info("[EXCEPTION] Lapsed | ref={} | expired {} | renewals={}",
                    e.getExceptionRef(), e.getExpiresAt(), e.getRenewalCount());
        }
        return lapsed.size();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // LINKS AND STATS
    // ═════════════════════════════════════════════════════════════════════════

    @Transactional
    public ExceptionLink link(Long exceptionId, String entityType, Long entityId,
                              String label, Long tenantId) {
        require(exceptionId, tenantId);
        return linkRepository.save(ExceptionLink.builder()
                .tenantId(tenantId)
                .exceptionId(exceptionId)
                .entityType(entityType)
                .entityId(entityId)
                .entityLabel(label)
                .build());
    }

    /**
     * Is this thing currently covered?
     *
     * The question every other module wants to ask — an asset failing a control
     * check, a finding being raised. Only live cover counts: an expired or
     * revoked exception protects nothing, which is exactly what the four inline
     * accepted_risk flags could never express.
     */
    @Transactional(readOnly = true)
    public Optional<ExceptionRecord> activeCoverFor(String entityType, Long entityId, Long tenantId) {
        return linkRepository.findByTenantIdAndEntityTypeAndEntityId(tenantId, entityType, entityId)
                .stream()
                .map(l -> exceptionRepository.findById(l.getExceptionId()).orElse(null))
                .filter(Objects::nonNull)
                .filter(ExceptionRecord::isActiveCover)
                .findFirst();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getStats(Long tenantId) {
        List<ExceptionRecord> all = exceptionRepository.findByTenantIdAndIsDeletedFalse(tenantId);

        Map<String, Long> byStatus = new LinkedHashMap<>();
        Map<String, Long> byType   = new LinkedHashMap<>();
        Map<String, Long> byRisk   = new LinkedHashMap<>();
        long active = 0, dueReview = 0, expiringSoon = 0, renewedTwicePlus = 0;
        LocalDateTime soon = LocalDateTime.now().plusDays(30);

        for (ExceptionRecord e : all) {
            byStatus.merge(e.getStatus().name(), 1L, Long::sum);
            byType.merge(e.getExceptionType(), 1L, Long::sum);
            byRisk.merge(e.getRiskLevel(), 1L, Long::sum);
            if (e.isActiveCover()) {
                active++;
                if (e.getExpiresAt().isBefore(soon)) expiringSoon++;
            }
            if (e.isDueForReview()) dueReview++;
            // Surfaced as its own number: repeated renewal is the signal that
            // something should have been fixed or the policy changed.
            if (e.getRenewalCount() != null && e.getRenewalCount() >= 2) renewedTwicePlus++;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total",            all.size());
        out.put("active",           active);
        out.put("expiringSoon",     expiringSoon);
        out.put("dueForReview",     dueReview);
        out.put("expired",          byStatus.getOrDefault("EXPIRED", 0L));
        out.put("pendingApproval",  byStatus.getOrDefault("PENDING_APPROVAL", 0L));
        out.put("renewedTwicePlus", renewedTwicePlus);
        out.put("byStatus",     series(byStatus));
        out.put("byType",       series(byType));
        out.put("byRiskLevel",  series(byRisk));
        return out;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Parses and caps the expiry.
     *
     * Missing, past or beyond the cap are three different mistakes and each
     * gets its own message — "invalid date" tells somebody nothing about which
     * of the three they made.
     */
    private LocalDateTime parseExpiry(Object raw) {
        if (raw == null || String.valueOf(raw).isBlank()) {
            throw new ValidationException(
                    "An exception needs an expiry date. One without an end date is not an "
                            + "exception, it is an undocumented policy change.");
        }
        LocalDateTime when;
        try {
            String s = String.valueOf(raw);
            when = s.length() <= 10
                    ? LocalDate.parse(s).atStartOfDay()
                    : LocalDateTime.parse(s.replace(" ", "T"));
        } catch (Exception ex) {
            throw new ValidationException("Could not read that expiry date: " + raw);
        }
        if (when.isBefore(LocalDateTime.now())) {
            throw new ValidationException("The expiry date is in the past.");
        }
        if (when.isAfter(LocalDateTime.now().plusMonths(MAX_MONTHS))) {
            throw new ValidationException(
                    "An exception cannot run longer than " + MAX_MONTHS + " months. Set a shorter "
                            + "date and renew it if it is still needed — renewals are recorded, "
                            + "which is what makes a long-running exception visible.");
        }
        return when;
    }

    /** One or the other. Silence about what compensates is the thing to refuse. */
    private void requireCompensation(Map<String, Object> req) {
        boolean none = Boolean.TRUE.equals(req.get("hasNoCompensatingControl"));
        String controls = str(req.get("compensatingControls"));
        if (!none && controls == null) {
            throw new ValidationException(
                    "Describe what compensates for this exception, or tick that nothing does. "
                            + "\"Nothing\" is a valid answer and gets recorded as one — leaving it "
                            + "blank is not.");
        }
    }

    private String nextRef(Long tenantId) {
        long seq = exceptionRepository.nextRefSequence(tenantId);
        String candidate = String.format("EXC-%d-%04d", LocalDate.now().getYear(), seq);
        int guard = 0;
        while (exceptionRepository.existsByExceptionRefAndTenantId(candidate, tenantId)
                && guard++ < 1000) {
            candidate = String.format("EXC-%d-%04d", LocalDate.now().getYear(), ++seq);
        }
        return candidate;
    }

    private ExceptionRecord require(Long id, Long tenantId) {
        ExceptionRecord e = exceptionRepository.findById(id)
                .filter(x -> !x.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("Exception", id));
        if (!tenantId.equals(e.getTenantId())) {
            throw new ResourceNotFoundException("Exception", id);
        }
        return e;
    }

    /** [{key, value}] — a list, because a bare map has no stable chart order. */
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

    private String orDefault(String s, String fallback) { return s == null ? fallback : s; }
}
