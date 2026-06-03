package com.kashi.grc.evidence.scheduler;

import com.kashi.grc.evidence.service.EvidenceReuseEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * EvidenceExpiryScheduler — daily job that expires stale EvidenceLinks.
 *
 * Runs at 02:00 every night.
 * Finds all EvidenceRecords where validUntil < NOW() and expired=false.
 * Marks the record expired and flips all its PENDING_REVIEW/ACCEPTED
 * links to EXPIRED status.
 *
 * This ensures compliance posture is accurate — an expired certificate
 * no longer counts as evidence for a control.
 *
 * The EvidenceReuseEngine.expireStaleLinks() method does the actual work.
 * This class is purely the scheduler trigger.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvidenceExpiryScheduler {

    private final EvidenceReuseEngine engine;

    @Scheduled(cron = "0 0 2 * * *")   // 02:00 daily
    public void expireStaleEvidence() {
        log.info("[EVIDENCE-SCHEDULER] Running evidence expiry check");
        try {
            engine.expireStaleLinks();
        } catch (Exception e) {
            log.error("[EVIDENCE-SCHEDULER] Expiry check failed: {}", e.getMessage(), e);
        }
    }
}
