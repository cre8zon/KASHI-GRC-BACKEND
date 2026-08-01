package com.kashi.grc.evidence.service;

import com.kashi.grc.audit.domain.AuditEngagement;
import com.kashi.grc.audit.repository.AuditControlInstanceRepository;
import com.kashi.grc.audit.repository.AuditEngagementRepository;
import com.kashi.grc.assessment.repository.AssessmentQuestionInstanceRepository;
import com.kashi.grc.evidence.domain.EvidenceLink;
import com.kashi.grc.evidence.domain.EvidenceRecord;
import com.kashi.grc.evidence.repository.EvidenceLinkRepository;
import com.kashi.grc.evidence.repository.EvidenceRecordRepository;
import com.kashi.grc.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * EvidenceReuseEngine — propagates evidence to all matching entities via tag.
 *
 * Two propagation paths:
 *
 *   propagate(recordId)          — MANUAL upload → EvidenceLink.PENDING_REVIEW
 *   propagateAutomated(recordId, isPass) — AUTOMATED collection:
 *       isPass=true  → EvidenceLink.AUTOMATION_VERIFIED (no human gate)
 *       isPass=false → EvidenceLink.PENDING_REVIEW (auditor must document exception)
 *
 * Modules register by implementing EvidenceTagMatcher SPI (@Component).
 * Engine discovers all implementations automatically — zero engine changes per module.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvidenceReuseEngine {

    private final EvidenceRecordRepository             evidenceRecordRepository;
    private final EvidenceLinkRepository               evidenceLinkRepository;
    private final AuditControlInstanceRepository       auditControlRepo;
    private final AuditEngagementRepository            auditEngagementRepo;
    private final AssessmentQuestionInstanceRepository assessmentQuestionRepo;
    private final List<EvidenceTagMatcher>             tagMatchers;
    private final NotificationService                  notificationService;

    /**
     * Engagements that may still receive new evidence.
     *
     * FINAL_REPORT / CLOSED / CANCELLED are excluded: once the report is issued,
     * evidence appearing in scope without a documented decision is exactly what
     * a peer reviewer objects to. MANAGEMENT_RESPONSE is included deliberately —
     * that phase is when remediation evidence legitimately arrives.
     */
    private static final Set<AuditEngagement.Status> OPEN_FOR_EVIDENCE = EnumSet.of(
            AuditEngagement.Status.PLANNING,
            AuditEngagement.Status.FIELDWORK,
            AuditEngagement.Status.EVIDENCE_REVIEW,
            AuditEngagement.Status.DRAFT_REPORT,
            AuditEngagement.Status.MANAGEMENT_RESPONSE
    );

    // ── KashiLink propagation entry point ─────────────────────────────────────

    /**
     * Propagate a COMMITTED evidence record to every matching entity instance.
     *
     * Deliberately synchronous — the caller owns threading:
     *   MANUAL    → EvidencePropagationListener (@Async + AFTER_COMMIT)
     *   AUTOMATED → IntegrationRunner (inline; record already visible in-tx)
     *
     * This method used to be @Async, which raced the caller's uncommitted
     * transaction: the pool thread ran findById() before EvidenceService.create()
     * had committed, found nothing, and logged "No tag on record N — skipping"
     * for a record that definitely had a tag.
     *
     * @return number of NEW links created (0 is the signature of tag drift)
     */
    @Transactional
    public int propagate(Long evidenceRecordId, boolean automatedPass) {
        return propagateInternal(evidenceRecordId, automatedPass);
    }

    // ── AUTOMATED propagation (called synchronously by IntegrationRunner) ─────

    @Transactional
    public int propagateAutomated(Long evidenceRecordId, boolean isPass) {
        return propagateInternal(evidenceRecordId, isPass);
    }

    private int propagateInternal(Long evidenceRecordId, boolean automatedPass) {
        EvidenceRecord record = evidenceRecordRepository.findById(evidenceRecordId).orElse(null);
        if (record == null || record.getControlTag() == null) {
            log.debug("[EVIDENCE-REUSE] No tag on record {} — skipping", evidenceRecordId);
            return 0;
        }

        String tag      = record.getControlTag();
        Long   tenantId = record.getTenantId();
        // AUTOMATION_VERIFIED for automated PASS, PENDING_REVIEW for everything else
        EvidenceLink.Status linkStatus = automatedPass
                ? EvidenceLink.Status.AUTOMATION_VERIFIED
                : EvidenceLink.Status.PENDING_REVIEW;

        int newLinks = 0;

        // KashiLink: which engagements may receive this record. Filters out closed
        // engagements AND engagements whose audit period does not overlap the
        // evidence validity window — without this, a 2024 pen-test report with a
        // null validUntil links into a 2027 audit as current evidence.
        Set<Long> eligibleEngagements = eligibleEngagements(tenantId, record);

        log.info("[EVIDENCE-REUSE] Propagating | recordId={} | tag={} | status={} | tenantId={} | eligibleEngagements={}",
                evidenceRecordId, tag, linkStatus, tenantId, eligibleEngagements.size());

        // ── Audit control instances ───────────────────────────────────────────
        for (Map<String, Object> match : auditControlRepo.findByTenantIdAndControlTagSnapshot(tenantId, tag)) {
            Long instanceId   = (Long) match.get("id");
            Long auditorId    = (Long) match.get("assignedAuditorId");
            Long engagementId = (Long) match.get("engagementId");
            if (engagementId != null && !eligibleEngagements.contains(engagementId)) continue;
            if (createLink(record, "AUDIT_CONTROL_INSTANCE", instanceId, tenantId, linkStatus)) {
                newLinks++;
                if (auditorId != null && linkStatus == EvidenceLink.Status.PENDING_REVIEW) {
                    notificationService.send(auditorId, "EVIDENCE_AUTO_LINKED",
                            "Evidence '" + record.getTitle() + "' was auto-linked. Please review.",
                            "EVIDENCE_RECORD", evidenceRecordId);
                }
            }
        }

        // ── Assessment question instances ─────────────────────────────────────
        for (Map<String, Object> match : assessmentQuestionRepo.findByTenantIdAndQuestionTagSnapshot(tenantId, tag)) {
            Long instanceId  = (Long) match.get("id");
            Long responderId = (Long) match.get("assignedUserId");
            if (createLink(record, "ASSESSMENT_QUESTION_INSTANCE", instanceId, tenantId, linkStatus)) {
                newLinks++;
                if (responderId != null && linkStatus == EvidenceLink.Status.PENDING_REVIEW) {
                    notificationService.send(responderId, "EVIDENCE_AUTO_LINKED",
                            "Evidence '" + record.getTitle() + "' was auto-linked to your assessment question.",
                            "EVIDENCE_RECORD", evidenceRecordId);
                }
            }
        }

        // ── SPI matchers (AuditTestEvidenceMatcher, AuditPolicyEvidenceMatcher...) ──
        for (EvidenceTagMatcher matcher : tagMatchers) {
            try {
                for (EvidenceTagMatcher.MatchResult r : matcher.findMatches(tag, tenantId)) {
                    // null engagementId = not engagement-scoped, always allowed
                    if (r.engagementId() != null && !eligibleEngagements.contains(r.engagementId())) continue;
                    if (createLink(record, r.entityType(), r.entityId(), tenantId, linkStatus)) {
                        newLinks++;
                        if (r.responsibleUserId() != null && linkStatus == EvidenceLink.Status.PENDING_REVIEW) {
                            notificationService.send(r.responsibleUserId(), "EVIDENCE_AUTO_LINKED",
                                    "Evidence '" + record.getTitle() + "' was auto-linked. Please review.",
                                    "EVIDENCE_RECORD", evidenceRecordId);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[EVIDENCE-REUSE] Matcher {} failed: {}", matcher.getClass().getSimpleName(), e.getMessage());
            }
        }

        if (newLinks > 0) {
            record.setLinkCount(record.getLinkCount() + newLinks);
            evidenceRecordRepository.save(record);
        }

        log.info("[EVIDENCE-REUSE] Done | recordId={} | newLinks={}", evidenceRecordId, newLinks);
        return newLinks;
    }

    // ── Accept / Reject ───────────────────────────────────────────────────────

    @Transactional
    public void acceptLink(Long linkId, Long reviewedBy, String note, Long tenantId) {
        EvidenceLink link = evidenceLinkRepository.findByIdAndTenantId(linkId, tenantId)
                .orElseThrow(() -> new com.kashi.grc.common.exception.ResourceNotFoundException("EvidenceLink", linkId));
        link.setStatus(EvidenceLink.Status.ACCEPTED);
        link.setReviewedBy(reviewedBy);
        link.setReviewedAt(LocalDateTime.now());
        link.setReviewerNote(note);
        evidenceLinkRepository.save(link);
    }

    @Transactional
    public void rejectLink(Long linkId, Long reviewedBy, String note, Long tenantId) {
        EvidenceLink link = evidenceLinkRepository.findByIdAndTenantId(linkId, tenantId)
                .orElseThrow(() -> new com.kashi.grc.common.exception.ResourceNotFoundException("EvidenceLink", linkId));
        EvidenceLink.Status previousStatus = link.getStatus();
        link.setStatus(EvidenceLink.Status.REJECTED);
        link.setReviewedBy(reviewedBy);
        link.setReviewedAt(LocalDateTime.now());
        link.setReviewerNote(note);
        evidenceLinkRepository.save(link);

        // KashiLink: linkCount was only ever incremented, so the counter shown on
        // the evidence record drifted upward and never came back down.
        if (previousStatus != EvidenceLink.Status.REJECTED) {
            evidenceRecordRepository.findById(link.getEvidenceRecordId()).ifPresent(r -> {
                r.setLinkCount(Math.max(0, r.getLinkCount() - 1));
                evidenceRecordRepository.save(r);
            });
        }
    }

    @Transactional
    public void expireStaleLinks() {
        evidenceRecordRepository.findByExpiredFalseAndValidUntilBefore(LocalDateTime.now())
                .forEach(record -> {
                    record.setExpired(true);
                    evidenceRecordRepository.save(record);
                    int count = evidenceLinkRepository.expireByEvidenceRecordId(record.getId());
                    record.setLinkCount(0);   // KashiLink: every live link is now EXPIRED
                    evidenceRecordRepository.save(record);
                    log.info("[EVIDENCE-REUSE] Expired {} links for recordId={}", count, record.getId());
                });
    }

    public List<EvidenceLink> getLinksForEntity(String entityType, Long entityId, Long tenantId) {
        return evidenceLinkRepository.findByTargetEntityTypeAndTargetEntityIdAndTenantId(entityType, entityId, tenantId);
    }

    public List<EvidenceLink> getLinksForRecord(Long evidenceRecordId, Long tenantId) {
        return evidenceLinkRepository.findByEvidenceRecordIdAndTenantId(evidenceRecordId, tenantId);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * KashiLink PULL side — link an EXISTING evidence record to a target entity.
     *
     * Propagation is push-only: it fires once, when evidence is created, and never
     * runs again. That leaves a new engagement starting empty even when the tenant
     * already holds evidence under the same tags. This is the entry point the
     * backfill uses to close that gap.
     *
     * @return true if a new link was created, false if one already existed
     */
    @Transactional
    public boolean linkRecordTo(Long evidenceRecordId, String targetEntityType, Long targetEntityId,
                                Long tenantId, EvidenceLink.Status status) {
        EvidenceRecord record = evidenceRecordRepository.findById(evidenceRecordId).orElse(null);
        if (record == null || !tenantId.equals(record.getTenantId())) return false;

        if (createLink(record, targetEntityType, targetEntityId, tenantId, status)) {
            record.setLinkCount(record.getLinkCount() + 1);
            evidenceRecordRepository.save(record);
            return true;
        }
        return false;
    }

    /** Engagement IDs this record is allowed to reach. */
    private Set<Long> eligibleEngagements(Long tenantId, EvidenceRecord record) {
        Set<Long> ids = new HashSet<>();
        for (AuditEngagement e : auditEngagementRepo.findByTenantId(tenantId)) {
            if (e.getStatus() != null && !OPEN_FOR_EVIDENCE.contains(e.getStatus())) continue;
            if (!periodsOverlap(record, e)) continue;
            ids.add(e.getId());
        }
        return ids;
    }

    /**
     * Evidence is in scope for an engagement when its validity window overlaps the
     * audit period. Nulls are open-ended on both sides: evidence with no expiry is
     * treated as still valid, and an engagement with no planned dates accepts
     * anything. That keeps existing data working while gating the cases that matter.
     */
    public static boolean periodsOverlap(EvidenceRecord r, AuditEngagement e) {
        LocalDateTime periodStart = e.getPlannedStart();
        LocalDateTime periodEnd   = e.getPlannedEnd();

        // Evidence expired before the audit period began
        if (r.getValidUntil() != null && periodStart != null
                && r.getValidUntil().isBefore(periodStart)) {
            return false;
        }
        // Evidence only becomes valid after the audit period ended
        if (r.getValidFrom() != null && periodEnd != null
                && r.getValidFrom().isAfter(periodEnd)) {
            return false;
        }
        return true;
    }

    private boolean createLink(EvidenceRecord record, String targetType, Long targetId,
                               Long tenantId, EvidenceLink.Status status) {
        if (evidenceLinkRepository.existsByEvidenceRecordIdAndTargetEntityTypeAndTargetEntityId(
                record.getId(), targetType, targetId)) {
            return false;
        }
        try {
            evidenceLinkRepository.save(EvidenceLink.builder()
                    .evidenceRecordId(record.getId())
                    .targetEntityType(targetType)
                    .targetEntityId(targetId)
                    .tenantId(tenantId)
                    .status(status)
                    .autoLinked(true)
                    .matchedTagSnapshot(record.getControlTag())
                    .linkedAt(LocalDateTime.now())
                    .build());
            return true;
        } catch (Exception e) {
            log.debug("[EVIDENCE-REUSE] Duplicate link skipped for {}:{}", targetType, targetId);
            return false;
        }
    }
}