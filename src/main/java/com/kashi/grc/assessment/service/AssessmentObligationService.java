package com.kashi.grc.assessment.service;

import com.kashi.grc.actionitem.domain.ActionItem;
import com.kashi.grc.actionitem.repository.ActionItemRepository;
import com.kashi.grc.actionitem.specification.ActionItemSpecification;
import com.kashi.grc.assessment.domain.AssessmentQuestionInstance;
import com.kashi.grc.assessment.repository.AssessmentQuestionInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Closes a person's open per-question ASSIGNMENT obligations for one section.
 *
 * ── WHY THIS EXISTS ───────────────────────────────────────────────────────
 *
 * The same loop was written twice, once per side:
 *
 *   AssessmentController.contributorSubmitSection   CONTRIBUTOR_ASSIGNMENT
 *   ReviewController.assistantSubmitSection         REVIEWER_ASSIGNMENT
 *
 * identical but for the remediation-type string and the resolution note. Both
 * sat below an early return that skipped them, which is the bug this service
 * was extracted to fix — and fixing it in place would have produced a third
 * and fourth copy, one per endpoint per path.
 *
 * ── THE BUG IT FIXES ──────────────────────────────────────────────────────
 *
 * A contributor is assigned one question in a section, answers it, and locks
 * the section. That writes a contributor_section_submissions row. Later the
 * responder assigns them a SECOND question in the SAME section:
 *
 *   • doAssignQuestion creates a new CONTRIBUTOR_ASSIGNMENT item, OPEN
 *   • they can answer it — QuestionItemCard's canEdit is
 *     `owedHere || (editable && !settled)`, and an open obligation beats the
 *     section lock, mirroring the server's own bypass
 *   • they cannot finish it. contributorSubmitSection's first statement is
 *     `if (exists(sectionInstanceId, userId)) return ALREADY_SUBMITTED`, and
 *     the closing loop is forty lines below it. The row is already there from
 *     the first question, so the loop never runs.
 *
 * The item stays OPEN forever, their inbox never clears, and the task gate is
 * never re-evaluated. The reviewer side is the same defect in the same shape.
 *
 * This is the third occurrence of one pattern — an early return that skips work
 * which must still happen. submitSection was fixed for it already and carries a
 * comment saying so; reviewerSubmitSection and these two were not.
 *
 * ── WHY NOT CLEAR THE SUBMISSION ROW ON ASSIGNMENT INSTEAD ────────────────
 *
 * That was the other candidate fix, and it is worse. Deleting the row would
 * make their EARLIER answers in that section editable again, silently, because
 * a responder added one unrelated question. The lock they took is a true
 * statement about the work they held when they took it; what was missing was
 * the way out of the new obligation, not the lock. owedHere already grants
 * exactly the one question they owe and nothing else.
 *
 * ── SCOPED THREE WAYS, AND ALL THREE MATTER ───────────────────────────────
 *
 *   this user's items     somebody else's obligation on the same question is
 *                         their own business
 *   questions in THIS     a lock is per-section; obligations elsewhere survive
 *   section
 *   only the assignment   a REVISION_REQUEST, a CLARIFICATION or a KashiGuard
 *   type                  finding on a question in this section MUST survive
 *                         the lock. The open obligation is precisely what
 *                         lets the person back in to answer it afterwards —
 *                         close it here and you have taken away their way in.
 *
 * One query, not one per question: the two copies this replaces both ran a
 * findAll per question instance, so a forty-question section was forty
 * queries. forEntities takes the id list.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssessmentObligationService {

    /** The two per-question assignment obligation types, by side. */
    public static final String CONTRIBUTOR_ASSIGNMENT = "CONTRIBUTOR_ASSIGNMENT";
    public static final String REVIEWER_ASSIGNMENT    = "REVIEWER_ASSIGNMENT";

    private final ActionItemRepository                 actionItemRepository;
    private final AssessmentQuestionInstanceRepository questionInstanceRepository;

    /**
     * Resolves every open {@code remediationType} obligation held by
     * {@code userId} on a question in {@code sectionInstanceId}.
     *
     * Idempotent: an already-resolved item is not matched by
     * ActionItemSpecification.open(), so calling this twice closes nothing the
     * second time and returns 0. That is what makes it safe to call from an
     * ALREADY_SUBMITTED path on every submit.
     *
     * @return how many items were closed — 0 is the normal, uninteresting case
     */
    @Transactional
    public int closeAssignmentObligations(Long sectionInstanceId,
                                          Long userId,
                                          Long tenantId,
                                          String remediationType,
                                          String resolutionNote) {

        if (sectionInstanceId == null || userId == null || tenantId == null
                || remediationType == null) {
            return 0;
        }

        List<Long> sectionQuestionIds = questionInstanceRepository
                .findBySectionInstanceIdOrderByOrderNo(sectionInstanceId)
                .stream()
                .map(AssessmentQuestionInstance::getId)
                .toList();

        if (sectionQuestionIds.isEmpty()) return 0;

        List<ActionItem> candidates = actionItemRepository.findAll(
                ActionItemSpecification.forTenant(tenantId)
                        .and(ActionItemSpecification.assignedTo(userId))
                        .and(ActionItemSpecification.forEntities(
                                ActionItem.EntityType.QUESTION_RESPONSE, sectionQuestionIds))
                        .and(ActionItemSpecification.open()));

        int closed = 0;
        LocalDateTime now = LocalDateTime.now();

        for (ActionItem ai : candidates) {
            // remediationType is filtered here rather than in the Specification
            // because it is a free-text column with no index and no enum behind
            // it; the predicate set above already narrows this to one person's
            // open items on one section's questions, which is a handful of rows
            // at most.
            if (!remediationType.equals(ai.getRemediationType())) continue;

            ai.setStatus(ActionItem.Status.RESOLVED);
            ai.setResolutionNote(resolutionNote);
            ai.setResolvedAt(now);
            ai.setResolvedBy(userId);
            actionItemRepository.save(ai);
            closed++;
        }

        if (closed > 0) {
            log.info("[OBLIGATION-CLOSE] Closed {} {} obligation(s) | si={} | userId={}",
                    closed, remediationType, sectionInstanceId, userId);
        } else {
            log.debug("[OBLIGATION-CLOSE] Nothing open | type={} | si={} | userId={}",
                    remediationType, sectionInstanceId, userId);
        }
        return closed;
    }

    /**
     * Does this person still owe an assignment obligation anywhere in this
     * section? The question the Lock control has to answer: a person who has
     * already submitted the section but has since been assigned another
     * question must be offered the control again, or the new obligation has no
     * way to close.
     *
     * Read-only counterpart of closeAssignmentObligations, matching on exactly
     * the same three scopes so the button and the endpoint cannot disagree.
     */
    @Transactional(readOnly = true)
    public boolean hasOpenAssignmentObligation(Long sectionInstanceId,
                                               Long userId,
                                               Long tenantId,
                                               String remediationType) {

        if (sectionInstanceId == null || userId == null || tenantId == null
                || remediationType == null) {
            return false;
        }

        List<Long> sectionQuestionIds = questionInstanceRepository
                .findBySectionInstanceIdOrderByOrderNo(sectionInstanceId)
                .stream()
                .map(AssessmentQuestionInstance::getId)
                .toList();

        if (sectionQuestionIds.isEmpty()) return false;

        return actionItemRepository.findAll(
                        ActionItemSpecification.forTenant(tenantId)
                                .and(ActionItemSpecification.assignedTo(userId))
                                .and(ActionItemSpecification.forEntities(
                                        ActionItem.EntityType.QUESTION_RESPONSE, sectionQuestionIds))
                                .and(ActionItemSpecification.open()))
                .stream()
                .anyMatch(ai -> remediationType.equals(ai.getRemediationType()));
    }
}