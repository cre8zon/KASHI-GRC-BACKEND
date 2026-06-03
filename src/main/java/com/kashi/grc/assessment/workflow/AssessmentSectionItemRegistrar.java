package com.kashi.grc.assessment.workflow;

import com.kashi.grc.assessment.domain.AssessmentQuestionInstance;
import com.kashi.grc.assessment.domain.VendorAssessment;
import com.kashi.grc.assessment.domain.VendorAssessmentCycle;
import com.kashi.grc.assessment.repository.AssessmentQuestionInstanceRepository;
import com.kashi.grc.assessment.repository.VendorAssessmentCycleRepository;
import com.kashi.grc.assessment.repository.VendorAssessmentRepository;
import com.kashi.grc.workflow.event.SectionItemsNeededEvent;
import com.kashi.grc.workflow.service.TaskSectionCompletionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Populates TaskSectionItem rows for sections that track QUESTION_RESPONSE items.
 *
 * Listens for SectionItemsNeededEvent where itemRefType = "QUESTION_RESPONSE".
 * Looks up the VendorAssessment for this workflow instance, loads all question
 * instances, and registers them as TaskSectionItems so CompoundSectionRenderer
 * can display and track each question individually.
 *
 * ── BACKWARD COMPATIBILITY ────────────────────────────────────────────────────
 * This listener only fires when:
 *   1. A blueprint section has tracksItems=true AND itemRefType="QUESTION_RESPONSE"
 *   2. That section's step activates (snapshotSectionsForTask runs)
 *
 * Existing TPRM blueprints have NO sections defined → snapshotSectionsForTask
 * returns immediately → zero events fired → this listener never runs.
 * VendorAssessmentFillPage continues to work exactly as before.
 *
 * ── WHEN THIS FIRES (new generic blueprints only) ─────────────────────────────
 * 1. Workflow starts → EXECUTE_ASSESSMENT creates the assessment + question instances
 * 2. FILL step activates → snapshotSectionsForTask creates TaskSectionCompletion
 *    for the QUESTIONS section → fires SectionItemsNeededEvent
 * 3. This listener catches it → loads question instances → registers as items
 * 4. CompoundSectionRenderer renders each question using itemScreenKey config
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssessmentSectionItemRegistrar {

    private static final String ITEM_REF_TYPE = "QUESTION_RESPONSE";

    private final VendorAssessmentCycleRepository        cycleRepository;
    private final VendorAssessmentRepository             assessmentRepository;
    private final AssessmentQuestionInstanceRepository   questionInstanceRepository;
    private final TaskSectionCompletionService           sectionService;

    @EventListener
    @Transactional
    public void onSectionItemsNeeded(SectionItemsNeededEvent event) {
        // Only handle QUESTION_RESPONSE sections
        if (!ITEM_REF_TYPE.equalsIgnoreCase(event.itemRefType())) {
            return;
        }

        log.info("[ASSESSMENT_ITEM_REGISTRAR] Registering question items | " +
                        "workflowInstanceId={} | sectionKey={} | taskInstanceId={}",
                event.workflowInstanceId(), event.sectionKey(), event.taskInstanceId());

        // ── Find the assessment for this workflow instance ─────────────────────
        // EXECUTE_ASSESSMENT stores the workflowInstanceId on the cycle.
        VendorAssessmentCycle cycle = cycleRepository
                .findByWorkflowInstanceId(event.workflowInstanceId())
                .orElse(null);

        if (cycle == null) {
            log.warn("[ASSESSMENT_ITEM_REGISTRAR] No assessment cycle found for " +
                            "workflowInstanceId={} — EXECUTE_ASSESSMENT may not have run yet",
                    event.workflowInstanceId());
            return;
        }

        List<VendorAssessment> assessments = assessmentRepository.findByCycleId(cycle.getId());
        if (assessments.isEmpty()) {
            log.warn("[ASSESSMENT_ITEM_REGISTRAR] No assessment found for cycleId={}",
                    cycle.getId());
            return;
        }

        // Most cycles have one assessment; take the most recent if multiple exist
        VendorAssessment assessment = assessments.get(assessments.size() - 1);

        // ── Load all question instances for this assessment ────────────────────
        List<AssessmentQuestionInstance> questions =
                questionInstanceRepository.findByAssessmentIdOrderByOrderNo(assessment.getId());

        if (questions.isEmpty()) {
            log.warn("[ASSESSMENT_ITEM_REGISTRAR] No question instances found for " +
                    "assessmentId={}", assessment.getId());
            return;
        }

        // ── Register as TaskSectionItems ───────────────────────────────────────
        // Each question instance becomes one item in the section.
        // itemRefType=QUESTION_RESPONSE, itemRefId=questionInstanceId,
        // itemLabel=first 200 chars of the question text snapshot.
        List<TaskSectionCompletionService.ItemRegistration> registrations = questions.stream()
                .map(q -> new TaskSectionCompletionService.ItemRegistration(
                        ITEM_REF_TYPE,
                        q.getId(),
                        truncate(q.getQuestionTextSnapshot(), 200)
                ))
                .toList();

        sectionService.registerItems(
                event.taskInstanceId(),
                event.sectionKey(),
                registrations
        );

        log.info("[ASSESSMENT_ITEM_REGISTRAR] Registered {} question items | " +
                        "assessmentId={} | sectionKey={} | taskInstanceId={}",
                registrations.size(), assessment.getId(),
                event.sectionKey(), event.taskInstanceId());
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen - 1) + "…";
    }
}