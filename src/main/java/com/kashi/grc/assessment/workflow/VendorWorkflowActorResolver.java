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
 * ── FILL / REVIEW steps on the VENDOR side ───────────────────────────────────
 *   stepAction = FILL or REVIEW, side = VENDOR
 *   → returns distinct assignedUserId values from assessment_section_instances
 *   → only Responders who were actually assigned sections by the CISO get tasks
 *
 *   REVIEW belongs here as well as FILL: "Responders Review and Publish Answers"
 *   is the same population doing the next thing to the same sections. Previously
 *   only FILL was matched, so REVIEW returned an empty list, the engine logged
 *   "resolver returned 0 users" and fell back to ROLE_BASED — which fans out to
 *   EVERY user holding VENDOR_RESPONDER, including responders who own no
 *   sections and therefore have nothing to review. Those tasks can never be
 *   completed by their owner and stall the step.
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

        // FILL or REVIEW on VENDOR side → Responders assigned sections by the CISO.
        //
        // Safe for other VENDOR-side REVIEW steps (e.g. "Vendor CISO Final Review"):
        // the engine applies the step's configured actor roles as a filter to
        // whatever this returns, and when that filter empties the list it falls
        // back to ROLE_BASED. So a CISO-only REVIEW step still resolves to the
        // CISO pool rather than to section responders.
        if ("VENDOR".equals(side) && ("FILL".equals(action) || "REVIEW".equals(action))) {
            List<Long> ids = sectionInstanceRepository
                    .findDistinctAssignedResponderIds(templateInstanceId);
            log.info("[VENDOR-ACTOR-RESOLVER] {} step '{}' | templateInstanceId={} | {} assigned responder(s)",
                    action, si.getSnapName(), templateInstanceId, ids.size());
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