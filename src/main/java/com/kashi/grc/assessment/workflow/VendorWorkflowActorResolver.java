package com.kashi.grc.assessment.workflow;

import com.kashi.grc.assessment.repository.AssessmentSectionInstanceRepository;
import com.kashi.grc.assessment.repository.AssessmentTemplateInstanceRepository;
import com.kashi.grc.assessment.repository.VendorAssessmentCycleRepository;
import com.kashi.grc.assessment.repository.VendorAssessmentRepository;
import com.kashi.grc.workflow.domain.StepInstance;
import com.kashi.grc.workflow.domain.WorkflowInstance;
import com.kashi.grc.workflow.spi.WorkflowActorResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Resolves ACTOR task recipients for VENDOR workflow steps
 * that have actorResolution = ASSIGNMENT_SCOPED.
 *
 * Lookup chain:
 *   WorkflowInstance.id → VendorAssessmentCycle → VendorAssessment → templateInstanceId
 *   → AssessmentSectionInstance.assignedUserId / reviewerAssignedUserId
 *
 * ── FILL steps (Responders Fill Questionnaires, Reviewers Evaluate Questions) ─
 *   stepAction = FILL, side = VENDOR
 *   → returns distinct assignedUserId values from assessment_section_instances
 *   → only Responders who were actually assigned sections by the CISO get tasks
 *
 * ── REVIEW / EVALUATE steps (org-side reviewer steps) ────────────────────────
 *   stepAction = REVIEW or EVALUATE, side = ORGANIZATION
 *   → returns distinct reviewerAssignedUserId values from assessment_section_instances
 *   → only Reviewers assigned sections get tasks
 *
 * ── All other steps ───────────────────────────────────────────────────────────
 *   Returns empty list → engine falls back to ROLE_BASED.
 *   CISO assign steps, VRM delegate steps, SYSTEM steps stay role-based.
 *
 * ── Safety ───────────────────────────────────────────────────────────────────
 *   Empty list → engine falls back to ROLE_BASED so no step ever stalls.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VendorWorkflowActorResolver implements WorkflowActorResolver {

    private final VendorAssessmentCycleRepository       cycleRepository;
    private final VendorAssessmentRepository            assessmentRepository;
    private final AssessmentTemplateInstanceRepository  templateInstanceRepository;
    private final AssessmentSectionInstanceRepository   sectionInstanceRepository;

    @Override
    public String entityType() {
        return "VENDOR";
    }

    @Override
    public List<Long> resolveActorIds(WorkflowInstance instance, StepInstance si) {
        Long templateInstanceId = resolveTemplateInstanceId(instance);
        if (templateInstanceId == null) {
            log.warn("[VENDOR-ACTOR-RESOLVER] No templateInstance found for workflowInstanceId={}",
                    instance.getId());
            return List.of();
        }

        String side   = si.getSnapSide()   != null ? si.getSnapSide().toUpperCase()   : "";
        String action = si.getSnapStepAction() != null ? si.getSnapStepAction().name() : "";

        // FILL on VENDOR side → Responders assigned sections by the CISO
        if ("VENDOR".equals(side) && "FILL".equals(action)) {
            List<Long> ids = sectionInstanceRepository
                    .findDistinctAssignedResponderIds(templateInstanceId);
            log.info("[VENDOR-ACTOR-RESOLVER] FILL step '{}' | templateInstanceId={} | {} assigned responder(s)",
                    si.getSnapName(), templateInstanceId, ids.size());
            return ids;
        }

        // REVIEW or EVALUATE on ORGANIZATION side → Reviewers assigned sections
        if ("ORGANIZATION".equals(side) && ("REVIEW".equals(action) || "EVALUATE".equals(action))) {
            List<Long> ids = sectionInstanceRepository
                    .findDistinctAssignedReviewerIds(templateInstanceId);
            log.info("[VENDOR-ACTOR-RESOLVER] {} step '{}' | templateInstanceId={} | {} assigned reviewer(s)",
                    action, si.getSnapName(), templateInstanceId, ids.size());
            return ids;
        }

        log.debug("[VENDOR-ACTOR-RESOLVER] side='{}' action='{}' not assignment-scoped — ROLE_BASED fallback",
                side, action);
        return List.of();
    }

    /**
     * workflowInstanceId → VendorAssessmentCycle → VendorAssessment → templateInstanceId
     */
    private Long resolveTemplateInstanceId(WorkflowInstance instance) {
        return cycleRepository.findByWorkflowInstanceId(instance.getId())
                .map(cycle -> assessmentRepository.findByCycleId(cycle.getId())
                        .stream().findFirst().orElse(null))
                .map(assessment -> templateInstanceRepository
                        .findByAssessmentId(assessment.getId()).orElse(null))
                .map(ti -> ti.getId())
                .orElse(null);
    }
}