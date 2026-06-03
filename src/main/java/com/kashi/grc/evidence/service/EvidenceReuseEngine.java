package com.kashi.grc.evidence.service;

import com.kashi.grc.audit.repository.AuditControlInstanceRepository;
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
import java.util.List;
import java.util.Map;

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
    private final AssessmentQuestionInstanceRepository assessmentQuestionRepo;
    private final List<EvidenceTagMatcher>             tagMatchers;
    private final NotificationService                  notificationService;

    // ── MANUAL propagation (async) ────────────────────────────────────────────

    @Async
    @Transactional
    public void propagate(Long evidenceRecordId) {
        propagateInternal(evidenceRecordId, false);
    }

    // ── AUTOMATED propagation (called synchronously by IntegrationRunner) ─────

    @Transactional
    public void propagateAutomated(Long evidenceRecordId, boolean isPass) {
        propagateInternal(evidenceRecordId, isPass);
    }

    private void propagateInternal(Long evidenceRecordId, boolean automatedPass) {
        EvidenceRecord record = evidenceRecordRepository.findById(evidenceRecordId).orElse(null);
        if (record == null || record.getControlTag() == null) {
            log.debug("[EVIDENCE-REUSE] No tag on record {} — skipping", evidenceRecordId);
            return;
        }

        String tag      = record.getControlTag();
        Long   tenantId = record.getTenantId();
        // AUTOMATION_VERIFIED for automated PASS, PENDING_REVIEW for everything else
        EvidenceLink.Status linkStatus = automatedPass
                ? EvidenceLink.Status.AUTOMATION_VERIFIED
                : EvidenceLink.Status.PENDING_REVIEW;

        int newLinks = 0;

        log.info("[EVIDENCE-REUSE] Propagating | recordId={} | tag={} | status={} | tenantId={}",
                evidenceRecordId, tag, linkStatus, tenantId);

        // ── Audit control instances ───────────────────────────────────────────
        for (Map<String, Object> match : auditControlRepo.findByTenantIdAndControlTagSnapshot(tenantId, tag)) {
            Long instanceId = (Long) match.get("id");
            Long auditorId  = (Long) match.get("assignedAuditorId");
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
        link.setStatus(EvidenceLink.Status.REJECTED);
        link.setReviewedBy(reviewedBy);
        link.setReviewedAt(LocalDateTime.now());
        link.setReviewerNote(note);
        evidenceLinkRepository.save(link);
    }

    @Transactional
    public void expireStaleLinks() {
        evidenceRecordRepository.findByExpiredFalseAndValidUntilBefore(LocalDateTime.now())
                .forEach(record -> {
                    record.setExpired(true);
                    evidenceRecordRepository.save(record);
                    int count = evidenceLinkRepository.expireByEvidenceRecordId(record.getId());
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