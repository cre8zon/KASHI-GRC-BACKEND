package com.kashi.grc.exception_register.scheduler;

import com.kashi.grc.exception_register.service.ExceptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Lapses approved exceptions whose date has passed.
 *
 * 02:00 daily, between the metric capture at 01:15 and the training recurrence
 * sweep at 02:30 — so an exception that lapses tonight is EXPIRED before
 * anything else reads the register in the morning.
 *
 * ── WHY THIS IS SAFE TO SCHEDULE AND THE ISSUES ESCALATION IS NOT ─────────
 * GAPS item 4 records that the Issues SLA escalation re-escalates every
 * breached item every 24 hours forever. This cannot behave that way: it moves
 * APPROVED to EXPIRED, and its query selects only APPROVED rows, so anything it
 * has handled is outside its own input next time. Idempotent by construction
 * rather than by a cap, which also means a missed night costs nothing.
 *
 * Follows AuditEvidenceReminderScheduler: one cron entry, everything wrapped,
 * and a summary line whether or not anything happened — a job that logs only on
 * success is indistinguishable from a job that is not running.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExceptionExpiryScheduler {

    private final ExceptionService exceptionService;

    @Scheduled(cron = "0 0 2 * * *")
    public void expireLapsed() {
        log.info("[EXCEPTION-EXPIRY] Starting daily sweep");
        try {
            int n = exceptionService.expireLapsed();
            log.info("[EXCEPTION-EXPIRY] Complete | {} exception(s) lapsed", n);
        } catch (Exception e) {
            log.error("[EXCEPTION-EXPIRY] Sweep failed: {}", e.getMessage(), e);
        }
    }
}
