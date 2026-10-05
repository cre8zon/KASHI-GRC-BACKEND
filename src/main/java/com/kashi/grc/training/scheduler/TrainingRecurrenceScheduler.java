package com.kashi.grc.training.scheduler;

import com.kashi.grc.training.service.TrainingAutoAssignService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily recurrence sweep.
 *
 * Follows AuditEvidenceReminderScheduler: a single cron entry, everything in a
 * try/catch so one tenant's bad data cannot stop the rest, and a summary line
 * whether or not anything happened — a job that logs only on success is
 * indistinguishable from a job that is not running.
 *
 * 02:30 rather than 08:00 so it does not compete with the evidence reminder,
 * and so that anyone who completed training late the previous evening is
 * evaluated against a settled picture.
 *
 * ── WHY THIS ONE IS SAFE TO SCHEDULE ──────────────────────────────────────
 * No sweep was wired into Incidents or Training until now because the Issues
 * SLA escalation (GAPS item 4) re-escalates every breached item every 24 hours
 * forever. This sweep cannot do that structurally: it only creates an
 * assignment for a (person, course, cycle) triple that has none, and the unique
 * constraint makes a duplicate impossible. Running it repeatedly produces the
 * same rows as running it once, so a missed night costs nothing and there is no
 * state to reconcile.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrainingRecurrenceScheduler {

    private final TrainingAutoAssignService autoAssignService;

    @Scheduled(cron = "0 30 2 * * *")   // 02:30 daily
    public void runRecurrence() {
        log.info("[TRAINING-RECURRENCE] Starting daily sweep");
        try {
            int created = autoAssignService.runRecurrenceSweep();
            log.info("[TRAINING-RECURRENCE] Complete | {} assignment(s) created", created);
        } catch (Exception e) {
            log.error("[TRAINING-RECURRENCE] Sweep failed: {}", e.getMessage(), e);
        }
    }
}