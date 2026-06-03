package com.kashi.grc.audit.scheduler;

import com.kashi.grc.audit.domain.AuditControlInstance;
import com.kashi.grc.audit.repository.AuditControlInstanceRepository;
import com.kashi.grc.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * AuditEvidenceReminderScheduler — daily job for evidence due date reminders.
 *
 * Runs at 08:00 every morning.
 * Finds all AuditControlInstance rows where:
 *   - auditeeAssignedUserId IS NOT NULL
 *   - auditeeEvidenceSubmitted = false
 *   - evidenceDueDate IS NOT NULL
 *   - evidenceDueDate <= today + 3 days (approaching or overdue)
 *
 * Sends AUDIT_EVIDENCE_DUE_SOON for approaching and AUDIT_EVIDENCE_OVERDUE
 * for past-due controls.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditEvidenceReminderScheduler {

    private final AuditControlInstanceRepository controlInstanceRepository;
    private final NotificationService            notificationService;

    @Scheduled(cron = "0 0 8 * * *")   // 08:00 daily
    public void sendEvidenceReminders() {
        log.info("[AUDIT-REMINDER] Running evidence due date reminder check");
        try {
            LocalDate today    = LocalDate.now();
            LocalDate deadline = today.plusDays(3);

            List<AuditControlInstance> dueSoon =
                    controlInstanceRepository.findDueForEvidenceReminder(deadline);

            int reminded = 0;
            for (AuditControlInstance ctrl : dueSoon) {
                LocalDate due     = ctrl.getEvidenceDueDate();
                boolean   overdue = due.isBefore(today);

                String subject = overdue
                        ? "AUDIT_EVIDENCE_OVERDUE"
                        : "AUDIT_EVIDENCE_DUE_SOON";
                String message = overdue
                        ? "Evidence overdue for control: " + ctrl.getControlNameSnapshot()
                          + " (was due " + due + ")"
                        : "Evidence due in " + ChronoUnit.DAYS.between(today, due)
                          + " day(s) for control: " + ctrl.getControlNameSnapshot()
                          + " (due " + due + ")";

                notificationService.send(
                        ctrl.getAuditeeAssignedUserId(),
                        subject,
                        message,
                        "AUDIT_CONTROL_INSTANCE",
                        ctrl.getId()
                );
                reminded++;
            }

            log.info("[AUDIT-REMINDER] Sent {} evidence reminder(s) | today={} | window=+3d",
                    reminded, today);

        } catch (Exception e) {
            log.error("[AUDIT-REMINDER] Reminder job failed: {}", e.getMessage(), e);
        }
    }
}