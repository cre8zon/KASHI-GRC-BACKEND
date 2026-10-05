package com.kashi.grc.assessment.service;

import com.kashi.grc.actionitem.domain.ActionItem;
import com.kashi.grc.actionitem.repository.ActionItemRepository;
import com.kashi.grc.actionitem.specification.ActionItemSpecification;
import com.kashi.grc.assessment.domain.AssessmentQuestionInstance;
import com.kashi.grc.assessment.domain.AssessmentSectionInstance;
import com.kashi.grc.assessment.domain.VendorAssessment;
import com.kashi.grc.assessment.domain.VendorAssessmentCycle;
import com.kashi.grc.assessment.repository.AssessmentQuestionInstanceRepository;
import com.kashi.grc.assessment.repository.AssessmentSectionInstanceRepository;
import com.kashi.grc.assessment.repository.VendorAssessmentCycleRepository;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.usermanagement.domain.RoleSide;
import com.kashi.grc.usermanagement.domain.User;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.workflow.enums.TaskStatus;
import com.kashi.grc.workflow.repository.TaskInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Who is allowed to do what on an assessment.
 *
 * ── WHY THIS COMES OUT FIRST ──────────────────────────────────────────────
 * "Every step can be performed only by those having permission and the
 * assigned task, or for individual, just the assigned contributor" is the
 * requirement that touches every endpoint. Right now it lives as three private
 * methods on AssessmentController — assertUserHasActiveTask,
 * assertUserHasParticipated, hasOpenActionItemForAssessment — which means
 * nothing outside that one class can enforce it. ReviewController re-derives
 * its own version; the v2 screens would have had to derive a third.
 *
 * Every remaining extraction depends on this one, so it goes first.
 *
 * ── THE THREE GATES, AND WHY THEY ARE NOT ONE ─────────────────────────────
 *   canAct        — has an active task, or an open obligation. For writes.
 *   canRead       — has ever participated. For reads.
 *   canEditAnswer — the above, plus the section is not locked.
 *
 * They are deliberately distinct. Collapsing read into write would lock a
 * contributor out of a page they are meant to watch after their task closes;
 * collapsing the section lock into the task check would lose the bypass that
 * makes revision requests work at all.
 *
 * Logic is preserved exactly from the controller, including every bypass. The
 * bypasses are not leniency — each one exists because without it a legitimate
 * flow dead-ends.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssessmentAccessGuard {

    private final VendorAssessmentCycleRepository       cycleRepository;
    private final TaskInstanceRepository                taskInstanceRepository;
    private final ActionItemRepository                  actionItemRepository;
    private final AssessmentQuestionInstanceRepository  questionInstanceRepository;
    private final AssessmentSectionInstanceRepository   sectionInstanceRepository;
    private final UtilityService                        utilityService;

    // ═════════════════════════════════════════════════════════════════════════
    // WRITE GATE
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Asserts the caller has an active (PENDING or IN_PROGRESS) task on the
     * workflow instance driving this assessment, or an open obligation on it.
     *
     * Preserved exactly from AssessmentController.assertUserHasActiveTask,
     * including both skips:
     *
     *   · SYSTEM users pass. A platform operator has no workflow task by
     *     construction, and support cannot be locked out of every tenant.
     *   · An assessment with no linked workflow instance passes. That is data
     *     created outside the workflow — seeds, imports, legacy rows — and
     *     denying it would make those assessments permanently unusable rather
     *     than merely ungated.
     */
    @Transactional(readOnly = true)
    public void assertCanAct(VendorAssessment assessment, Long userId, String action) {
        User currentUser = utilityService.getLoggedInDataContext();
        if (hasSide(currentUser, RoleSide.SYSTEM)) {
            log.debug("[ASSESSMENT-GUARD] System user — skipping task guard | userId={} | assessmentId={}",
                    userId, assessment.getId());
            return;
        }

        Long workflowInstanceId = workflowInstanceOf(assessment);
        if (workflowInstanceId == null) {
            log.debug("[ASSESSMENT-GUARD] No workflow instance linked for assessmentId={} — skipping task guard",
                    assessment.getId());
            return;
        }

        boolean hasActiveTask = taskInstanceRepository.existsByUserIdAndWorkflowInstanceIdAndStatusIn(
                userId, workflowInstanceId, List.of(TaskStatus.PENDING, TaskStatus.IN_PROGRESS));

        if (hasActiveTask) {
            log.debug("[ASSESSMENT-GUARD] Access granted | userId={} | assessmentId={} | action='{}'",
                    userId, assessment.getId(), action);
            return;
        }

        // The obligation bypass. A contributor sent back to fix one answer has
        // no workflow task of their own — their work is carried by an action
        // item. Without this, being asked to fix something makes it impossible
        // to fix it.
        if (hasOpenObligationFor(userId, assessment.getId(), assessment.getTenantId())) {
            log.info("[ASSESSMENT-GUARD] Open obligation bypass | userId={} | assessmentId={} | action='{}'",
                    userId, assessment.getId(), action);
            return;
        }

        log.warn("[ASSESSMENT-GUARD] Access denied | userId={} | assessmentId={} | "
                        + "workflowInstanceId={} | action='{}' — no active task or open obligation",
                userId, assessment.getId(), workflowInstanceId, action);
        throw new BusinessException("ACCESS_DENIED",
                "You do not have an active task or open obligation for this assessment.",
                HttpStatus.FORBIDDEN);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // READ GATE
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Read access — anyone who has ever had a task on this workflow instance.
     *
     * Preserved exactly from assertUserHasParticipated, including the blanket
     * pass for ORGANIZATION users: the assessing org owns the assessment, so
     * any of its users may look at one. Contributors are covered by the action
     * item check, because they are assigned through obligations rather than
     * workflow tasks and would otherwise be unable to open the page they were
     * asked to work on.
     */
    @Transactional(readOnly = true)
    public void assertCanRead(VendorAssessment assessment, Long userId, String action) {
        User currentUser = utilityService.getLoggedInDataContext();
        if (hasSide(currentUser, RoleSide.SYSTEM))       return;
        if (hasSide(currentUser, RoleSide.ORGANIZATION)) return;

        Long workflowInstanceId = workflowInstanceOf(assessment);
        if (workflowInstanceId == null) return;

        boolean hasParticipated =
                taskInstanceRepository.existsByUserIdAndWorkflowInstanceId(userId, workflowInstanceId)
                || actionItemRepository.existsByAssignedToAndAssessmentId(userId, assessment.getId());

        if (!hasParticipated) {
            log.warn("[ASSESSMENT-GUARD] Read access denied | userId={} | assessmentId={} | action='{}'",
                    userId, assessment.getId(), action);
            throw new BusinessException("ACCESS_DENIED",
                    "You do not have access to this assessment.", HttpStatus.FORBIDDEN);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SECTION LOCK
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Asserts one question is still editable by this caller.
     *
     * A submitted section is locked. The bypass: if the caller has an open
     * action item on THIS question, the lock lifts, because they were
     * explicitly sent back to fix this answer and blocking them defeats the
     * point of the action item.
     *
     * Note the bypass is per question, not per section — an open item on
     * question 7 does not unlock question 8. That is the original behaviour and
     * it is the right one: a revision request is a licence to change the thing
     * that was queried, not to reopen the whole section after submission.
     */
    @Transactional(readOnly = true)
    public void assertAnswerEditable(Long questionInstanceId, Long userId, Long tenantId) {
        if (questionInstanceId == null) return;

        AssessmentQuestionInstance qi =
                questionInstanceRepository.findById(questionInstanceId).orElse(null);
        if (qi == null) return;

        AssessmentSectionInstance si =
                sectionInstanceRepository.findById(qi.getSectionInstanceId()).orElse(null);
        if (si == null || si.getSubmittedAt() == null) return;

        boolean hasOpenObligation = !actionItemRepository.findAll(
                ActionItemSpecification.forTenant(tenantId)
                        .and(ActionItemSpecification.assignedTo(userId))
                        .and(ActionItemSpecification.forEntity(
                                ActionItem.EntityType.QUESTION_RESPONSE, qi.getId()))
                        .and(ActionItemSpecification.open())
        ).isEmpty();

        if (!hasOpenObligation) {
            throw new BusinessException("SECTION_SUBMITTED",
                    "Section is submitted and locked. Ask CISO/VRM to reopen it.",
                    HttpStatus.FORBIDDEN);
        }
        log.info("[SECTION-LOCK] Bypass for open obligation | userId={} | qi={} | si={}",
                userId, qi.getId(), si.getId());
    }

    // ═════════════════════════════════════════════════════════════════════════
    // INTERNALS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Any open obligation on this assessment — on the assessment itself, or on
     * one of its questions.
     *
     * ── THE N+1 IS GONE ───────────────────────────────────────────────────
     * The original resolved the second branch by streaming every open
     * QUESTION_RESPONSE item for the user and calling findById on each one's
     * entityId to see which assessment it belonged to. One query per open item,
     * on a guard that runs on every single write.
     *
     * Same answer, one batched read. The result is identical because the
     * predicate is identical — this only changes how many round trips it takes
     * to evaluate it.
     */
    private boolean hasOpenObligationFor(Long userId, Long assessmentId, Long tenantId) {
        boolean directMatch = !actionItemRepository.findAll(
                ActionItemSpecification.forTenant(tenantId)
                        .and(ActionItemSpecification.assignedTo(userId))
                        .and(ActionItemSpecification.forEntity(
                                ActionItem.EntityType.ASSESSMENT, assessmentId))
                        .and(ActionItemSpecification.open())
        ).isEmpty();
        if (directMatch) return true;

        List<ActionItem> questionItems = actionItemRepository.findAll(
                ActionItemSpecification.forTenant(tenantId)
                        .and(ActionItemSpecification.assignedTo(userId))
                        .and(ActionItemSpecification.withEntityType(
                                ActionItem.EntityType.QUESTION_RESPONSE))
                        .and(ActionItemSpecification.open()));
        if (questionItems.isEmpty()) return false;

        Set<Long> questionIds = questionItems.stream()
                .map(ActionItem::getEntityId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        if (questionIds.isEmpty()) return false;

        Map<Long, AssessmentQuestionInstance> byId =
                questionInstanceRepository.findAllById(questionIds).stream()
                        .collect(Collectors.toMap(AssessmentQuestionInstance::getId,
                                Function.identity(), (a, b) -> a));

        return questionIds.stream()
                .map(byId::get)
                .filter(java.util.Objects::nonNull)
                .anyMatch(qi -> assessmentId.equals(qi.getAssessmentId()));
    }

    private Long workflowInstanceOf(VendorAssessment assessment) {
        VendorAssessmentCycle cycle =
                cycleRepository.findById(assessment.getCycleId()).orElse(null);
        return (cycle == null) ? null : cycle.getWorkflowInstanceId();
    }

    private boolean hasSide(User u, RoleSide side) {
        return u != null && u.getRoles() != null
                && u.getRoles().stream().anyMatch(r -> r.getSide() == side);
    }
}
