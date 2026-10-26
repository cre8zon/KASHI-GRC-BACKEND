package com.kashi.grc.usermanagement.service.user;

import com.kashi.grc.actionitem.domain.ActionItem;
import com.kashi.grc.assessment.domain.AssessmentQuestionInstance;
import com.kashi.grc.assessment.domain.AssessmentSectionInstance;
import com.kashi.grc.assessment.domain.AssessmentTemplateInstance;
import com.kashi.grc.assessment.domain.VendorAssessment;
import com.kashi.grc.audit.domain.AuditControlInstance;
import com.kashi.grc.audit.domain.AuditEngagement;
import com.kashi.grc.audit.domain.AuditSectionInstance;
import com.kashi.grc.common.security.AccountAccessService;
import com.kashi.grc.notification.service.NotificationService;
import com.kashi.grc.tenant.domain.Tenant;
import com.kashi.grc.workflow.domain.TaskInstance;
import com.kashi.grc.workflow.domain.TaskSectionAssignment;
import com.kashi.grc.workflow.domain.TaskSectionItem;
import com.kashi.grc.workflow.domain.TaskSectionItemAssignment;
import com.kashi.grc.workflow.domain.WorkflowInstance;
import com.kashi.grc.workflow.enums.TaskStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Everything a person was holding, besides workflow tasks, handed back when
 * they stop being active (suspended, deactivated, deleted).
 *
 * WorkflowEngineService.releaseTasksOfInactiveUser returns their workflow
 * tasks to unassigned. That left the finer-grained work still pointing at
 * them — a question to answer, a section to review, a control to test, an
 * action item to resolve — so screens showed it as theirs and nobody else
 * could see it was waiting. The rule here is the same as for tasks: the work
 * goes back to whoever hands it out, never silently to somebody else.
 *
 *   ACTION ITEMS (open or in progress, assigned to them)
 *     • an assignment mirror (CONTRIBUTOR_ASSIGNMENT, REVIEWER_ASSIGNMENT,
 *       TASK_ASSIGNMENT …) is dismissed — the assignment it mirrors is cleared
 *       below or with the workflow task;
 *     • any other item goes back to the person who delegated it
 *       (resolution_reserved_for), else to whoever raised it, else to its group
 *       pool when it has one, else to the organisation's owner.
 *     Items waiting on THEIR review go to whoever raised the item, else the owner.
 *
 *   ASSESSMENTS (not completed, closed or cancelled)
 *     question and section assignments — fill and review — are cleared unless
 *     that section was already submitted. Whoever is working the assessment is
 *     told, so they can assign someone else.
 *
 *   AUDITS (engagement not closed or cancelled)
 *     control and section assignments — auditor and auditee — are cleared
 *     unless that part is already done (tested / evidence submitted /
 *     section submitted). The engagement's owner and lead are told.
 *
 *   SECTIONS OF A WORKFLOW TASK (a coordinator handing sections or items out)
 *     their unfinished section assignments are removed and item assignments
 *     ended, so the coordinator can hand them to someone else.
 *
 * Finished work is never touched: who answered, tested or submitted something
 * stays on the record.
 *
 * Criteria API throughout (no @Query), through the EntityManager, so no other
 * module's repository changes for this.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssignmentReleaseService {

    /** What changed — returned to the caller and written to the status history. */
    public record Released(int actionItemsReturned, int actionItemsClosed, int reviewsReturned,
                           int assessmentItems, int auditItems, int sectionAssignments) {
        public int total() {
            return actionItemsReturned + actionItemsClosed + reviewsReturned
                    + assessmentItems + auditItems + sectionAssignments;
        }
        public String summary() {
            return "action items returned: " + actionItemsReturned
                    + "; assignment items closed: " + actionItemsClosed
                    + "; reviews returned: " + reviewsReturned
                    + "; assessment assignments cleared: " + assessmentItems
                    + "; audit assignments cleared: " + auditItems
                    + "; task section assignments released: " + sectionAssignments;
        }
    }

    private static final Set<ActionItem.Status> WORKING =
            EnumSet.of(ActionItem.Status.OPEN, ActionItem.Status.IN_PROGRESS);
    private static final Set<ActionItem.Status> LIVE = EnumSet.of(
            ActionItem.Status.OPEN, ActionItem.Status.IN_PROGRESS, ActionItem.Status.PENDING_REVIEW,
            ActionItem.Status.PENDING_VALIDATION, ActionItem.Status.SUBMITTED);
    private static final Set<String> ASSESSMENT_DONE = Set.of("COMPLETED", "CLOSED", "CANCELLED");
    private static final Set<AuditEngagement.Status> ENGAGEMENT_DONE =
            EnumSet.of(AuditEngagement.Status.CLOSED, AuditEngagement.Status.CANCELLED);
    private static final Set<TaskStatus> TASK_OPEN = EnumSet.of(TaskStatus.PENDING, TaskStatus.IN_PROGRESS);

    @PersistenceContext
    private EntityManager em;

    private final AccountAccessService accountAccess;
    private final NotificationService notificationService;

    // ── Release ──────────────────────────────────────────────────────────────

    @Transactional
    public Released release(Long userId, Long actorId, String who, String reason) {
        String note = "Returned — " + who + " is no longer active"
                + (reason == null || reason.isBlank() ? "" : " (" + reason.trim() + ")");
        int[] ai = releaseActionItems(userId, actorId, who, note);
        int assessment = releaseAssessments(userId, who);
        int audit = releaseAudits(userId, who);
        int sections = releaseTaskSections(userId, who);
        Released r = new Released(ai[0], ai[1], ai[2], assessment, audit, sections);
        log.info("[RELEASE] userId={} | {}", userId, r.summary());
        return r;
    }

    /** What release() would change — for the "what this affects" dialog. */
    @Transactional(readOnly = true)
    public Map<String, Long> count(Long userId) {
        long items = list(ActionItem.class, (cb, r) -> cb.and(
                cb.equal(r.get("assignedTo"), userId), r.get("status").in(WORKING))).size();
        long reviews = list(ActionItem.class, (cb, r) -> cb.and(
                cb.equal(r.get("resolutionReservedFor"), userId), r.get("status").in(LIVE),
                cb.or(cb.isNull(r.get("assignedTo")), cb.notEqual(r.get("assignedTo"), userId)))).size();
        long assessment = assessmentQuestions(userId).size() + assessmentSections(userId).size();
        long audit = auditControls(userId).size() + auditSections(userId).size();
        Map<String, Long> out = new LinkedHashMap<>();
        out.put("action_items", items);
        out.put("reviews", reviews);
        out.put("assessment_assignments", assessment);
        out.put("audit_assignments", audit);
        return out;
    }

    // ── Action items ─────────────────────────────────────────────────────────

    private int[] releaseActionItems(Long userId, Long actorId, String who, String note) {
        int returned = 0, closed = 0, reviews = 0;
        LocalDateTime now = LocalDateTime.now();
        Map<Long, List<String>> told = new LinkedHashMap<>();

        for (ActionItem a : list(ActionItem.class, (cb, r) -> cb.and(
                cb.equal(r.get("assignedTo"), userId), r.get("status").in(WORKING)))) {
            String type = a.getRemediationType();
            if (type != null && type.toUpperCase().endsWith("ASSIGNMENT")) {
                a.setStatus(ActionItem.Status.DISMISSED);
                a.setResolvedAt(now);
                a.setResolvedBy(actorId);
                a.setResolutionNote(note);
                closed++;
                continue;
            }
            Long holder = firstOperating(a.getTenantId(), userId, a.getResolutionReservedFor(), a.getCreatedBy());
            if (holder == null && a.getAssignedGroupRole() != null) {
                a.setAssignedTo(null);                       // back to its group's pool
            } else {
                if (holder == null) holder = firstOperating(a.getTenantId(), userId, ownerOf(a.getTenantId()));
                if (holder == null) continue;                // nobody to give it to — leave it visible as theirs
                a.setAssignedTo(holder);
                told.computeIfAbsent(holder, k -> new ArrayList<>()).add(a.getTitle());
            }
            returned++;
        }

        for (ActionItem a : list(ActionItem.class, (cb, r) -> cb.and(
                cb.equal(r.get("resolutionReservedFor"), userId), r.get("status").in(LIVE)))) {
            if (userId.equals(a.getAssignedTo()) && WORKING.contains(a.getStatus())) continue; // handled above
            Long reviewer = firstOperating(a.getTenantId(), userId, a.getCreatedBy(), ownerOf(a.getTenantId()));
            a.setResolutionReservedFor(reviewer);
            if (reviewer != null) told.computeIfAbsent(reviewer, k -> new ArrayList<>()).add(a.getTitle() + " (review)");
            reviews++;
        }

        told.forEach((uid, titles) -> notificationService.send(uid, "ACTION_ITEMS_RETURNED",
                titles.size() == 1
                        ? "\"" + titles.get(0) + "\" is yours now — " + who + " is no longer active."
                        : titles.size() + " action items are yours now — " + who + " is no longer active.",
                "ACTION_ITEM", null));
        return new int[]{returned, closed, reviews};
    }

    // ── Assessments ──────────────────────────────────────────────────────────

    private int releaseAssessments(Long userId, String who) {
        Set<Long> assessments = new LinkedHashSet<>();
        int n = 0;
        for (Object[] row : assessmentQuestions(userId)) {
            AssessmentQuestionInstance q = (AssessmentQuestionInstance) row[0];
            AssessmentSectionInstance s = (AssessmentSectionInstance) row[1];
            if (userId.equals(q.getAssignedUserId()) && s.getSubmittedAt() == null) { q.setAssignedUserId(null); n++; }
            if (userId.equals(q.getReviewerAssignedUserId()) && s.getReviewerSubmittedAt() == null) { q.setReviewerAssignedUserId(null); n++; }
            assessments.add(q.getAssessmentId());
        }
        for (Object[] row : assessmentSections(userId)) {
            AssessmentSectionInstance s = (AssessmentSectionInstance) row[0];
            if (userId.equals(s.getAssignedUserId()) && s.getSubmittedAt() == null) { s.setAssignedUserId(null); n++; }
            if (userId.equals(s.getReviewerAssignedUserId()) && s.getReviewerSubmittedAt() == null) { s.setReviewerAssignedUserId(null); n++; }
            assessments.add((Long) row[1]);
        }
        for (Long assessmentId : assessments) {
            Set<Long> to = new LinkedHashSet<>(workingOn("VENDOR_ASSESSMENT", assessmentId));
            VendorAssessment va = em.find(VendorAssessment.class, assessmentId);
            if (va != null && va.getReviewLeadUserId() != null) to.add(va.getReviewLeadUserId());
            to.remove(userId);
            if (!to.isEmpty()) notificationService.sendToUsers(new ArrayList<>(to), "ASSIGNMENTS_RETURNED",
                    "Questions or sections in an assessment need someone new — " + who + " is no longer active.",
                    "VENDOR_ASSESSMENT", assessmentId);
        }
        return n;
    }

    /** [question, its section] — assigned to them (fill or review) in a live assessment. */
    private List<Object[]> assessmentQuestions(Long userId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> q = cb.createQuery(Object[].class);
        Root<AssessmentQuestionInstance> qi = q.from(AssessmentQuestionInstance.class);
        Root<AssessmentSectionInstance> si = q.from(AssessmentSectionInstance.class);
        Root<VendorAssessment> va = q.from(VendorAssessment.class);
        q.multiselect(qi, si).where(
                cb.equal(si.get("id"), qi.get("sectionInstanceId")),
                cb.equal(va.get("id"), qi.get("assessmentId")),
                cb.not(cb.upper(cb.coalesce(va.<String>get("status"), "")).in(ASSESSMENT_DONE)),
                cb.or(cb.and(cb.equal(qi.get("assignedUserId"), userId), cb.isNull(si.get("submittedAt"))),
                      cb.and(cb.equal(qi.get("reviewerAssignedUserId"), userId), cb.isNull(si.get("reviewerSubmittedAt")))));
        return em.createQuery(q).getResultList();
    }

    /** [section, assessmentId] — assigned to them (fill or review) in a live assessment. */
    private List<Object[]> assessmentSections(Long userId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> q = cb.createQuery(Object[].class);
        Root<AssessmentSectionInstance> si = q.from(AssessmentSectionInstance.class);
        Root<AssessmentTemplateInstance> ti = q.from(AssessmentTemplateInstance.class);
        Root<VendorAssessment> va = q.from(VendorAssessment.class);
        q.multiselect(si, va.get("id")).where(
                cb.equal(ti.get("id"), si.get("templateInstanceId")),
                cb.equal(va.get("id"), ti.get("assessmentId")),
                cb.not(cb.upper(cb.coalesce(va.<String>get("status"), "")).in(ASSESSMENT_DONE)),
                cb.or(cb.and(cb.equal(si.get("assignedUserId"), userId), cb.isNull(si.get("submittedAt"))),
                      cb.and(cb.equal(si.get("reviewerAssignedUserId"), userId), cb.isNull(si.get("reviewerSubmittedAt")))));
        return em.createQuery(q).getResultList();
    }

    // ── Audits ───────────────────────────────────────────────────────────────

    private int releaseAudits(Long userId, String who) {
        Set<Long> engagements = new LinkedHashSet<>();
        int n = 0;
        for (AuditControlInstance c : auditControls(userId)) {
            if (userId.equals(c.getAssignedAuditorId()) && c.getTestResult() == AuditControlInstance.TestResult.NOT_TESTED) {
                c.setAssignedAuditorId(null); n++;
            }
            if (userId.equals(c.getAuditeeAssignedUserId()) && !c.isAuditeeEvidenceSubmitted()) {
                c.setAuditeeAssignedUserId(null); n++;
            }
            engagements.add(c.getEngagementId());
        }
        for (AuditSectionInstance s : auditSections(userId)) {
            if (userId.equals(s.getAssignedAuditorId()) && s.getSubmittedAt() == null) { s.setAssignedAuditorId(null); n++; }
            if (userId.equals(s.getAuditeeAssignedUserId()) && s.getAuditeeSubmittedAt() == null) { s.setAuditeeAssignedUserId(null); n++; }
            engagements.add(s.getEngagementId());
        }
        for (Long engagementId : engagements) {
            AuditEngagement e = engagementId == null ? null : em.find(AuditEngagement.class, engagementId);
            if (e == null) continue;
            Set<Long> to = new LinkedHashSet<>();
            if (e.getOwnerId() != null) to.add(e.getOwnerId());
            if (e.getLeadAuditorId() != null) to.add(e.getLeadAuditorId());
            if (e.getLeadAuditeeId() != null) to.add(e.getLeadAuditeeId());
            to.remove(userId);
            if (!to.isEmpty()) notificationService.sendToUsers(new ArrayList<>(to), "ASSIGNMENTS_RETURNED",
                    "Controls or sections in an audit need someone new — " + who + " is no longer active.",
                    "AUDIT_ENGAGEMENT", engagementId);
        }
        return n;
    }

    private List<AuditControlInstance> auditControls(Long userId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<AuditControlInstance> q = cb.createQuery(AuditControlInstance.class);
        Root<AuditControlInstance> c = q.from(AuditControlInstance.class);
        Root<AuditEngagement> e = q.from(AuditEngagement.class);
        q.select(c).where(
                cb.equal(e.get("id"), c.get("engagementId")),
                cb.not(e.get("status").in(ENGAGEMENT_DONE)),
                cb.or(cb.and(cb.equal(c.get("assignedAuditorId"), userId),
                             cb.equal(c.get("testResult"), AuditControlInstance.TestResult.NOT_TESTED)),
                      cb.and(cb.equal(c.get("auditeeAssignedUserId"), userId),
                             cb.isFalse(c.<Boolean>get("auditeeEvidenceSubmitted")))));
        return em.createQuery(q).getResultList();
    }

    private List<AuditSectionInstance> auditSections(Long userId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<AuditSectionInstance> q = cb.createQuery(AuditSectionInstance.class);
        Root<AuditSectionInstance> s = q.from(AuditSectionInstance.class);
        Root<AuditEngagement> e = q.from(AuditEngagement.class);
        q.select(s).where(
                cb.equal(e.get("id"), s.get("engagementId")),
                cb.not(e.get("status").in(ENGAGEMENT_DONE)),
                cb.or(cb.and(cb.equal(s.get("assignedAuditorId"), userId), cb.isNull(s.get("submittedAt"))),
                      cb.and(cb.equal(s.get("auditeeAssignedUserId"), userId), cb.isNull(s.get("auditeeSubmittedAt")))));
        return em.createQuery(q).getResultList();
    }

    // ── Sections and items of a workflow task (coordinator hand-outs) ────────

    private int releaseTaskSections(Long userId, String who) {
        int n = 0;
        Map<Long, Integer> coordinators = new LinkedHashMap<>();

        // Case 2 — a section handed to them as a sub-task. The row goes, so the
        // coordinator can hand the section to someone else; while it stayed
        // PENDING the section could never complete. Their sub-task itself was
        // already returned with the workflow tasks.
        for (TaskSectionAssignment a : list(TaskSectionAssignment.class, (cb, r) -> cb.and(
                cb.equal(r.get("assignedToUserId"), userId), cb.notEqual(r.get("status"), "COMPLETED")))) {
            if (!taskOpen(a.getTaskInstanceId())) continue;
            coordinators.merge(a.getAssignedByUserId(), 1, Integer::sum);
            em.remove(a);
            n++;
        }

        // Case 3 — individual items handed to them.
        for (TaskSectionItemAssignment a : list(TaskSectionItemAssignment.class, (cb, r) -> cb.and(
                cb.equal(r.get("assignedToUserId"), userId), cb.isTrue(r.<Boolean>get("isActive"))))) {
            if (!taskOpen(a.getTaskInstanceId())) continue;
            a.setActive(false);
            coordinators.merge(a.getAssignedByUserId(), 1, Integer::sum);
            n++;
        }
        for (TaskSectionItem i : list(TaskSectionItem.class, (cb, r) -> cb.and(
                cb.equal(r.get("assignedToUserId"), userId), cb.notEqual(r.get("status"), "COMPLETED")))) {
            if (!taskOpen(i.getTaskInstanceId())) continue;
            i.setAssignedToUserId(null);
        }

        coordinators.forEach((uid, count) -> {
            if (uid == null || uid.equals(userId)) return;
            notificationService.send(uid, "ASSIGNMENTS_RETURNED",
                    count + (count == 1 ? " part of a task you handed out needs" : " parts of tasks you handed out need")
                            + " someone new — " + who + " is no longer active.",
                    "TASK", null);
        });
        return n;
    }

    private boolean taskOpen(Long taskInstanceId) {
        TaskInstance t = taskInstanceId == null ? null : em.find(TaskInstance.class, taskInstanceId);
        return t != null && TASK_OPEN.contains(t.getStatus());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** People holding open tasks on the live workflow of this entity — whoever is working it. */
    private List<Long> workingOn(String entityType, Long entityId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> q = cb.createQuery(Long.class);
        Root<TaskInstance> t = q.from(TaskInstance.class);
        Root<com.kashi.grc.workflow.domain.StepInstance> s = q.from(com.kashi.grc.workflow.domain.StepInstance.class);
        Root<WorkflowInstance> w = q.from(WorkflowInstance.class);
        q.select(t.get("assignedUserId")).distinct(true).where(
                cb.equal(s.get("id"), t.get("stepInstanceId")),
                cb.equal(w.get("id"), s.get("workflowInstanceId")),
                cb.equal(w.get("entityType"), entityType),
                cb.equal(w.get("entityId"), entityId),
                t.get("status").in(TASK_OPEN));
        return em.createQuery(q).getResultList();
    }

    /** The first candidate who is someone else and can act in this tenant now. */
    private Long firstOperating(Long tenantId, Long leaving, Long... candidates) {
        for (Long c : candidates) {
            if (c == null || c.equals(leaving)) continue;
            if (accountAccess.check(c, tenantId).access() == AccountAccessService.Access.FULL) return c;
        }
        return null;
    }

    private Long ownerOf(Long tenantId) {
        Tenant t = tenantId == null ? null : em.find(Tenant.class, tenantId);
        return t == null ? null : t.getOwnerUserId();
    }

    @FunctionalInterface
    private interface Where<T> {
        jakarta.persistence.criteria.Predicate build(CriteriaBuilder cb, Root<T> root);
    }

    private <T> List<T> list(Class<T> type, Where<T> where) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<T> q = cb.createQuery(type);
        Root<T> r = q.from(type);
        q.select(r).where(where.build(cb, r));
        return em.createQuery(q).getResultList();
    }
}
