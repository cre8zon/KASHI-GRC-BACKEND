package com.kashi.grc.assessment.listener;

import com.kashi.grc.assessment.domain.AssessmentSectionInstance;
import com.kashi.grc.assessment.domain.AssessmentTemplateInstance;
import com.kashi.grc.assessment.domain.VendorAssessment;
import com.kashi.grc.assessment.domain.VendorAssessmentCycle;
import com.kashi.grc.assessment.repository.AssessmentSectionInstanceRepository;
import com.kashi.grc.assessment.repository.AssessmentTemplateInstanceRepository;
import com.kashi.grc.assessment.repository.ContributorSectionSubmissionRepository;
import com.kashi.grc.assessment.repository.ReviewerAssistantSectionSubmissionRepository;
import com.kashi.grc.assessment.repository.VendorAssessmentCycleRepository;
import com.kashi.grc.assessment.repository.VendorAssessmentRepository;
import com.kashi.grc.workflow.event.TaskResetEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Clears the assessment's OWN section locks when an admin resets a task.
 *
 * ── THE BUG ───────────────────────────────────────────────────────────────────
 *
 * An admin resets a "Responders Fill Questionnaires" task from the Workflow
 * tab. The task goes back to IN_PROGRESS, its section gates are re-armed, and
 * the responder sees it in their inbox again — and still cannot do anything,
 * because the questionnaire sections still read as submitted.
 *
 * WorkflowEngineService.resetTask does everything the ENGINE owns. The locks
 * that actually stop the responder are the module's, and the engine cannot see
 * them:
 *
 *   assessment_section_instances.submitted_at            responder's lock
 *   assessment_section_instances.reviewer_submitted_at   reviewer's lock
 *   contributor_section_submissions                      contributor's own lock
 *   reviewer_assistant_section_submissions               assistant's own lock
 *
 * With submitted_at still set, AssessmentSectionsTab's canSubmit is false
 * (`&& !submitted`) and AssessmentFillTab renders "Section locked by the
 * responder". So there is no control to press, the re-armed gate has nothing
 * that can fire it, and the step cannot complete by any route. The task was
 * reopened; the work was not.
 *
 * reset-reviewer-sections existed for half of this and its javadoc says "Pair
 * with workflowsApi.instances.resetTask() for full task reopen" — a pairing
 * nothing enforced and no caller performed. WorkflowTimeline, the only reset
 * button in the product, calls resetTask alone. There was no vendor-side
 * equivalent at all.
 *
 * ── SCOPED TO THE PERSON WHOSE TASK WAS RESET ─────────────────────────────────
 *
 * A reset is for ONE task and one task belongs to ONE person. Clearing every
 * responder's lock because one of them had their task reset would be the
 * multi-actor bug in a new place: responder 2 would silently lose their
 * submission because responder 1 was reset.
 *
 * So every query here filters on assignedUserId, and a null one does nothing —
 * an unassigned task has no owner whose locks could be meant.
 *
 * ── AND TO THE RIGHT LOCK ─────────────────────────────────────────────────────
 *
 * stepAction decides which lock the reset refers to:
 *
 *   FILL      the vendor side — submitted_at, and the contributor rows for that
 *             person. Never reviewer_submitted_at: an org reviewer's sign-off
 *             is not undone by reopening a vendor task.
 *   EVALUATE  the org side — reviewer_submitted_at and the assistant rows.
 *
 * Anything else is left alone. An ASSIGN or REVIEW step holds no section
 * submission of its own, and guessing would do damage for no gain.
 *
 * ── FAILURE IS NOT FATAL ──────────────────────────────────────────────────────
 *
 * This runs inside resetTask's transaction, so throwing would roll the reset
 * back. Being unable to clear a module lock must not leave the admin with no
 * reset at all — every path catches and logs.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssessmentTaskResetListener {

    /** WorkflowInstance.entityType for the TPRM workflow. */
    private static final String ENTITY_TYPE = "VENDOR_ASSESSMENT";

    private final VendorAssessmentCycleRepository               cycleRepository;
    private final VendorAssessmentRepository                    assessmentRepository;
    private final AssessmentTemplateInstanceRepository          templateInstanceRepository;
    private final AssessmentSectionInstanceRepository           sectionInstanceRepository;
    private final ContributorSectionSubmissionRepository        contributorSubmissionRepository;
    private final ReviewerAssistantSectionSubmissionRepository  assistantSubmissionRepository;

    @EventListener
    @Transactional
    public void onTaskReset(TaskResetEvent event) {
        try {
            if (event.assignedUserId() == null) {
                log.debug("[ASSESSMENT-RESET] Task {} has no assignee — nothing to unlock",
                        event.taskInstanceId());
                return;
            }

            String action = event.stepAction() == null ? "" : event.stepAction().toUpperCase();
            boolean vendorSide = "FILL".equals(action);
            boolean orgSide    = "EVALUATE".equals(action);
            if (!vendorSide && !orgSide) {
                log.debug("[ASSESSMENT-RESET] stepAction={} holds no section lock — nothing to unlock | taskId={}",
                        event.stepAction(), event.taskInstanceId());
                return;
            }

            VendorAssessment assessment = resolveAssessment(event);
            if (assessment == null) return;

            AssessmentTemplateInstance ti = templateInstanceRepository
                    .findByAssessmentId(assessment.getId()).orElse(null);
            if (ti == null) {
                log.warn("[ASSESSMENT-RESET] No template instance for assessmentId={} — cannot unlock",
                        assessment.getId());
                return;
            }

            Long userId = event.assignedUserId();
            int clearedSections = 0;
            int clearedRows     = 0;

            if (vendorSide) {
                // The responder's own sections. assigned_user_id is the vendor-side
                // owner — the same column SectionRow's `mine` and
                // fireSectionGateIfAllSubmitted both read, so what unlocks here is
                // exactly what the Submit control will reappear on.
                List<AssessmentSectionInstance> mine = sectionInstanceRepository
                        .findByTemplateInstanceIdAndAssignedUserIdOrderBySectionOrderNo(ti.getId(), userId);

                for (AssessmentSectionInstance sec : mine) {
                    if (sec.getSubmittedAt() != null) {
                        sec.setSubmittedAt(null);
                        sec.setSubmittedBy(null);
                        sec.setReopenedAt(LocalDateTime.now());
                        sec.setReopenedBy(event.performedBy());
                        clearedSections++;
                    }
                    // The contributor rows for THIS section, whoever holds them.
                    //
                    // Not filtered to userId, deliberately, and this is the one
                    // place that is right: the responder's section is being
                    // reopened, and a contributor of theirs who locked their own
                    // answers inside it would otherwise be stuck behind a lock on
                    // a section that is no longer submitted — the same dead end
                    // sql/95 repaired, arriving by a different road. The
                    // responder's own contributor-reopen already does exactly
                    // this for exactly this reason.
                    var rows = contributorSubmissionRepository
                            .findBySectionInstanceId(sec.getId());
                    if (!rows.isEmpty()) {
                        contributorSubmissionRepository.deleteAll(rows);
                        clearedRows += rows.size();
                    }
                }
                sectionInstanceRepository.saveAll(mine);
            }

            if (orgSide) {
                // The mirror, on reviewer_assigned_user_id. Same logic as the
                // existing reset-reviewer-sections endpoint, which stays as it is
                // for the admin who wants it on its own.
                List<AssessmentSectionInstance> mine = sectionInstanceRepository
                        .findByTemplateInstanceIdAndReviewerAssignedUserIdOrderBySectionOrderNo(
                                ti.getId(), userId);

                for (AssessmentSectionInstance sec : mine) {
                    if (sec.getReviewerSubmittedAt() != null) {
                        sec.setReviewerSubmittedAt(null);
                        sec.setReviewerSubmittedBy(null);
                        sec.setReviewerReopenedAt(LocalDateTime.now());
                        sec.setReviewerReopenedBy(event.performedBy());
                        clearedSections++;
                    }
                    var rows = assistantSubmissionRepository
                            .findBySectionInstanceId(sec.getId());
                    if (!rows.isEmpty()) {
                        assistantSubmissionRepository.deleteAll(rows);
                        clearedRows += rows.size();
                    }
                }
                sectionInstanceRepository.saveAll(mine);
            }

            log.info("[ASSESSMENT-RESET] Unlocked after task reset | assessmentId={} | taskId={} | " +
                            "userId={} | side={} | sectionsUnlocked={} | submissionRowsCleared={}",
                    assessment.getId(), event.taskInstanceId(), userId,
                    vendorSide ? "VENDOR" : "ORGANIZATION", clearedSections, clearedRows);

        } catch (Exception ex) {
            // Never fail the reset itself — see the class javadoc.
            log.error("[ASSESSMENT-RESET] Could not unlock sections after task reset (non-fatal) | " +
                    "taskId={} | {}", event.taskInstanceId(), ex.getMessage(), ex);
        }
    }

    /**
     * The assessment behind this workflow instance.
     *
     * entityType/entityId are set when the instance is created and are the
     * reliable route; the cycle lookup is the fallback for TPRM instances whose
     * entity is the CYCLE rather than the assessment, which is how
     * EXECUTE_ASSESSMENT records it and how AssessmentSectionItemRegistrar
     * resolves the same thing.
     */
    private VendorAssessment resolveAssessment(TaskResetEvent event) {
        if (ENTITY_TYPE.equals(event.entityType()) && event.entityId() != null) {
            VendorAssessment direct = assessmentRepository.findById(event.entityId()).orElse(null);
            if (direct != null) return direct;
        }

        VendorAssessmentCycle cycle = cycleRepository
                .findByWorkflowInstanceId(event.workflowInstanceId()).orElse(null);
        if (cycle == null) {
            log.debug("[ASSESSMENT-RESET] workflowInstanceId={} is not a vendor assessment — ignoring",
                    event.workflowInstanceId());
            return null;
        }

        List<VendorAssessment> assessments = assessmentRepository.findByCycleId(cycle.getId());
        if (assessments.isEmpty()) {
            log.warn("[ASSESSMENT-RESET] No assessment for cycleId={} | taskId={}",
                    cycle.getId(), event.taskInstanceId());
            return null;
        }
        // Most cycles carry one; take the most recent, as the item registrar does.
        return assessments.get(assessments.size() - 1);
    }
}