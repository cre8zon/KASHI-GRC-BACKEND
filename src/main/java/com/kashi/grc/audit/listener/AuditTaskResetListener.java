package com.kashi.grc.audit.listener;

import com.kashi.grc.audit.domain.AuditSectionInstance;
import com.kashi.grc.audit.repository.AuditSectionInstanceRepository;
import com.kashi.grc.audit.repository.AuditorSectionSubmissionRepository;
import com.kashi.grc.workflow.event.TaskResetEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Clears the audit module's OWN section locks when an admin resets an audit task.
 *
 * The audit-side twin of AssessmentTaskResetListener, and it exists for exactly
 * the same reason.
 *
 * ── THE BUG ───────────────────────────────────────────────────────────────────
 *
 * WorkflowEngineService.resetTask does everything the ENGINE owns: the task goes
 * back to IN_PROGRESS, its section gates are re-armed, its task_section_items
 * are cleared. For a module that keeps no lock of its own, that is the whole
 * job.
 *
 * Audit keeps three locks the engine cannot see, all on audit_section_instances:
 *
 *   submitted_at / submitted_by                  the assigned auditor's lock
 *   auditee_submitted_at / auditee_submitted_by  the assigned auditee's lock
 *   testing_submitted_at / testing_submitted_by  the tester's lock
 *
 * plus one table of its own:
 *
 *   auditor_section_submissions                  per-auditor rows on a section
 *
 * So an admin resets an audit task, the owner sees it in their inbox again, and
 * the section is still submitted — no control to press, a re-armed gate with
 * nothing that can fire it, and a step that cannot complete by any route. The
 * task was reopened; the work was not.
 *
 * ── WHY AN EVENT AND NOT A CALL ───────────────────────────────────────────────
 *
 * The workflow engine must not know what an audit section is. Same contract
 * SectionItemsNeededEvent uses in the other direction: the engine announces
 * that something happened and each module cleans up the state it owns.
 * Publishing an event a module does not consume changes nothing for that
 * module, which is why adding this one affects nothing outside audit.
 *
 * ── SCOPED TO THE PERSON WHOSE TASK WAS RESET ─────────────────────────────────
 *
 * A reset is for ONE task, and one task belongs to ONE person. Clearing every
 * auditor's lock because a colleague's task was reset would be the multi-actor
 * bug in a new place. Every query here filters on assignedUserId, and a null
 * one does nothing: an unassigned task has no owner whose locks could be meant.
 *
 * ── AND TO THE RIGHT LOCK ─────────────────────────────────────────────────────
 *
 * This differs from the assessment listener on ONE point, deliberately.
 *
 * AssessmentTaskResetListener reads stepAction (FILL → vendor side, EVALUATE →
 * org side) to choose which lock a reset refers to. Here the OWNERSHIP COLUMNS
 * already answer that question as recorded fact:
 *
 *   assigned_auditor_id       is who owns submitted_at on that section
 *   auditee_assigned_user_id  is who owns auditee_submitted_at
 *
 * So this clears the locks THIS USER actually holds, rather than inferring a
 * side from a step action and hoping the audit workflow's actions are named the
 * way the TPRM one's are. The schema is the stronger signal, and using it means
 * there is nothing here to get wrong when a new audit step action is added.
 *
 * A person is the assigned auditor on a section or its assigned auditee, not
 * both, so in practice this touches exactly one side.
 *
 * testing_submitted_at is cleared only on sections where the user is the
 * assigned AUDITOR, because testing is an auditor act — an auditee's reset must
 * not undo a tester's results.
 *
 * ── FAILURE IS NOT FATAL ──────────────────────────────────────────────────────
 *
 * Listeners run inside resetTask's transaction, so throwing would roll the
 * reset back. Being unable to clear a module lock must not leave the admin with
 * no reset at all. Every path catches and logs, exactly as the assessment
 * listener does.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditTaskResetListener {

    /**
     * WorkflowInstance.entityType for the audit workflow.
     *
     * AUDIT_ENGAGEMENT is the one whose entityId is the engagement, which is
     * what audit_section_instances.engagement_id is keyed on — see
     * WorkflowEntityResolverRegistry, which registers AUDIT_PROJECT,
     * AUDIT_POLICY and AUDIT_ENGAGEMENT. The other two own no section locks, so
     * a reset on them has nothing here to clear and returns early.
     */
    private static final String ENTITY_TYPE = "AUDIT_ENGAGEMENT";

    private final AuditSectionInstanceRepository     sectionInstanceRepository;
    private final AuditorSectionSubmissionRepository auditorSubmissionRepository;

    @EventListener
    @Transactional
    public void onTaskReset(TaskResetEvent event) {
        try {
            if (event.assignedUserId() == null) {
                log.debug("[AUDIT-RESET] Task {} has no assignee — nothing to unlock",
                        event.taskInstanceId());
                return;
            }

            if (!ENTITY_TYPE.equals(event.entityType()) || event.entityId() == null) {
                log.debug("[AUDIT-RESET] entityType={} is not an audit engagement — ignoring | taskId={}",
                        event.entityType(), event.taskInstanceId());
                return;
            }

            Long engagementId = event.entityId();
            Long userId       = event.assignedUserId();
            LocalDateTime now = LocalDateTime.now();

            int auditorSections = 0;
            int auditeeSections = 0;
            int testingCleared  = 0;
            int clearedRows     = 0;

            List<AuditSectionInstance> touched = new ArrayList<>();

            // ── The auditor's own sections ───────────────────────────────────
            List<AuditSectionInstance> asAuditor = sectionInstanceRepository
                    .findByEngagementIdAndAssignedAuditorId(engagementId, userId);

            for (AuditSectionInstance sec : asAuditor) {
                boolean changed = false;

                if (sec.getSubmittedAt() != null) {
                    sec.setSubmittedAt(null);
                    sec.setSubmittedBy(null);
                    sec.setReopenedAt(now);
                    sec.setReopenedBy(event.performedBy());
                    auditorSections++;
                    changed = true;
                }

                // Testing is an auditor act, so it is reopened with the
                // auditor's section and nowhere else. There is no
                // testing_reopened_at pair on the entity; clearing the two
                // columns that exist is the whole of it.
                if (sec.getTestingSubmittedAt() != null) {
                    sec.setTestingSubmittedAt(null);
                    sec.setTestingSubmittedBy(null);
                    testingCleared++;
                    changed = true;
                }

                // The per-auditor submission rows on this section, whoever holds
                // them. Not filtered to userId, and that is right for the same
                // reason the assessment listener gives: the section is being
                // reopened, so a colleague who locked their own work inside it
                // would otherwise sit behind a lock on a section that is no
                // longer submitted.
                var rows = auditorSubmissionRepository.findBySectionInstanceId(sec.getId());
                if (!rows.isEmpty()) {
                    auditorSubmissionRepository.deleteAll(rows);
                    clearedRows += rows.size();
                }

                if (changed) touched.add(sec);
            }

            // ── The auditee's own sections ───────────────────────────────────
            List<AuditSectionInstance> asAuditee = sectionInstanceRepository
                    .findByEngagementIdAndAuditeeAssignedUserId(engagementId, userId);

            for (AuditSectionInstance sec : asAuditee) {
                if (sec.getAuditeeSubmittedAt() != null) {
                    sec.setAuditeeSubmittedAt(null);
                    sec.setAuditeeSubmittedBy(null);
                    sec.setAuditeeReopenedAt(now);
                    sec.setAuditeeReopenedBy(event.performedBy());
                    auditeeSections++;
                    touched.add(sec);
                }
            }

            if (!touched.isEmpty()) {
                sectionInstanceRepository.saveAll(touched);
            }

            log.info("[AUDIT-RESET] Unlocked after task reset | engagementId={} | taskId={} | userId={} | "
                            + "auditorSections={} | auditeeSections={} | testingCleared={} | submissionRowsCleared={}",
                    engagementId, event.taskInstanceId(), userId,
                    auditorSections, auditeeSections, testingCleared, clearedRows);

        } catch (Exception ex) {
            // Never fail the reset itself — see the class javadoc.
            log.error("[AUDIT-RESET] Could not unlock sections after task reset (non-fatal) | taskId={} | {}",
                    event.taskInstanceId(), ex.getMessage(), ex);
        }
    }
}