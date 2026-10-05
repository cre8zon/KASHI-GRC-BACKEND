package com.kashi.grc.assessment.listener;

import com.kashi.grc.actionitem.domain.ActionItem;
import com.kashi.grc.actionitem.event.ActionItemCreatedEvent;
import com.kashi.grc.actionitem.repository.ActionItemRepository;
import com.kashi.grc.assessment.domain.AssessmentQuestionInstance;
import com.kashi.grc.assessment.domain.VendorAssessment;
import com.kashi.grc.assessment.repository.AssessmentQuestionInstanceRepository;
import com.kashi.grc.assessment.repository.VendorAssessmentRepository;
import com.kashi.grc.assessment.service.AssessmentIssueEscalationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * A finding on a vendor assessment question becomes an Issue by itself.
 *
 * ── BOTH PATHS, ONE DOOR ──────────────────────────────────────────────────
 * Two things raise a finding against an assessment question, and they differ
 * only in who decided:
 *
 *   KashiGuard   — a rule the author wrote in advance matched a real answer
 *                  ("no file attached to the policy question", "the answer was
 *                  'No formal process'", "score below 40"). Not an opinion.
 *   A reviewer   — they read the answer, judged it insufficient, and chose a
 *                  severity and a due date in the remediation panel.
 *
 * Both produce the same thing: an open REMEDIATION_REQUEST against one question
 * instance, owned by the vendor user who has to fix it. So both escalate here,
 * through the same service, and severity mapping, workflow resolution and the
 * no-owner fallback cannot drift apart between them.
 *
 * This listener used to take only the guard path, on the reasoning that a
 * reviewer's judgement deserved a second confirmation. In practice that second
 * step is a button somebody has to remember: until it is pressed the finding is
 * an action item, the vendor's remediation workflow has not started, and nothing
 * in the product says so. The reviewer already made the decision when they filled
 * in the panel and pressed Request remediation — asking them to confirm it again
 * on a different screen is not a safeguard, it is a place for work to stop.
 *
 * The ActionItem is not replaced by the Issue; it remains the FINDING RECORD,
 * which is the part AuditFinding plays on the audit side. The Findings tab
 * queries it, vendor scoping keys on its vendorId, and CloseIssueAction walks
 * back through it to resolve the finding and resync the score. What moved to the
 * Issue is the lifecycle — remediate, evidence, validate, close — which is what
 * the action-item path was standing in for before the Issue module existed.
 *
 * Both end as an EXTERNAL Issue on the same workflow with the same severity
 * mapping — deliberately unlike the audit module, whose two automatic paths
 * hardcode severity MEDIUM and type INTERNAL while its manual path maps severity
 * and writes EXTERNAL, so identical findings produce different issues depending
 * on how they were raised.
 *
 * Escalate on the Findings tab stays, as the recovery path: a finding whose rule
 * assigned by group role has no owner and cannot be escalated automatically, and
 * a failed escalation leaves the finding standing. Both are cases where a person
 * picks an owner and presses it.
 *
 * ── WHY IT LIVES HERE AND NOT IN THE GUARD MODULE ─────────────────────────
 * KashiGuard raises findings for every module. Audit does not want this, and has
 * its own escalation with its own rules. Branching on entity type inside
 * GuardEvaluator would make the shared component know about its consumers. So
 * the guard publishes, and the module that cares subscribes — every other module
 * carries on with no listener and no change.
 *
 * ── WHAT IT ALSO FIXES IN PASSING ─────────────────────────────────────────
 * GuardEvaluator cannot set parentEntityType/parentEntityId or vendorId: it does
 * not know that a question instance belongs to an assessment, and should not.
 * Without them a guard finding never appeared in the Findings tab's primary
 * query (entityType=ASSESSMENT) and nothing downstream could scope it to a
 * vendor. The assessment module can resolve its own parent, so it does, here,
 * before escalating.
 *
 * A reviewer's finding arrives with all three already stamped by
 * ReviewController.requestRemediation, so the enrich block below is a no-op for
 * it and saves nothing. That is the right shape: the raiser sets what it knows,
 * this fills only what was missing, and neither has to know which path it is on.
 *
 * ── NAME ──────────────────────────────────────────────────────────────────
 * The class is still called AssessmentGuardFindingListener and now handles both
 * paths, so the name under-describes it. Left as-is deliberately: renaming it
 * means deleting a file and adding another on the module we are treating as the
 * lifeline, for no behaviour change. Worth doing in a quiet drop, not this one.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssessmentGuardFindingListener {

    private final ActionItemRepository                actionItemRepository;
    private final AssessmentQuestionInstanceRepository questionInstanceRepository;
    private final VendorAssessmentRepository          assessmentRepository;
    private final AssessmentIssueEscalationService    escalationService;

    /** Escalations raised by the platform, not by a person. */
    private static final Long SYSTEM_USER_ID = 0L;

    /**
     * Who the Issue is recorded as having been raised by.
     *
     * This is the one place the two paths legitimately differ, and it is
     * attribution rather than behaviour. A guard finding was raised by the
     * platform, so the Issue is the platform's — SYSTEM_USER_ID, as before. A
     * reviewer's finding was raised by that reviewer, and recording it as the
     * platform's would erase the only trace of who made the call: the Issue's
     * createdBy is what the issue screen shows and what an auditor reads later.
     *
     * Falls back to SYSTEM_USER_ID rather than passing null, because
     * Issue.createdBy is NOT NULL.
     */
    private static Long escalatingUserId(ActionItem item) {
        if (item.getSourceType() == ActionItem.SourceType.SYSTEM) return SYSTEM_USER_ID;
        return item.getCreatedBy() != null ? item.getCreatedBy() : SYSTEM_USER_ID;
    }

    /**
     * AFTER_COMMIT because the item must exist before we read it back and write
     * to it. @Async so a slow workflow start never delays the answer save that
     * triggered the guard. REQUIRES_NEW because the publishing transaction is
     * already committed — without it the enrich-and-escalate writes would have
     * no transaction to join.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onActionItemCreated(ActionItemCreatedEvent event) {
        ActionItem item = event.item();
        if (item == null || item.getId() == null) return;

        // Not ours. Three filters, and sourceType is deliberately NOT one of
        // them — a guard finding is SourceType.SYSTEM and a reviewer's is
        // SourceType.COMMENT, and both are findings.
        //
        //   entityType     — a finding is raised against a question. An item on
        //                    anything else belongs to another module; this
        //                    listener sees every raised item in the product.
        //   remediationType — REMEDIATION_REQUEST is the finding. CLARIFICATION
        //                    is an internal note to a review assistant,
        //                    CONTRIBUTOR_ASSIGNMENT and REVIEWER_ASSIGNMENT are
        //                    assignment bookkeeping. None of those is a finding,
        //                    and escalating one produces an Issue nobody can act
        //                    on. AssessmentIssueEscalationService refuses them
        //                    too; this is the cheaper half of the same rule.
        //   linkedIssueId  — already escalated. The guard against a replayed or
        //                    duplicated event, re-checked below after the re-read
        //                    because this one reads a stale instance.
        if (item.getEntityType() != ActionItem.EntityType.QUESTION_RESPONSE) return;
        if (!"REMEDIATION_REQUEST".equals(item.getRemediationType()))        return;
        if (item.getLinkedIssueId() != null)                                 return;

        try {
            // Re-read: the event carries the instance from the publishing
            // transaction, and we are in a new one.
            ActionItem fresh = actionItemRepository.findById(item.getId()).orElse(null);
            if (fresh == null || fresh.getLinkedIssueId() != null) return;

            AssessmentQuestionInstance qi =
                    questionInstanceRepository.findById(fresh.getEntityId()).orElse(null);
            if (qi == null || qi.getAssessmentId() == null) {
                // A question instance from another module, or a guard item on
                // something that is not an assessment question. Nothing to do,
                // and not an error — this listener sees every raised item.
                return;
            }

            Long assessmentId = qi.getAssessmentId();
            VendorAssessment assessment = assessmentRepository.findById(assessmentId).orElse(null);
            if (assessment == null) return;

            // ── Enrich, so the finding is findable ──────────────────────────
            boolean changed = false;
            if (fresh.getParentEntityId() == null) {
                fresh.setParentEntityType(ActionItem.EntityType.ASSESSMENT);
                fresh.setParentEntityId(assessmentId);
                changed = true;
            }
            if (fresh.getVendorId() == null && assessment.getVendorId() != null) {
                fresh.setVendorId(assessment.getVendorId());
                changed = true;
            }
            // Same reason as the parent above: GuardEvaluator cannot know which
            // screen opens an assessment question, and a guard rule should not
            // have to. The assessment module does, so it stamps them here and
            // the shared inbox resolver treats a guard finding exactly like a
            // reviewer's remediation — vendor fixes it, organisation validates.
            if (fresh.getNavKey() == null) {
                fresh.setNavKey("vendor_assessment_fill");
                fresh.setAssignerNavKey("org_assessment_review");
                changed = true;
            }
            if (changed) actionItemRepository.save(fresh);

            // ── Escalate ────────────────────────────────────────────────────
            //
            // No owner means no escalation, and that is the right outcome
            // rather than a failure. A guard rule that assigns by GROUP ROLE
            // rather than to a named user produces an item with assignedTo
            // null, and escalate() refuses it because an Issue with no owner
            // sits in OPEN with nobody told. It stays in the Findings tab and
            // a reviewer escalates it once they have picked someone — which is
            // exactly the manual path, reached honestly.
            if (fresh.getAssignedTo() == null && fresh.getResolutionReservedFor() == null) {
                log.info("[FINDING-ESCALATE] Skipped, no owner | itemId={} assessmentId={} source={} "
                                + "— nobody is named on it, so a person must pick an owner and escalate "
                                + "from the Findings tab",
                        fresh.getId(), assessmentId, fresh.getSourceType());
                return;
            }

            var result = escalationService.escalate(
                    assessmentId, fresh.getId(), null, escalatingUserId(fresh), event.tenantId());

            log.info("[FINDING-ESCALATE] itemId={} assessmentId={} source={} → issue {} ({})",
                    fresh.getId(), assessmentId, fresh.getSourceType(),
                    result.get("issueId"), result.get("issueRef"));

        } catch (Exception e) {
            // Never take the raise down with us. The finding is already saved
            // and visible; a failed escalation leaves it for manual escalation,
            // which is a working fallback rather than a lost finding.
            log.warn("[FINDING-ESCALATE] Failed for itemId={} — the finding stands and can be "
                    + "escalated by hand from the Findings tab: {}", item.getId(), e.getMessage());
        }
    }
}