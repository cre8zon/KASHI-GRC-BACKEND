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

import java.util.ArrayList;
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

        // ── ORGANIZATION SIDE: THE REVIEW LEAD, THE SECTION REVIEWERS, OR BOTH ─
        //
        // Returns the nominated lead CISO AND the reviewers assigned to
        // sections, and lets the ENGINE's role filter decide which of them this
        // particular step is for.
        //
        // That filter is already there, in the ASSIGNMENT_SCOPED branch of
        // assignTasksForStep: when a step has workflow_step_actor_roles rows, the
        // resolver's output is narrowed to users who currently hold one of
        // them. Every one of these steps has those rows — that is how
        // ROLE_BASED resolves them today, and a step with none creates no
        // tasks at all ("has no actorRoles — no ACTOR tasks created"). So:
        //
        //   step 10  Org CISO Assigns to Reviewers   CISO role      → the lead
        //   step 11  Reviewers Evaluate              Reviewer role  → reviewers
        //   step 12  Reviewers Consolidate Findings  Reviewer role  → reviewers
        //   step 13  Org CISO Approves and Rates     CISO role      → the lead
        //
        // ── WHY THE FILTER RATHER THAN DECIDING HERE ──────────────────────
        //
        // Steps 11 and 13 are BOTH ORGANIZATION + EVALUATE, so side and action
        // cannot separate them — and neither can step order or name without
        // writing workflow numbers or English into this class. The step's own
        // actor roles are the data that already says who a step is for, and
        // reading them is the engine's job, not this resolver's.
        //
        // ASSIGN is included for step 10, which matched nothing before and so
        // fell through to the whole CISO pool. That was the bug.
        //
        // The lead goes FIRST so that a tie in any future ordering favours the
        // nomination, and is added only when set — a null lead leaves this
        // exactly as it was, which is what every assessment predating the
        // column needs.
        if ("ORGANIZATION".equals(side)
                && ("ASSIGN".equals(action) || "REVIEW".equals(action) || "EVALUATE".equals(action))) {

            Long leadId = resolveReviewLeadUserId(instance);

            List<Long> reviewerIds = sectionInstanceRepository
                    .findDistinctAssignedReviewerIds(templateInstanceId);

            List<Long> ids = new ArrayList<>();
            if (leadId != null) ids.add(leadId);
            for (Long r : reviewerIds) if (!ids.contains(r)) ids.add(r);

            log.info("[VENDOR-ACTOR-RESOLVER] {} step '{}' | templateInstanceId={} | "
                            + "reviewLead={} | {} assigned reviewer(s) | {} candidate(s) before role filter",
                    action, si.getSnapName(), templateInstanceId,
                    leadId, reviewerIds.size(), ids.size());
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

    /**
     * The Org CISO nominated to lead this assessment's review, or null.
     *
     * Same walk as resolveTemplateInstanceId, stopping one step earlier. Kept
     * separate rather than folded into that method because the two answer
     * different questions and a combined one would have to return a pair just
     * to serve one caller each.
     *
     * Null is the normal, uninteresting case: every assessment created before
     * step 9 had a picker has no lead, and the caller then returns exactly what
     * it returned before this change.
     */
    private Long resolveReviewLeadUserId(WorkflowInstance instance) {
        return cycleRepository.findByWorkflowInstanceId(instance.getId())
                .map(cycle -> assessmentRepository.findByCycleId(cycle.getId())
                        .stream().findFirst().orElse(null))
                .map(com.kashi.grc.assessment.domain.VendorAssessment::getReviewLeadUserId)
                .orElse(null);
    }
}