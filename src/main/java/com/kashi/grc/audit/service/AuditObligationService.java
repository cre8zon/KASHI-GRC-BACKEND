package com.kashi.grc.audit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashi.grc.actionitem.domain.ActionItem;
import com.kashi.grc.actionitem.dto.ActionItemRequest;
import com.kashi.grc.actionitem.dto.ActionItemResponse;
import com.kashi.grc.actionitem.repository.ActionItemRepository;
import com.kashi.grc.actionitem.service.ActionItemService;
import com.kashi.grc.actionitem.specification.ActionItemSpecification;
import com.kashi.grc.audit.domain.AuditControlInstance;
import com.kashi.grc.audit.domain.AuditPolicyInstance;
import com.kashi.grc.audit.domain.AuditSectionInstance;
import com.kashi.grc.audit.domain.AuditTestInstance;
import com.kashi.grc.audit.repository.AuditControlInstanceRepository;
import com.kashi.grc.audit.repository.AuditControlInstanceTestMappingRepository;
import com.kashi.grc.audit.repository.AuditSectionInstanceRepository;
import com.kashi.grc.audit.repository.AuditTestInstanceRepository;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.notification.service.NotificationService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-instance obligations on an audit engagement: delegation, resolution on
 * completion, send-back reopen, and cleanup on reassignment.
 *
 * ── WHY ACTION ITEMS AND NOT WORKFLOW TASKS ───────────────────────────────
 * Controls reach the workflow only at engagement level — one task per user per
 * step (AuditWorkflowActorResolver, ASSIGNMENT_SCOPED). Giving every control
 * its own workflow instance ("Option B") is a far larger change and is
 * deliberately not taken. An action item is the per-instance carrier, exactly
 * as it is for a vendor-assessment question: it lands in the assignee's inbox,
 * it has a delegator who can revoke it, and — through ControlAccessGuard — a
 * LIVE one lets its assignee act on the instance it is about.
 *
 * ── THE LIFECYCLE ─────────────────────────────────────────────────────────
 *   delegate*        someone who may do the work raises an item for a colleague
 *                    (resolutionReservedFor = the delegator, so only they can
 *                    revoke it — ActionItemService.validateTransition)
 *   on*              doing the work resolves the doer's own item of that type
 *                    on that instance, and nothing else. A clarification or a
 *                    finding on the same control must survive — the mistake
 *                    AssessmentObligationService documents was made once already.
 *   raiseControlReopen
 *                    a send-back puts the control back in the evidence owner's
 *                    inbox (mirrors CONTRIBUTOR_REOPEN)
 *   closeOnReassignment
 *                    when a control changes hands for one kind of work,
 *                    delegations for that work stop granting access — the new
 *                    owner did not choose those delegates
 *
 * ── ROUTING: THE ENGAGEMENT, WITH THE INSTANCE IN A DRAWER ─────────────────
 * Work on an instance is done from the engagement — its Controls tab, with the
 * control (or test, or policy) open in a drawer that is the instance's full
 * screen embedded. So every item carries parentEntityType = AUDIT and
 * parentEntityId = the engagement, and lib/inboxRoute.js — which resolves the
 * destination id as `parentEntityId ?? entityId` — fills a nav row's :id with
 * the ENGAGEMENT. The nav rows (sql/91 §5, re-pointed by sql/92) read
 *   /module/audit_engagement/:id?tab=controls&drawerType=AUDIT_CONTROL_INSTANCE&drawerTab=evidence
 * and the instance id reaches the drawer through the entityType/entityId that
 * inboxRoute appends to every action-item route (UrlEntityDrawerHost in
 * UniversalModulePage). parentEntityId also makes "every obligation in this
 * engagement" one query.
 *
 * nav_key, assigner_nav_key and default priority come from the
 * action_item_blueprints row whose blueprint_code is the remediation type
 * (sql/92), so moving a route is a data change, not a code change. nav_context
 * carries the same routes fully resolved, as the fallback for an environment
 * where the blueprint or nav rows are missing.
 *
 * ── WHO MAY TAKE THE WORK ─────────────────────────────────────────────────
 * By the permission for the work, never by role side. Evidence goes to someone
 * holding audit:control:submit-evidence, testing to someone holding
 * audit:control:record-test-result — whatever their role is called and
 * whichever side the workflow puts them on (an internal audit's evidence owners
 * are often ORGANIZATION-side). See Work.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditObligationService {

    private static final Set<ActionItem.Status> LIVE = EnumSet.of(
            ActionItem.Status.OPEN,
            ActionItem.Status.IN_PROGRESS,
            ActionItem.Status.PENDING_REVIEW,
            ActionItem.Status.PENDING_VALIDATION);

    /**
     * The work being handed on, and the permission(s) that decide who can do it
     * — the same codes the work's endpoints and ui_actions require, so "can be
     * delegated this" and "can press the button" are one question. OR semantics.
     */
    public enum Work {
        EVIDENCE(List.of("audit:control:submit-evidence")),
        TESTING(List.of("audit:control:record-test-result")),
        POLICY_REVIEW(List.of("audit:policy:review", "audit:control:record-test-result"));

        final List<String> permissions;
        Work(List<String> permissions) { this.permissions = permissions; }

        public List<String> permissions() { return permissions; }

        /** EVIDENCE | TESTING | POLICY_REVIEW; the first drop's AUDITEE / AUDITOR are accepted. */
        public static Work parse(String raw) {
            if (raw == null || raw.isBlank()) return null;
            String v = raw.trim().toUpperCase();
            if ("AUDITEE".equals(v)) return EVIDENCE;
            if ("AUDITOR".equals(v)) return TESTING;
            try { return Work.valueOf(v); } catch (IllegalArgumentException ex) { return null; }
        }
    }

    private final ActionItemService                         actionItemService;
    private final ActionItemRepository                      actionItemRepository;
    private final ControlAccessGuard                        guard;
    private final AuditControlInstanceRepository            controlRepo;
    private final AuditTestInstanceRepository               testRepo;
    private final com.kashi.grc.audit.repository.AuditPolicyInstanceRepository policyRepo;
    private final AuditControlInstanceTestMappingRepository ctrlTestMappingRepo;
    private final AuditSectionInstanceRepository            sectionRepo;
    private final com.kashi.grc.workflow.service.WorkflowEngineService workflowEngineService;
    private final com.kashi.grc.usermanagement.repository.UserRepository userRepository;
    private final NotificationService                       notificationService;
    private final UtilityService                            utilityService;
    private final ObjectMapper                              objectMapper;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager em;

    // ══════════════════════════════════════════════════════════════════════
    // DELEGATE
    // ══════════════════════════════════════════════════════════════════════

    /** Body of POST /v1/audit/{control|test|policy}-instances/{id}/delegate. */
    @Data
    public static class DelegateRequest {
        /** Who will do the work. Required. */
        private Long   assignedTo;
        /**
         * Controls: EVIDENCE or TESTING. Tests are always TESTING and policies
         * POLICY_REVIEW, so it may be omitted for them.
         */
        private String work;
        /** The first drop's name for `work` (AUDITEE / AUDITOR). Still accepted. */
        private String side;
        /** What is being asked, in the delegator's words. Optional. */
        private String note;
        /** ISO date (2026-10-15) or date-time (2026-10-15T17:00:00). Optional. */
        private String dueAt;
        /** LOW | MEDIUM | HIGH | CRITICAL. Optional — the blueprint's default otherwise. */
        private ActionItem.Priority priority;
    }

    @Transactional
    public ActionItemResponse delegateControl(Long controlId, DelegateRequest req) {
        var me = utilityService.getLoggedInDataContext();
        AuditControlInstance ctrl = controlRepo.findById(controlId)
                .orElseThrow(() -> notAccessible());
        guard.requireReadable(ctrl.getTenantId(), ctrl.getEngagementId());

        Work work = parseControlWork(req);
        boolean evidence = work == Work.EVIDENCE;
        // Only the people who OWN this work may hand it on — section owner first,
        // then the lead / engagement owner / override (ControlAccessGuard.requireCanDelegate).
        // Not a delegate (no chains) and not a control-level assignee.
        guard.requireCanDelegate(ctrl, me.getId(), evidence);

        String type = evidence
                ? AuditObligationTypes.CONTROL_EVIDENCE_ASSIGNMENT
                : AuditObligationTypes.CONTROL_TEST_ASSIGNMENT;
        validateDelegatee(req.getAssignedTo(), me.getId(), work, me.getTenantId());
        requireNotAlreadyDelegated(ActionItem.EntityType.AUDIT_CONTROL_INSTANCE, controlId,
                req.getAssignedTo(), type, me.getTenantId());

        String label = controlLabel(ctrl);
        ActionItemRequest item = baseRequest(req, me.getId(),
                ActionItem.EntityType.AUDIT_CONTROL_INSTANCE, controlId, ctrl.getEngagementId(), type,
                (evidence ? "Provide evidence: " : "Test control: ") + label,
                navContext(ctrl.getEngagementId(), "AUDIT_CONTROL_INSTANCE", controlId,
                        evidence ? "evidence" : "fieldwork", work));

        ActionItemResponse created = actionItemService.create(item, me.getId(), me.getTenantId());

        // Same type and shape assignAuditeeToControl uses for evidence, so an
        // email rule configured for evidence requests fires for both.
        notificationService.send(req.getAssignedTo(),
                evidence ? "AUDIT_EVIDENCE_REQUESTED" : "AUDIT_CONTROL_TEST_DELEGATED",
                (evidence ? "Evidence requested for audit control: " : "Testing delegated for audit control: ")
                        + label + dueSuffix(req.getDueAt()),
                "AUDIT_CONTROL_INSTANCE", controlId);

        log.info("[AUDIT-OBLIGATION] Control delegated | controlId={} work={} to={} by={} itemId={}",
                controlId, work, req.getAssignedTo(), me.getId(), created.getId());
        return created;
    }

    @Transactional
    public ActionItemResponse delegateTest(Long testId, DelegateRequest req) {
        var me = utilityService.getLoggedInDataContext();
        AuditTestInstance test = testRepo.findById(testId).orElseThrow(() -> notAccessible());
        guard.requireReadable(test.getTenantId(), test.getEngagementId());
        rejectOtherWork(req, Work.TESTING, "A test");
        guard.requireCanDelegateTest(test, me.getId());

        validateDelegatee(req.getAssignedTo(), me.getId(), Work.TESTING, me.getTenantId());
        requireNotAlreadyDelegated(ActionItem.EntityType.AUDIT_TEST_INSTANCE, testId,
                req.getAssignedTo(), AuditObligationTypes.TEST_ASSIGNMENT, me.getTenantId());

        String label = join(test.getTestRefSnapshot(), test.getTestNameSnapshot());
        ActionItemRequest item = baseRequest(req, me.getId(),
                ActionItem.EntityType.AUDIT_TEST_INSTANCE, testId, test.getEngagementId(),
                AuditObligationTypes.TEST_ASSIGNMENT, "Run test: " + label,
                navContext(test.getEngagementId(), "AUDIT_TEST_INSTANCE", testId, null, Work.TESTING));

        ActionItemResponse created = actionItemService.create(item, me.getId(), me.getTenantId());
        notificationService.send(req.getAssignedTo(), "AUDIT_TEST_DELEGATED",
                "Audit test delegated to you: " + label + dueSuffix(req.getDueAt()),
                "AUDIT_TEST_INSTANCE", testId);
        log.info("[AUDIT-OBLIGATION] Test delegated | testId={} to={} by={} itemId={}",
                testId, req.getAssignedTo(), me.getId(), created.getId());
        return created;
    }

    @Transactional
    public ActionItemResponse delegatePolicy(Long policyId, DelegateRequest req) {
        var me = utilityService.getLoggedInDataContext();
        AuditPolicyInstance policy = policyRepo.findById(policyId).orElseThrow(() -> notAccessible());
        guard.requireReadable(policy.getTenantId(), policy.getEngagementId());
        rejectOtherWork(req, Work.POLICY_REVIEW, "A policy review");
        guard.requireCanDelegatePolicy(policy, me.getId());

        validateDelegatee(req.getAssignedTo(), me.getId(), Work.POLICY_REVIEW, me.getTenantId());
        requireNotAlreadyDelegated(ActionItem.EntityType.AUDIT_POLICY_INSTANCE, policyId,
                req.getAssignedTo(), AuditObligationTypes.POLICY_REVIEW_ASSIGNMENT, me.getTenantId());

        String label = join(policy.getPolicyRefSnapshot(), policy.getTitleSnapshot());
        ActionItemRequest item = baseRequest(req, me.getId(),
                ActionItem.EntityType.AUDIT_POLICY_INSTANCE, policyId, policy.getEngagementId(),
                AuditObligationTypes.POLICY_REVIEW_ASSIGNMENT, "Review policy: " + label,
                navContext(policy.getEngagementId(), "AUDIT_POLICY_INSTANCE", policyId,
                        "policy-content", Work.POLICY_REVIEW));

        ActionItemResponse created = actionItemService.create(item, me.getId(), me.getTenantId());
        notificationService.send(req.getAssignedTo(), "AUDIT_POLICY_REVIEW_DELEGATED",
                "Policy review delegated to you: " + label + dueSuffix(req.getDueAt()),
                "AUDIT_POLICY_INSTANCE", policyId);
        log.info("[AUDIT-OBLIGATION] Policy review delegated | policyId={} to={} by={} itemId={}",
                policyId, req.getAssignedTo(), me.getId(), created.getId());
        return created;
    }

    // ══════════════════════════════════════════════════════════════════════
    // DELEGATE CANDIDATES — who may be picked
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Users in the caller's tenant who hold the permission for this work, as
     * the picker shows them. The same list delegate() validates against, so the
     * picker cannot offer someone the endpoint will refuse. A guest sees only
     * the users their scope allows (their own firm and the client), never a
     * rival firm's staff. Excludes the caller.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> candidates(Work work) {
        var me = utilityService.getLoggedInDataContext();
        return candidatesFor(work, me.getTenantId())
                .stream()
                .filter(m -> !(m.get("id") instanceof Number n && n.longValue() == me.getId()))
                .toList();
    }

    // ══════════════════════════════════════════════════════════════════════
    // CLOSE ON COMPLETION
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Evidence submitted on a control.
     *
     * Closes every evidence delegation on the control and any CONTROL_REOPEN,
     * whoever holds them — the evidence they asked for has now been given,
     * whoever gave it. Delegates and delegators are notified.
     */
    @Transactional
    public void onEvidenceSubmitted(Long controlId, Long submittedBy, Long tenantId) {
        close(ActionItem.EntityType.AUDIT_CONTROL_INSTANCE, controlId, submittedBy, tenantId,
                List.of(AuditObligationTypes.CONTROL_EVIDENCE_ASSIGNMENT),
                "Evidence submitted", submittedBy);
        // The control's evidence now exists, whoever submitted it (the delegate,
        // the delegator, the owner, an override holder): every evidence
        // delegation on it asked for exactly that, so all of them are done.
        closeDoneDelegations(controlId, submittedBy, tenantId,
                List.of(AuditObligationTypes.CONTROL_EVIDENCE_ASSIGNMENT), "evidence");
        close(ActionItem.EntityType.AUDIT_CONTROL_INSTANCE, controlId, null, tenantId,
                List.of(AuditObligationTypes.CONTROL_REOPEN),
                "Evidence resubmitted", submittedBy);
    }

    /** Control-level result recorded: the recorder's control-test delegation is done. */
    @Transactional
    public void onControlResultRecorded(Long controlId, Long testedBy, Long tenantId) {
        close(ActionItem.EntityType.AUDIT_CONTROL_INSTANCE, controlId, testedBy, tenantId,
                AuditObligationTypes.CONTROL_AUDITOR_TYPES, "Test result recorded", testedBy);
        closeDoneDelegations(controlId, testedBy, tenantId, AuditObligationTypes.CONTROL_AUDITOR_TYPES, "testing");
    }

    /**
     * A test result was recorded. Closes the recorder's delegation on the test,
     * and — for each control the test is mapped to — the recorder's control-test
     * delegation once every REQUIRED test on that control has a result. A
     * delegate asked to "test control X" usually works test by test, and the
     * control's own result is derived, so without the second part their item
     * would never close.
     */
    @Transactional
    public void onTestResultRecorded(Long testId, Long recordedBy, Long tenantId) {
        close(ActionItem.EntityType.AUDIT_TEST_INSTANCE, testId, recordedBy, tenantId,
                AuditObligationTypes.TEST_TYPES, "Test result recorded", recordedBy);

        List<Long> controlIds = ctrlTestMappingRepo.findControlInstanceIdsByTestInstanceId(testId);
        if (controlIds == null || controlIds.isEmpty()) return;

        // Only the controls this person actually owes — usually none.
        Set<Long> owed = actionItemRepository.findEntityIdsWithLiveItemForAssignee(
                "AUDIT_CONTROL_INSTANCE", controlIds, recordedBy, tenantId,
                AuditObligationTypes.CONTROL_AUDITOR_TYPES);
        for (Long controlId : owed) {
            List<Long> required = ctrlTestMappingRepo.findRequiredTestInstanceIdsByControlInstanceId(controlId);
            boolean allRecorded = required == null || required.isEmpty()
                    || testRepo.findAllById(required).stream()
                       .allMatch(t -> t.getTestResult() != null
                               && t.getTestResult() != AuditTestInstance.TestResult.NOT_RUN);
            if (allRecorded) {
                close(ActionItem.EntityType.AUDIT_CONTROL_INSTANCE, controlId, recordedBy, tenantId,
                        AuditObligationTypes.CONTROL_AUDITOR_TYPES,
                        "All required tests recorded", recordedBy);
            }
        }
    }

    /** Policy reviewed: the reviewer's policy delegation is done. */
    @Transactional
    public void onPolicyReviewed(Long policyId, Long reviewedBy, Long tenantId) {
        close(ActionItem.EntityType.AUDIT_POLICY_INSTANCE, policyId, reviewedBy, tenantId,
                AuditObligationTypes.POLICY_TYPES, "Policy reviewed", reviewedBy);
    }

    // ══════════════════════════════════════════════════════════════════════
    // SEND-BACK
    // ══════════════════════════════════════════════════════════════════════

    /**
     * A control's evidence was sent back. Puts it in the evidence owner's inbox
     * as a CONTROL_REOPEN item — the same person sendBackControlEvidence
     * notifies: the control's evidence assignee, else the nearest section's.
     *
     * Not raised to the person sending it back (they are not going to fix their
     * own request), and not duplicated when one is already live.
     */
    @Transactional
    /** @return who the reopen item went to, or null when none was raised (no owner, or one already live) */
    public Long raiseControlReopen(AuditControlInstance ctrl, String reason, Long sentBackBy, Long tenantId) {
        if (ctrl == null) return null;
        Long recipient = ctrl.getAuditeeAssignedUserId();
        if (recipient == null) recipient = nearestSectionEvidenceOwner(ctrl.getSectionInstanceId());
        if (recipient == null || recipient.equals(sentBackBy)) return null;

        Set<Long> already = actionItemRepository.findEntityIdsWithLiveItemForAssignee(
                "AUDIT_CONTROL_INSTANCE", List.of(ctrl.getId()), recipient, tenantId,
                List.of(AuditObligationTypes.CONTROL_REOPEN));
        if (!already.isEmpty()) {
            log.debug("[AUDIT-OBLIGATION] Reopen already live | controlId={} recipient={}",
                    ctrl.getId(), recipient);
            return null;
        }

        DelegateRequest r = new DelegateRequest();
        r.setAssignedTo(recipient);
        r.setNote(reason == null || reason.isBlank()
                ? "The auditor sent this control's evidence back. Please re-upload and resubmit."
                : reason.trim());
        // A send-back is always urgent; stated here rather than left to the
        // blueprint so it holds on an environment without one.
        r.setPriority(ActionItem.Priority.HIGH);
        ActionItemRequest item = baseRequest(r, sentBackBy,
                ActionItem.EntityType.AUDIT_CONTROL_INSTANCE, ctrl.getId(), ctrl.getEngagementId(),
                AuditObligationTypes.CONTROL_REOPEN,
                "Evidence sent back: " + controlLabel(ctrl),
                navContext(ctrl.getEngagementId(), "AUDIT_CONTROL_INSTANCE", ctrl.getId(),
                        "evidence", Work.EVIDENCE));
        ActionItemResponse created = actionItemService.create(item, sentBackBy, tenantId);
        log.info("[AUDIT-OBLIGATION] Reopen raised | controlId={} recipient={} by={} itemId={}",
                ctrl.getId(), recipient, sentBackBy, created.getId());
        return recipient;
    }

    /**
     * Auditee-side reopen: give the control's evidence back to whoever did it
     * under a delegation, by reopening their most recent COMPLETED evidence
     * delegation on this control (not the reopener's own). Reopening keeps the
     * history in one item — the ask, the work, the "not good enough, again" —
     * instead of a second item nobody connects to the first.
     *
     * @return true when a delegation was reopened; false when there was none
     *         (the caller then sends it back to the control's owner instead)
     */
    @Transactional
    public boolean reopenLastEvidenceDelegation(AuditControlInstance ctrl, Long reopenedBy, String reason, Long tenantId) {
        if (ctrl == null || tenantId == null) return false;
        // The person's evidence delegation on this control, newest first. A live
        // one wins: a delegation left open after the evidence was submitted
        // (older data, or submitted another way) still names who did the work —
        // it used to be skipped, and the resubmit went to the section owner
        // instead of the delegate. Then the latest finished one (RESOLVED, or
        // SUBMITTED through the item). Reassigned-away (DISMISSED) never counts.
        List<ActionItem> mine = actionItemRepository.findAll(
                        ActionItemSpecification.forTenant(tenantId)
                                .and(ActionItemSpecification.forEntity(ActionItem.EntityType.AUDIT_CONTROL_INSTANCE, ctrl.getId())))
                .stream()
                .filter(ai -> AuditObligationTypes.CONTROL_EVIDENCE_ASSIGNMENT.equals(ai.getRemediationType()))
                .filter(ai -> ai.getStatus() != ActionItem.Status.DISMISSED)
                .filter(ai -> ai.getAssignedTo() != null && !ai.getAssignedTo().equals(reopenedBy))
                .sorted(java.util.Comparator.comparing(ActionItem::getId).reversed())
                .toList();
        ActionItem last = mine.stream().filter(ai -> LIVE.contains(ai.getStatus())).findFirst()
                .orElse(mine.stream()
                        .filter(ai -> ai.getStatus() == ActionItem.Status.RESOLVED || ai.getStatus() == ActionItem.Status.SUBMITTED)
                        .max(java.util.Comparator.comparing((ActionItem ai) -> ai.getResolvedAt() == null ? LocalDateTime.MIN : ai.getResolvedAt()))
                        .orElse(null));
        if (last == null) return false;
        last.setStatus(ActionItem.Status.OPEN);
        last.setResolvedAt(null);
        last.setResolvedBy(null);
        last.setResolutionNote("Reopened for resubmission" + (reason == null || reason.isBlank() ? "" : ": " + reason.trim()));
        actionItemRepository.save(last);
        String who = userRepository.findById(reopenedBy)
                .map(u -> u.getFullName() != null && !u.getFullName().isBlank() ? u.getFullName().trim() : u.getEmail())
                .orElse("Your delegator");
        try {
            notificationService.send(last.getAssignedTo(), "AUDIT_EVIDENCE_REOPENED",
                    who + " asked you to resubmit the evidence for " + controlLabel(ctrl)
                            + (reason == null || reason.isBlank() ? "" : ": " + reason.trim()),
                    "AUDIT_CONTROL_INSTANCE", ctrl.getId());
        } catch (RuntimeException e) {
            log.warn("[AUDIT-OBLIGATION] Notification failed (non-fatal) | {}", e.getMessage());
        }
        log.info("[AUDIT-OBLIGATION] Evidence delegation reopened | item={} control={} by={}", last.getId(), ctrl.getId(), reopenedBy);
        return true;
    }

    // ══════════════════════════════════════════════════════════════════════
    // REASSIGNMENT
    // ══════════════════════════════════════════════════════════════════════

    /**
     * A control's assignee for one kind of work changed (evidence =
     * auditee_assigned_user_id, testing = assigned_auditor_id). Live
     * obligations for that work held by anyone other than the new assignee are
     * closed, because a live item is an access grant and the new owner did not
     * choose those delegates. Called only when the value actually changed.
     */
    @Transactional
    public int closeOnReassignment(Long controlId, boolean evidenceWork, Long newAssignee,
                                   Long changedBy, Long tenantId) {
        if (controlId == null || tenantId == null) return 0;
        if (changedBy == null) {
            // The single-control assign paths do not carry the actor through the
            // service signature; the request does.
            try { changedBy = utilityService.getLoggedInDataContext().getId(); }
            catch (Exception ignored) { /* system path — resolvedBy stays null */ }
        }
        List<String> types = evidenceWork
                ? AuditObligationTypes.CONTROL_AUDITEE_TYPES
                : AuditObligationTypes.CONTROL_AUDITOR_TYPES;
        List<ActionItem> live = liveItems(ActionItem.EntityType.AUDIT_CONTROL_INSTANCE, controlId, tenantId);
        int closed = 0;
        LocalDateTime now = LocalDateTime.now();
        for (ActionItem ai : live) {
            if (!types.contains(ai.getRemediationType())) continue;
            if (newAssignee != null && newAssignee.equals(ai.getAssignedTo())) continue;
            ai.setStatus(ActionItem.Status.RESOLVED);
            ai.setResolutionNote("Control reassigned — delegation closed");
            ai.setResolvedAt(now);
            ai.setResolvedBy(changedBy);
            actionItemRepository.save(ai);
            closed++;
        }
        if (closed > 0) {
            log.info("[AUDIT-OBLIGATION] Closed {} {} obligation(s) on reassignment | controlId={} newAssignee={}",
                    closed, evidenceWork ? "evidence" : "testing", controlId, newAssignee);
        }
        return closed;
    }

    /**
     * A SECTION's owner for one kind of work changed (or was cleared). Controls
     * under it that have no assignee of their own on that side inherit the
     * section owner (ControlAccessGuard's section tier), so delegations on them
     * were handed out under the old owner's authority. Same rule as
     * closeOnReassignment for a control: live items of that side are closed
     * unless the new owner is their holder or their delegator. Controls with
     * their own assignee are untouched — their delegations rest on that
     * assignee, who has not changed.
     *
     * @param sectionIds the section and, when the assignment cascaded, its descendants
     */
    @Transactional
    public int closeOnSectionReassignment(Long engagementId, Collection<Long> sectionIds, boolean evidenceWork,
                                          Long newOwner, Long changedBy, Long tenantId) {
        if (engagementId == null || tenantId == null || sectionIds == null || sectionIds.isEmpty()) return 0;
        Set<Long> sections = new HashSet<>(sectionIds);
        List<Long> inheriting = controlRepo.findByEngagementId(engagementId).stream()
                .filter(c -> c.getSectionInstanceId() != null && sections.contains(c.getSectionInstanceId()))
                .filter(c -> (evidenceWork ? c.getAuditeeAssignedUserId() : c.getAssignedAuditorId()) == null)
                .map(AuditControlInstance::getId)
                .toList();
        if (inheriting.isEmpty()) return 0;

        List<String> types = evidenceWork
                ? AuditObligationTypes.CONTROL_AUDITEE_TYPES
                : AuditObligationTypes.CONTROL_AUDITOR_TYPES;
        List<ActionItem> live = actionItemRepository.findAll(
                ActionItemSpecification.forTenant(tenantId)
                        .and(ActionItemSpecification.forEntities(ActionItem.EntityType.AUDIT_CONTROL_INSTANCE, inheriting))
                        .and(ActionItemSpecification.withStatus(LIVE)));
        int closed = 0;
        LocalDateTime now = LocalDateTime.now();
        for (ActionItem ai : live) {
            if (!types.contains(ai.getRemediationType())) continue;
            if (newOwner != null && (newOwner.equals(ai.getAssignedTo())
                    || newOwner.equals(ai.getResolutionReservedFor()))) continue;
            ai.setStatus(ActionItem.Status.RESOLVED);
            ai.setResolutionNote("Section reassigned — delegation closed");
            ai.setResolvedAt(now);
            ai.setResolvedBy(changedBy);
            actionItemRepository.save(ai);
            closed++;
        }
        if (closed > 0) {
            log.info("[AUDIT-OBLIGATION] Closed {} {} obligation(s) on section reassignment | engagementId={} sections={} newOwner={}",
                    closed, evidenceWork ? "evidence" : "testing", engagementId, sections.size(), newOwner);
        }
        return closed;
    }

    // ══════════════════════════════════════════════════════════════════════
    // REASSIGN A DELEGATION — revoke + delegate again, in one step
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Moves a live delegation to someone else.
     *
     * The generic PUT /v1/action-items/{id} refuses to re-point an audit item
     * (the assignee IS the access grant), so reassigning used to mean revoking
     * and delegating again by hand. This does both, in that safe order:
     *
     *   1. the NEW delegation is raised through the normal delegate path, so
     *      ControlAccessGuard re-checks the caller and the candidate list
     *      re-checks the new assignee — if either fails, nothing changes and
     *      the old delegation stays live;
     *   2. only then is the old item closed (DISMISSED, with a note) and its
     *      holder told.
     *
     * May be done by the delegator (the person the item is reserved for), its
     * creator, or a holder of that side's override permission — the same people
     * who could revoke it. Body: assignedTo (required); note / dueAt / priority
     * optional, defaulting to the old item's.
     */
    @Transactional
    public ActionItemResponse reassignDelegation(Long actionItemId, DelegateRequest req) {
        var me = utilityService.getLoggedInDataContext();
        ActionItem old = actionItemRepository.findById(actionItemId)
                .filter(a -> java.util.Objects.equals(a.getTenantId(), me.getTenantId()))
                .filter(a -> ActionItemService.AUDIT_INSTANCE_TYPES
                        .contains(a.getEntityType()))
                .orElseThrow(() -> notAccessible());
        if (!LIVE.contains(old.getStatus())) {
            throw new BusinessException("DELEGATION_CLOSED",
                    "This delegation is already closed. Delegate the work again instead.",
                    HttpStatus.CONFLICT);
        }
        if (req == null || req.getAssignedTo() == null) {
            throw new BusinessException("ASSIGNEE_REQUIRED", "Choose who to reassign this to.",
                    HttpStatus.BAD_REQUEST);
        }
        if (req.getAssignedTo().equals(old.getAssignedTo())) {
            throw new BusinessException("SAME_ASSIGNEE", "This is already delegated to that person.",
                    HttpStatus.BAD_REQUEST);
        }

        Work work = workOf(old.getRemediationType());
        boolean auditeeSide = work == Work.EVIDENCE;
        boolean mayManage = me.getId().equals(old.getResolutionReservedFor())
                || me.getId().equals(old.getCreatedBy())
                || guard.hasOverride(auditeeSide);
        if (!mayManage) {
            throw new BusinessException("NOT_DELEGATOR",
                    "Only the person who delegated this (or an override holder) can reassign it.",
                    HttpStatus.FORBIDDEN);
        }

        DelegateRequest next = new DelegateRequest();
        next.setAssignedTo(req.getAssignedTo());
        next.setWork(work.name());
        next.setNote(req.getNote() != null ? req.getNote() : old.getDescription());
        next.setDueAt(req.getDueAt() != null ? req.getDueAt()
                : old.getDueAt() != null ? old.getDueAt().toString() : null);
        next.setPriority(req.getPriority() != null ? req.getPriority() : old.getPriority());

        // 1 — raise the new one (all checks run here; a failure leaves the old live)
        ActionItemResponse created = switch (old.getEntityType()) {
            case AUDIT_CONTROL_INSTANCE -> delegateControl(old.getEntityId(), next);
            case AUDIT_TEST_INSTANCE    -> delegateTest(old.getEntityId(), next);
            case AUDIT_POLICY_INSTANCE  -> delegatePolicy(old.getEntityId(), next);
            default -> throw notAccessible();
        };

        // 2 — close the old one
        String newName = userRepository.findById(req.getAssignedTo())
                .map(u -> u.getFullName() != null ? u.getFullName() : u.getEmail())
                .orElse("user #" + req.getAssignedTo());
        old.setStatus(ActionItem.Status.DISMISSED);
        old.setResolutionNote("Reassigned to " + newName);
        old.setResolvedAt(LocalDateTime.now());
        old.setResolvedBy(me.getId());
        actionItemRepository.save(old);
        if (old.getAssignedTo() != null) {
            notificationService.send(old.getAssignedTo(), "AUDIT_DELEGATION_REASSIGNED",
                    "Reassigned to " + newName + ": " + truncate(old.getTitle(), 120),
                    old.getEntityType().name(), old.getEntityId());
        }
        log.info("[AUDIT-OBLIGATION] Delegation reassigned | oldItem={} {}→{} newItem={} by={}",
                old.getId(), old.getAssignedTo(), req.getAssignedTo(), created.getId(), me.getId());
        return created;
    }

    /** The kind of work an existing item delegates, from its remediation type. */
    private static Work workOf(String remediationType) {
        if (AuditObligationTypes.CONTROL_EVIDENCE_ASSIGNMENT.equals(remediationType)
                || AuditObligationTypes.CONTROL_REOPEN.equals(remediationType)) return Work.EVIDENCE;
        if (AuditObligationTypes.POLICY_REVIEW_ASSIGNMENT.equals(remediationType)) return Work.POLICY_REVIEW;
        return Work.TESTING;   // CONTROL_TEST_ASSIGNMENT, TEST_ASSIGNMENT
    }

    // ══════════════════════════════════════════════════════════════════════
    // INTERNALS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Resolves live items of the given types on one instance. assignee null =
     * whoever holds them. Idempotent: resolved items are not live, so a second
     * call closes nothing.
     */
    private int close(ActionItem.EntityType type, Long entityId, Long assignee, Long tenantId,
                      Collection<String> remediationTypes, String note, Long resolvedBy) {
        if (entityId == null || tenantId == null) return 0;
        int closed = 0;
        LocalDateTime now = LocalDateTime.now();
        for (ActionItem ai : liveItems(type, entityId, tenantId)) {
            if (!remediationTypes.contains(ai.getRemediationType())) continue;
            if (assignee != null && !assignee.equals(ai.getAssignedTo())) continue;
            ai.setStatus(ActionItem.Status.RESOLVED);
            ai.setResolutionNote(note);
            ai.setResolvedAt(now);
            ai.setResolvedBy(resolvedBy);
            actionItemRepository.save(ai);
            closed++;
        }
        if (closed > 0) {
            log.info("[AUDIT-OBLIGATION] Closed {} item(s) | {}={} assignee={} note='{}'",
                    closed, type, entityId, assignee, note);
        }
        return closed;
    }

    /**
     * The work is done on the control, so every live delegation of that kind on
     * it is closed — whoever did it. The delegate and the delegator are each
     * told (not the person who did it), so an item never just vanishes.
     */
    private void closeDoneDelegations(Long controlId, Long doneBy, Long tenantId,
                                      Collection<String> remediationTypes, String what) {
        if (controlId == null || tenantId == null) return;
        LocalDateTime now = LocalDateTime.now();
        String who = null;
        for (ActionItem ai : liveItems(ActionItem.EntityType.AUDIT_CONTROL_INSTANCE, controlId, tenantId)) {
            if (!remediationTypes.contains(ai.getRemediationType())) continue;
            ai.setStatus(ActionItem.Status.RESOLVED);
            ai.setResolutionNote("Completed on the control");
            ai.setResolvedAt(now);
            ai.setResolvedBy(doneBy);
            actionItemRepository.save(ai);
            if (who == null) {
                who = doneBy == null ? "Someone" : userRepository.findById(doneBy)
                        .map(u -> u.getFullName() != null && !u.getFullName().isBlank() ? u.getFullName().trim() : u.getEmail())
                        .orElse("Someone");
            }
            String msg = who + " completed the " + what + " for \"" + ai.getTitle() + "\" — the delegation is closed";
            for (Long to : new java.util.LinkedHashSet<>(java.util.Arrays.asList(ai.getAssignedTo(), ai.getResolutionReservedFor()))) {
                if (to == null || to.equals(doneBy)) continue;
                try {
                    notificationService.send(to, "AUDIT_DELEGATION_COMPLETED", msg, "AUDIT_CONTROL_INSTANCE", controlId);
                } catch (RuntimeException e) {
                    log.warn("[AUDIT-OBLIGATION] Notification failed (non-fatal) | {}", e.getMessage());
                }
            }
            log.info("[AUDIT-OBLIGATION] Closed delegation {} | control={} | done by {}", ai.getId(), controlId, doneBy);
        }
    }

    private List<ActionItem> liveItems(ActionItem.EntityType type, Long entityId, Long tenantId) {
        return actionItemRepository.findAll(
                        ActionItemSpecification.forTenant(tenantId)
                                .and(ActionItemSpecification.forEntity(type, entityId))
                                .and(ActionItemSpecification.withStatus(LIVE)));
    }

    private void requireNotAlreadyDelegated(ActionItem.EntityType type, Long entityId, Long assignee,
                                            String remediationType, Long tenantId) {
        Set<Long> hit = actionItemRepository.findEntityIdsWithLiveItemForAssignee(
                type.name(), List.of(entityId), assignee, tenantId, List.of(remediationType));
        if (!hit.isEmpty()) {
            throw new BusinessException("ALREADY_DELEGATED",
                    "This is already delegated to that person and still open.",
                    HttpStatus.CONFLICT);
        }
    }

    private void validateDelegatee(Long assignedTo, Long delegator, Work work, Long tenantId) {
        if (assignedTo == null) {
            throw new BusinessException("ASSIGNEE_REQUIRED", "Choose who to delegate this to.",
                    HttpStatus.BAD_REQUEST);
        }
        if (assignedTo.equals(delegator)) {
            throw new BusinessException("SELF_DELEGATION",
                    "You already hold this work — pick a colleague to delegate it to.",
                    HttpStatus.BAD_REQUEST);
        }
        boolean eligible = candidatesFor(work, tenantId).stream()
                .anyMatch(m -> m.get("id") instanceof Number n && n.longValue() == assignedTo);
        if (!eligible) {
            throw new BusinessException("DELEGATEE_NOT_ELIGIBLE",
                    "That user does not hold the permission for this work ("
                            + String.join(" or ", work.permissions) + ").",
                    HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Users in this tenant who hold any of the work's permissions, through the
     * same three layers WorkflowAccessService.resolvePermissions applies:
     *   role_permissions              role grant          (Role @JoinTable)
     *   permission_grants granted=1   RBAC-screen grant   (PermissionGrant)
     *                     granted=0   role-level deny
     *   user_permission_overrides     per user, is_active and unexpired
     *                                 (UserPermissionOverride); granted=0 denies
     * Users come back in the shape WorkflowEngineService.getUsersByRoles uses
     * (id, firstName, lastName, email, fullName, roleName, side).
     */
    private List<Map<String, Object>> candidatesFor(Work work, Long tenantId) {
        @SuppressWarnings("unchecked")
        List<Number> roleRows = em.createNativeQuery(
                        "SELECT rp.role_id FROM role_permissions rp "
                      + "  JOIN permissions p ON p.id = rp.permission_id "
                      + " WHERE p.code IN (:codes) "
                      + "UNION "
                      + "SELECT pg.role_id FROM permission_grants pg "
                      + "  JOIN permissions p2 ON p2.id = pg.permission_id "
                      + " WHERE p2.code IN (:codes) AND pg.granted = 1")
                .setParameter("codes", work.permissions)
                .getResultList();
        @SuppressWarnings("unchecked")
        List<Number> deniedRoleRows = em.createNativeQuery(
                        "SELECT pg.role_id FROM permission_grants pg "
                      + "  JOIN permissions p ON p.id = pg.permission_id "
                      + " WHERE p.code IN (:codes) AND pg.granted = 0")
                .setParameter("codes", work.permissions)
                .getResultList();
        Set<Long> deniedRoles = new HashSet<>();
        deniedRoleRows.forEach(n -> deniedRoles.add(n.longValue()));
        List<Long> roleIds = roleRows.stream().map(Number::longValue)
                .filter(id -> !deniedRoles.contains(id)).distinct().toList();

        Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();
        if (!roleIds.isEmpty()) {
            for (Map<String, Object> m : workflowEngineService.getUsersByRoles(roleIds, tenantId)) {
                if (m.get("id") instanceof Number n) byId.put(n.longValue(), m);
            }
        }

        // Per-user overrides, scoped to users of this tenant (home or member).
        @SuppressWarnings("unchecked")
        List<Object[]> overrideRows = em.createNativeQuery(
                        "SELECT o.user_id, o.granted FROM user_permission_overrides o "
                      + "  JOIN permissions p ON p.id = o.permission_id "
                      + " WHERE p.code IN (:codes) "
                      + "   AND o.is_active = 1 "
                      + "   AND (o.expires_at IS NULL OR o.expires_at > :now) "
                      + "   AND (o.user_id IN (SELECT u.id FROM users u WHERE u.tenant_id = :tenantId) "
                      + "        OR o.user_id IN (SELECT m.user_id FROM user_tenant_memberships m "
                      + "                          WHERE m.tenant_id = :tenantId))")
                .setParameter("codes", work.permissions)
                .setParameter("now", LocalDateTime.now())
                .setParameter("tenantId", tenantId)
                .getResultList();
        List<Long> grantedUsers = new ArrayList<>();
        for (Object[] row : overrideRows) {
            Long uid = ((Number) row[0]).longValue();
            boolean granted = row[1] instanceof Boolean b ? b : ((Number) row[1]).intValue() != 0;
            if (granted) grantedUsers.add(uid);
            else         byId.remove(uid);
        }
        grantedUsers.removeIf(byId::containsKey);
        if (!grantedUsers.isEmpty()) {
            userRepository.findAllById(grantedUsers).stream()
                    .filter(u -> !u.isDeleted())
                    .forEach(u -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id",        u.getId());
                        m.put("firstName", u.getFirstName());
                        m.put("lastName",  u.getLastName());
                        m.put("email",     u.getEmail());
                        m.put("fullName",  u.getFirstName() + " " + u.getLastName());
                        byId.put(u.getId(), m);
                    });
        }

        Set<Long> visible = com.kashi.grc.common.config.multitenancy.AccessScope.userIds();
        return byId.values().stream()
                .filter(m -> visible == null
                        || (m.get("id") instanceof Number n && visible.contains(n.longValue())))
                .toList();
    }

    private ActionItemRequest baseRequest(DelegateRequest req, Long delegator,
                                          ActionItem.EntityType type, Long entityId, Long engagementId,
                                          String remediationType, String title, String navContext) {
        ActionItemRequest r = new ActionItemRequest();
        r.setSourceType(ActionItem.SourceType.SYSTEM);   // direct actor delegation
        r.setSourceId(entityId);
        r.setEntityType(type);
        r.setEntityId(entityId);
        // The engagement is the artifact the inbox opens — see class javadoc.
        r.setParentEntityType(ActionItem.EntityType.AUDIT);
        r.setParentEntityId(engagementId);
        r.setAssignedTo(req.getAssignedTo());
        r.setResolutionReservedFor(delegator);
        r.setTitle(truncate(title, 250));
        r.setDescription(req.getNote() != null && !req.getNote().isBlank() ? req.getNote().trim() : null);
        // Null lets the blueprint's default_priority apply; ActionItemService
        // falls back to MEDIUM when there is no blueprint.
        r.setPriority(req.getPriority());
        r.setDueAt(normaliseDue(req.getDueAt()));
        r.setRemediationType(remediationType);
        // The blueprint whose blueprint_code is the remediation type (sql/92)
        // supplies nav_key, assigner_nav_key and default_priority:
        // ActionItemService.create resolves blueprintCode → blueprintId and uses
        // the blueprint's values wherever the request leaves them null.
        r.setBlueprintCode(remediationType);
        r.setNavContext(navContext);
        return r;
    }

    /**
     * Fully-resolved routes, used when the blueprint / nav rows are absent. Same
     * destinations as the nav rows: the engagement's Controls tab with the
     * instance open in a drawer — on the tab where the work is done for the
     * assignee, on its Action items tab for the delegator.
     */
    private String navContext(Long engagementId, String instanceType, Long instanceId,
                              String workTab, Work work) {
        String base = "/module/audit_engagement/" + engagementId
                + "?tab=controls&drawerType=" + instanceType + "&drawerId=" + instanceId;
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("assigneeRoute", workTab != null ? base + "&drawerTab=" + workTab : base);
        ctx.put("reviewerRoute", base + "&drawerTab=actions");
        ctx.put("engagementId",  engagementId);
        ctx.put("instanceType",  instanceType);
        ctx.put("instanceId",    instanceId);
        ctx.put("work",          work != null ? work.name() : null);
        try {
            return objectMapper.writeValueAsString(ctx);
        } catch (Exception ex) {
            return null;   // routes then fall back to nav_key alone
        }
    }

    /** Accepts a date or a date-time; returns the ISO date-time ActionItemService parses. */
    private String normaliseDue(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        try {
            if (s.length() == 10) return LocalDate.parse(s).atTime(23, 59, 59).toString();
            String noZone = s.endsWith("Z") ? s.substring(0, s.length() - 1) : s;
            if (noZone.length() > 19) noZone = noZone.substring(0, 19);
            return LocalDateTime.parse(noZone).toString();
        } catch (Exception ex) {
            throw new BusinessException("INVALID_DUE_DATE",
                    "Due date must be a date (2026-10-15) or date-time (2026-10-15T17:00:00).",
                    HttpStatus.BAD_REQUEST);
        }
    }

    private Work parseControlWork(DelegateRequest req) {
        Work w = Work.parse(req.getWork() != null ? req.getWork() : req.getSide());
        if (w == Work.EVIDENCE || w == Work.TESTING) return w;
        throw new BusinessException("WORK_REQUIRED",
                "Say which work on this control is being delegated: EVIDENCE or TESTING.",
                HttpStatus.BAD_REQUEST);
    }

    /** Tests and policies have one kind of work; anything else named is a mistake. */
    private void rejectOtherWork(DelegateRequest req, Work expected, String what) {
        String raw = req.getWork() != null ? req.getWork() : req.getSide();
        if (raw == null || raw.isBlank()) return;
        Work w = Work.parse(raw);
        // TESTING (side=AUDITOR in the first drop) was sent for tests and policies.
        if (w == expected || w == Work.TESTING) return;
        throw new BusinessException("INVALID_WORK", what + " can only be delegated as " + expected + ".",
                HttpStatus.BAD_REQUEST);
    }

    private Long nearestSectionEvidenceOwner(Long sectionId) {
        Long current = sectionId;
        int hops = 0;
        while (current != null && hops++ < 64) {
            AuditSectionInstance s = sectionRepo.findById(current).orElse(null);
            if (s == null) return null;
            if (s.getAuditeeAssignedUserId() != null) return s.getAuditeeAssignedUserId();
            current = s.getParentInstanceId();
        }
        return null;
    }

    private static String controlLabel(AuditControlInstance c) {
        return join(c.getControlCodeSnapshot(), c.getControlNameSnapshot());
    }

    private static String join(String code, String name) {
        String n = name == null ? "" : name.trim();
        if (code == null || code.isBlank()) return truncate(n, 200);
        return truncate(code.trim() + " — " + n, 200);
    }

    private static String dueSuffix(String due) {
        if (due == null || due.isBlank()) return "";
        String d = due.trim();
        return " (due " + d.substring(0, Math.min(10, d.length())) + ")";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    private static BusinessException notAccessible() {
        // Same answer for "does not exist" and "not yours" — see requireReadable.
        return new BusinessException("AUDIT_INSTANCE_NOT_ACCESSIBLE",
                "You do not have access to this record.", HttpStatus.FORBIDDEN);
    }
}
