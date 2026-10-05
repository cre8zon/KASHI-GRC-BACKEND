package com.kashi.grc.collab.scheduler;

import com.kashi.grc.actionitem.domain.ActionItem;
import com.kashi.grc.collab.domain.CollabMeeting;
import com.kashi.grc.collab.domain.CollabMeetingAttendee;
import com.kashi.grc.collab.domain.CollabPlanItem;
import com.kashi.grc.collab.domain.CollabWorkspace;
import com.kashi.grc.notification.service.NotificationService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Collaboration reminders — the notifications nobody triggers by clicking.
 *
 *   Every 5 minutes   meetings starting in the next 15 minutes → organiser and
 *                     attendees (COLLAB_MEETING_STARTING; opens the meeting
 *                     with the call joined when it is an in-app call). Sent
 *                     once per meeting — reminder_sent_at marks it.
 *   08:10 daily       plan tasks and milestones due tomorrow, or that were due
 *                     yesterday and are not done → their owner (COLLAB_PLAN_DUE).
 *                     Open requests due tomorrow, or due yesterday → their
 *                     assignee (COLLAB_REQUEST_DUE).
 *
 * "Due yesterday" (not "overdue") so an overdue item nudges its owner once,
 * not every morning forever. Archived and deleted workspaces are skipped.
 * No transaction around the sweeps: each notification is its own (so one
 * failure cannot roll back the rest); a failed one is logged and skipped.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CollabReminderScheduler {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final NotificationService        notificationService;
    private final PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager em;

    // ══════════════════════ MEETINGS STARTING SOON ═══════════════════════════

    @Scheduled(cron = "0 */5 * * * *")
    public void meetingsStartingSoon() {
        try {
            LocalDateTime now = LocalDateTime.now();
            List<CollabMeeting> due = em.createQuery("""
                    SELECT m FROM CollabMeeting m
                    WHERE m.isDeleted = false AND m.status = :status AND m.reminderSentAt IS NULL
                      AND m.startsAt > :now AND m.startsAt <= :until
                    """, CollabMeeting.class)
                    .setParameter("status", CollabMeeting.SCHEDULED)
                    .setParameter("now", now)
                    .setParameter("until", now.plusMinutes(15))
                    .getResultList();
            if (due.isEmpty()) return;
            Map<Long, Set<Long>> people = attendees(due.stream().map(CollabMeeting::getId).toList());
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            int sent = 0;
            for (CollabMeeting m : due) {
                // Claim it first, in its own transaction: at most one reminder, even with
                // several backend instances or a send that fails part-way.
                Integer claimed = tx.execute(t -> em.createQuery(
                                "UPDATE CollabMeeting m SET m.reminderSentAt = :now WHERE m.id = :id AND m.reminderSentAt IS NULL")
                        .setParameter("now", now).setParameter("id", m.getId()).executeUpdate());
                if (claimed == null || claimed == 0) continue;
                Set<Long> to = new LinkedHashSet<>();
                if (m.getOrganizerId() != null) to.add(m.getOrganizerId());
                to.addAll(people.getOrDefault(m.getId(), Set.of()));
                String msg = "\"" + m.getTitle() + "\" starts at " + m.getStartsAt().format(TIME)
                        + ("EMBEDDED".equals(m.getProvider()) ? " — join the call from here" : "");
                for (Long u : to) {
                    if (send(u, "COLLAB_MEETING_STARTING", msg, "COLLAB_MEETING", m.getId())) sent++;
                }
            }
            log.info("[COLLAB-REMINDER] Meetings starting soon | meetings={} | notifications={}", due.size(), sent);
        } catch (Exception e) {
            log.error("[COLLAB-REMINDER] Meeting reminder sweep failed: {}", e.getMessage(), e);
        }
    }

    // ══════════════════════ DUE DATES ════════════════════════════════════════

    @Scheduled(cron = "0 10 8 * * *")   // 08:10 daily
    public void dueDates() {
        LocalDate today = LocalDate.now();
        LocalDate tomorrow = today.plusDays(1);
        LocalDate yesterday = today.minusDays(1);
        try {
            planItems(tomorrow, yesterday);
        } catch (Exception e) {
            log.error("[COLLAB-REMINDER] Plan due-date sweep failed: {}", e.getMessage(), e);
        }
        try {
            requests(tomorrow, yesterday);
        } catch (Exception e) {
            log.error("[COLLAB-REMINDER] Request due-date sweep failed: {}", e.getMessage(), e);
        }
    }

    private void planItems(LocalDate tomorrow, LocalDate yesterday) {
        List<Object[]> rows = em.createQuery("""
                SELECT i, w.name FROM CollabPlanItem i, CollabWorkspace w
                WHERE w.id = i.workspaceId AND w.isDeleted = false AND w.status <> :archived
                  AND i.isDeleted = false AND i.ownerUserId IS NOT NULL AND i.kind <> :phase
                  AND i.status <> :done AND i.plannedEnd IN (:tomorrow, :yesterday)
                """, Object[].class)
                .setParameter("archived", CollabWorkspace.ARCHIVED)
                .setParameter("phase", CollabPlanItem.KIND_PHASE)
                .setParameter("done", CollabPlanItem.DONE)
                .setParameter("tomorrow", tomorrow)
                .setParameter("yesterday", yesterday)
                .getResultList();
        int sent = 0;
        for (Object[] r : rows) {
            CollabPlanItem i = (CollabPlanItem) r[0];
            String ws = (String) r[1];
            String msg = tomorrow.equals(i.getPlannedEnd())
                    ? "\"" + i.getTitle() + "\" in " + ws + " is due tomorrow"
                    : "\"" + i.getTitle() + "\" in " + ws + " was due yesterday and is not done";
            if (send(i.getOwnerUserId(), "COLLAB_PLAN_DUE", msg, "COLLAB_WORKSPACE", i.getWorkspaceId())) sent++;
        }
        log.info("[COLLAB-REMINDER] Plan due dates | items={} | notifications={}", rows.size(), sent);
    }

    private void requests(LocalDate tomorrow, LocalDate yesterday) {
        List<Object[]> rows = em.createQuery("""
                SELECT a, w.name FROM ActionItem a, CollabWorkspace w
                WHERE a.entityType = :type AND w.id = a.entityId
                  AND w.isDeleted = false AND w.status <> :archived
                  AND a.assignedTo IS NOT NULL AND a.status IN (:open, :working)
                  AND ((a.dueAt >= :tFrom AND a.dueAt < :tTo) OR (a.dueAt >= :yFrom AND a.dueAt < :yTo))
                """, Object[].class)
                .setParameter("type", ActionItem.EntityType.COLLAB_WORKSPACE)
                .setParameter("archived", CollabWorkspace.ARCHIVED)
                .setParameter("open", ActionItem.Status.OPEN)
                .setParameter("working", ActionItem.Status.IN_PROGRESS)
                .setParameter("tFrom", tomorrow.atStartOfDay())
                .setParameter("tTo", tomorrow.plusDays(1).atStartOfDay())
                .setParameter("yFrom", yesterday.atStartOfDay())
                .setParameter("yTo", yesterday.plusDays(1).atStartOfDay())
                .getResultList();
        int sent = 0;
        for (Object[] r : rows) {
            ActionItem a = (ActionItem) r[0];
            String ws = (String) r[1];
            String msg = tomorrow.equals(a.getDueAt().toLocalDate())
                    ? "Request \"" + a.getTitle() + "\" in " + ws + " is due tomorrow"
                    : "Request \"" + a.getTitle() + "\" in " + ws + " was due yesterday";
            if (send(a.getAssignedTo(), "COLLAB_REQUEST_DUE", msg, "COLLAB_WORKSPACE", a.getEntityId())) sent++;
        }
        log.info("[COLLAB-REMINDER] Request due dates | requests={} | notifications={}", rows.size(), sent);
    }

    // ══════════════════════ HELPERS ══════════════════════════════════════════

    private Map<Long, Set<Long>> attendees(List<Long> meetingIds) {
        Map<Long, Set<Long>> out = new HashMap<>();
        em.createQuery("SELECT a FROM CollabMeetingAttendee a WHERE a.meetingId IN :ids", CollabMeetingAttendee.class)
                .setParameter("ids", meetingIds)
                .getResultList()
                .forEach(a -> out.computeIfAbsent(a.getMeetingId(), k -> new LinkedHashSet<>()).add(a.getUserId()));
        return out;
    }

    private boolean send(Long userId, String type, String message, String entityType, Long entityId) {
        if (userId == null) return false;
        try {
            notificationService.send(userId, type, message, entityType, entityId);
            return true;
        } catch (RuntimeException e) {
            log.warn("[COLLAB-REMINDER] Notification failed (non-fatal) | user={} | {} | {}", userId, type, e.getMessage());
            return false;
        }
    }
}