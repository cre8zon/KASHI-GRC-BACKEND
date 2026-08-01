package com.kashi.grc.audit.service;

import com.kashi.grc.audit.domain.AuditControlInstance;
import com.kashi.grc.audit.domain.AuditEngagement;
import com.kashi.grc.audit.domain.AuditPolicyInstance;
import com.kashi.grc.audit.domain.AuditTestInstance;
import com.kashi.grc.audit.repository.AuditControlInstanceRepository;
import com.kashi.grc.audit.repository.AuditEngagementRepository;
import com.kashi.grc.audit.repository.AuditPolicyInstanceRepository;
import com.kashi.grc.audit.repository.AuditTestInstanceRepository;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.evidence.domain.EvidenceLink;
import com.kashi.grc.evidence.domain.EvidenceRecord;
import com.kashi.grc.evidence.repository.EvidenceRecordRepository;
import com.kashi.grc.evidence.service.EvidenceReuseEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * KashiLink — the PULL side of the evidence reuse engine.
 *
 * ── WHY THIS EXISTS ──────────────────────────────────────────────────────────
 * EvidenceReuseEngine is push-only. It fires once, at evidence-creation time,
 * fans the record out to everything matching, and never runs again.
 *
 * That produces a backwards asymmetry across engagements:
 *
 *   Evidence uploaded AFTER a new engagement exists  → links into it fine
 *   Evidence uploaded BEFORE it existed              → never links
 *
 * So the second audit — the one where "collect once, comply many" is supposed to
 * pay off — starts completely empty and the auditee re-uploads everything they
 * already provided last year. This service closes that gap.
 *
 * ── WHY IT IS AN EXPLICIT ACTION, NOT AUTOMATIC ──────────────────────────────
 * Running this automatically at instantiation would silently populate a fresh
 * audit with prior-period evidence. An external reviewer would object: evidence
 * appeared in scope with no documented decision behind it.
 *
 * So it is invoked deliberately by the lead auditor, it records who ran it and
 * when, and every link it creates lands as PENDING_REVIEW — never ACCEPTED. The
 * auditor still has to look at each one and decide whether prior-period evidence
 * is good enough for this period. That decision is the audit trail.
 *
 * ── SCOPING ──────────────────────────────────────────────────────────────────
 * Candidate evidence must:
 *   1. belong to this tenant
 *   2. carry a tag that some instance in this engagement carries
 *   3. not be expired
 *   4. have a validity window overlapping this engagement's audit period
 *
 * Rule 4 is what stops a 2024 penetration test report from being pulled into a
 * 2027 audit as if it were current.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditEvidenceBackfillService {

    private final AuditEngagementRepository      engagementRepository;
    private final AuditControlInstanceRepository controlInstanceRepository;
    private final AuditTestInstanceRepository    testInstanceRepository;
    private final AuditPolicyInstanceRepository  policyInstanceRepository;
    private final EvidenceRecordRepository       evidenceRecordRepository;
    private final EvidenceReuseEngine            reuseEngine;

    /**
     * Preview what a backfill would link, without writing anything.
     * Drives the confirmation dialog — the auditor sees the count and the tags
     * before committing.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> preview(Long engagementId, Long tenantId) {
        return run(engagementId, tenantId, null, true);
    }

    /** Execute the backfill. Every link created is PENDING_REVIEW. */
    @Transactional
    public Map<String, Object> backfill(Long engagementId, Long tenantId, Long requestedBy) {
        return run(engagementId, tenantId, requestedBy, false);
    }

    // ─────────────────────────────────────────────────────────────────────────

    private Map<String, Object> run(Long engagementId, Long tenantId,
                                    Long requestedBy, boolean dryRun) {

        AuditEngagement engagement = engagementRepository.findById(engagementId)
                .filter(e -> tenantId.equals(e.getTenantId()))
                .orElseThrow(() -> new ResourceNotFoundException("AuditEngagement", engagementId));

        // ── 1. Collect every tag this engagement can be reached by ───────────
        List<AuditControlInstance> controls =
                controlInstanceRepository.findByEngagementId(engagementId);
        List<AuditTestInstance> tests =
                testInstanceRepository.findByEngagementIdOrderByTestNameSnapshotAsc(engagementId);
        List<AuditPolicyInstance> policies =
                policyInstanceRepository.findByEngagementIdOrderByTitleSnapshotAsc(engagementId);

        Set<String> tags = new HashSet<>();
        controls.forEach(c -> addTag(tags, c.getControlTagSnapshot()));
        tests.forEach(t -> addTag(tags, t.getControlTagSnapshot()));
        policies.forEach(p -> {
            if (p.getControlTagsSnapshot() != null) {
                for (String t : p.getControlTagsSnapshot().split(",")) addTag(tags, t);
            }
        });

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("engagementId",   engagementId);
        result.put("engagementName", engagement.getName());
        result.put("distinctTags",   tags.size());
        result.put("dryRun",         dryRun);

        if (tags.isEmpty()) {
            log.info("[KASHILINK-PULL] engagementId={} carries no tags — nothing to pull", engagementId);
            result.put("candidateRecords", 0);
            result.put("linksCreated",     0);
            result.put("skippedOutOfPeriod", 0);
            result.put("tags",             List.of());
            return result;
        }

        // ── 2. Candidate evidence already held by this tenant ────────────────
        List<EvidenceRecord> candidates = evidenceRecordRepository
                .findByTenantIdAndControlTagInAndExpiredFalse(tenantId, tags);

        int linksCreated = 0;
        int outOfPeriod  = 0;
        List<String> tagsHit = new ArrayList<>();

        for (EvidenceRecord record : candidates) {

            // ── 3. Audit-period gate ─────────────────────────────────────────
            if (!EvidenceReuseEngine.periodsOverlap(record, engagement)) {
                outOfPeriod++;
                continue;
            }

            String tag = record.getControlTag();
            boolean hit = false;

            for (AuditControlInstance c : controls) {
                if (tag.equalsIgnoreCase(c.getControlTagSnapshot())) {
                    hit = true;
                    if (!dryRun && reuseEngine.linkRecordTo(record.getId(),
                            "AUDIT_CONTROL_INSTANCE", c.getId(), tenantId,
                            EvidenceLink.Status.PENDING_REVIEW)) {
                        linksCreated++;
                    } else if (dryRun) {
                        linksCreated++;
                    }
                }
            }

            for (AuditTestInstance t : tests) {
                // AUTOMATED tests are fed by checkKey routing, never by tag —
                // same carve-out AuditTestEvidenceMatcher applies on the push side.
                if ("AUTOMATED".equalsIgnoreCase(t.getAutomationTypeSnapshot())) continue;
                if (tag.equalsIgnoreCase(t.getControlTagSnapshot())) {
                    hit = true;
                    if (!dryRun && reuseEngine.linkRecordTo(record.getId(),
                            "AUDIT_TEST_INSTANCE", t.getId(), tenantId,
                            EvidenceLink.Status.PENDING_REVIEW)) {
                        linksCreated++;
                    } else if (dryRun) {
                        linksCreated++;
                    }
                }
            }

            for (AuditPolicyInstance p : policies) {
                if (containsTag(p.getControlTagsSnapshot(), tag)) {
                    hit = true;
                    if (!dryRun && reuseEngine.linkRecordTo(record.getId(),
                            "AUDIT_POLICY_INSTANCE", p.getId(), tenantId,
                            EvidenceLink.Status.PENDING_REVIEW)) {
                        linksCreated++;
                    } else if (dryRun) {
                        linksCreated++;
                    }
                }
            }

            if (hit && !tagsHit.contains(tag)) tagsHit.add(tag);
        }

        result.put("candidateRecords",   candidates.size());
        result.put("linksCreated",       linksCreated);
        result.put("skippedOutOfPeriod", outOfPeriod);
        result.put("tags",               tagsHit);

        log.info("[KASHILINK-PULL] {} | engagementId={} | tenantId={} | tags={} | candidates={} "
                        + "| links={} | outOfPeriod={} | requestedBy={}",
                dryRun ? "PREVIEW" : "EXECUTED", engagementId, tenantId, tags.size(),
                candidates.size(), linksCreated, outOfPeriod, requestedBy);

        return result;
    }

    private static void addTag(Set<String> tags, String raw) {
        if (raw != null && !raw.isBlank()) tags.add(raw.toUpperCase().trim());
    }

    /** Exact membership test on a comma-separated tag list. */
    private static boolean containsTag(String tagList, String searchTag) {
        if (tagList == null || tagList.isBlank()) return false;
        for (String t : tagList.split(",")) {
            if (searchTag.equalsIgnoreCase(t.trim())) return true;
        }
        return false;
    }
}