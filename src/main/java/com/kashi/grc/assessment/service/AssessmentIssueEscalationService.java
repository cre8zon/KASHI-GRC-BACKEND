package com.kashi.grc.assessment.service;

import com.kashi.grc.actionitem.domain.ActionItem;
import com.kashi.grc.actionitem.repository.ActionItemRepository;
import com.kashi.grc.assessment.domain.VendorAssessment;
import com.kashi.grc.assessment.repository.VendorAssessmentRepository;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.issue.domain.Issue;
import com.kashi.grc.issue.dto.IssueRequest;
import com.kashi.grc.issue.dto.IssueResponse;
import com.kashi.grc.issue.service.IssueService;
import com.kashi.grc.workflow.domain.Workflow;
import com.kashi.grc.workflow.repository.WorkflowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A vendor-assessment remediation becomes an Issue, the way an audit finding does.
 *
 * ── WHAT THE AUDIT SIDE DOES, AND WHAT WE MIRROR ──────────────────────────
 * AuditFindingController.escalateToIssue (line 383) is the model:
 *
 *   • refuse if already escalated, naming the issue           (:401-404)
 *   • copy title / description / frameworkRef                 (:406-420)
 *   • map severity through one mapping function               (:495-503)
 *   • stamp sourceModule / sourceEntityType / sourceEntityId  (:412-415)
 *   • resolve an owner, with a documented fallback chain      (:424-436)
 *   • write the back-link onto the source row                 (:452-453)
 *
 * Every one of those is reproduced below. Two things are NOT copied, and both
 * are deliberate.
 *
 * ── 1. THE SEVERITY MAPPING IS REAL, NOT HARDCODED MEDIUM ─────────────────
 * Audit has three escalation paths and they disagree: the manual one maps
 * severity faithfully, while both automatic ones hardcode
 *
 *     req.setSeverity(Issue.Severity.MEDIUM);
 *
 * (AuditInstanceController:830, AuditTestPolicySnapshotService:645). The same
 * two also write IssueType.INTERNAL where the manual path writes EXTERNAL, so
 * identical findings produce differently-typed issues depending on how they
 * were raised. We map, always, from the remediation's own severity — which the
 * reviewer chose in the remediation modal and is the only honest input.
 *
 * ── 2. THE WORKFLOW IS RESOLVED, NOT ASSUMED ──────────────────────────────
 * Audit's three copies of findingWorkflowId() all end `.orElse(15L)` — a magic
 * id that is correct only by luck on any database that was not seeded in the
 * same order. More importantly:
 *
 *     IssueService.startWorkflowIfConfigured, line 760
 *     Long workflowId = overrideWorkflowId;
 *     if (workflowId == null) { log.warn(...); return; }
 *
 * The comment above that line says it looks up a default per issueType. It does
 * not. There is no per-issueType lookup anywhere in the codebase, so an
 * EXTERNAL issue and an INTERNAL one run the identical blueprint today.
 *
 * Until that is fixed centrally, this service resolves the workflow itself and
 * passes it explicitly, so an escalated remediation cannot silently land
 * without a workflow the way an audit escalation can. resolveWorkflowId is
 * ordered, logged, and has no magic number: if nothing matches it refuses
 * rather than creating an inert issue.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssessmentIssueEscalationService {

    private final IssueService                 issueService;
    private final ActionItemRepository         actionItemRepository;
    private final VendorAssessmentRepository   assessmentRepository;
    private final WorkflowRepository           workflowRepository;

    /** What we stamp on the issue so the closure cascade can find its way back. */
    public static final String SOURCE_MODULE      = "VENDOR_ASSESSMENT";
    public static final String SOURCE_ENTITY_TYPE = "ASSESSMENT_REMEDIATION";

    /**
     * Workflow names tried in order, most specific first.
     *
     * Names rather than ids because ids differ per environment, and the audit
     * code's `.orElse(15L)` is exactly the bug that causes.
     *
     * The order is decided by what the database actually holds:
     *
     *   "Vendor Remediation Lifecycle"  — seed 66. The vendor owner fixes and
     *                                     evidences, their CISO reviews, the
     *                                     organisation validates and closes.
     *   "External Issue Remediation"    — workflow 17. The right shape, the
     *                                     wrong cast: its step 5 "External
     *                                     Validation" is AUDITOR-sided with
     *                                     LEAD_AUDITOR as the only actor, and a
     *                                     TPRM remediation has no auditor, so an
     *                                     issue reaching step 5 stops there with
     *                                     a task nobody can see. It is also
     *                                     is_active = 0 today, so isActive()
     *                                     already skips it. Second in the list
     *                                     for the case where you activate it and
     *                                     staff an auditor deliberately.
     *   "Issue Remediation Lifecycle"   — workflow 15, the org-internal default.
     *                                     Last resort: it runs to completion, but
     *                                     every step is ORGANIZATION-sided, so
     *                                     the vendor never gets a task and the
     *                                     org ends up remediating its own
     *                                     vendor's finding.
     *
     * If none is active, this refuses rather than guessing — see resolveWorkflowId.
     */
    private static final String[] EXTERNAL_WORKFLOW_NAMES = {
            "Vendor Remediation Lifecycle",
            "External Issue Remediation",
            "Issue Remediation Lifecycle",
    };

    @Transactional
    public Map<String, Object> escalate(Long assessmentId, Long actionItemId,
                                        Long explicitWorkflowId,
                                        Long userId, Long tenantId) {

        ActionItem item = actionItemRepository.findById(actionItemId)
                .orElseThrow(() -> new ResourceNotFoundException("ActionItem", actionItemId));

        // Tenant first, before anything is read off the row.
        if (!tenantId.equals(item.getTenantId())) {
            throw new BusinessException("ACCESS_DENIED", "Not your tenant.", HttpStatus.FORBIDDEN);
        }

        // Only a remediation request escalates. A CLARIFICATION is an internal
        // note to a review assistant and a CONTRIBUTOR_ASSIGNMENT is assignment
        // bookkeeping — neither is a finding, and both would produce an issue
        // nobody can act on.
        if (!"REMEDIATION_REQUEST".equals(item.getRemediationType())) {
            throw new BusinessException("INVALID_OPERATION",
                    "Only a remediation request can be escalated to an issue. This item is "
                            + (item.getRemediationType() != null ? item.getRemediationType() : "an assignment")
                            + ".", HttpStatus.BAD_REQUEST);
        }

        // Idempotence, named. Audit's version says "This finding is already
        // linked to Issue #N" and that wording is worth keeping: the reviewer
        // wants the number, not a refusal.
        if (item.getLinkedIssueId() != null) {
            throw new BusinessException("ALREADY_ESCALATED",
                    "This remediation is already linked to Issue #" + item.getLinkedIssueId() + ".",
                    HttpStatus.CONFLICT);
        }

        VendorAssessment assessment = assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("VendorAssessment", assessmentId));
        if (!tenantId.equals(assessment.getTenantId())) {
            throw new BusinessException("ACCESS_DENIED", "Not your tenant.", HttpStatus.FORBIDDEN);
        }

        // The owner is the person who must fix it. On an assessment remediation
        // that is the item's assignee — the vendor user the reviewer raised it
        // against. Falling back to the raiser would put the issue back on the
        // reviewer's desk, which reads as done when it is not.
        Long ownerId = item.getAssignedTo() != null ? item.getAssignedTo() : item.getResolutionReservedFor();
        if (ownerId == null) {
            throw new BusinessException("NO_OWNER",
                    "This remediation has no assignee, so the issue would have no owner. "
                            + "Assign it first, or escalate from a remediation raised against a named user.",
                    HttpStatus.BAD_REQUEST);
        }

        Long workflowId = explicitWorkflowId != null
                ? explicitWorkflowId
                : resolveWorkflowId(tenantId);

        IssueRequest req = new IssueRequest();
        req.setTitle(item.getTitle());
        req.setDescription(buildDescription(item, assessment));
        req.setIssueType(Issue.IssueType.EXTERNAL);
        req.setSeverity(mapSeverity(item.getSeverity(), item.getPriority()));
        req.setSourceModule(SOURCE_MODULE);
        req.setSourceEntityType(SOURCE_ENTITY_TYPE);
        req.setSourceEntityId(item.getId());
        // templateId, not a name: VendorAssessment carries no template-name
        // snapshot, and resolving one here would mean a repository this service
        // has no other reason to hold. The issue's own source link gets you the
        // assessment, which has the name.
        req.setSourceDescription("Vendor assessment #" + assessmentId
                + ", template #" + assessment.getTemplateId());
        req.setOwnerId(ownerId);
        req.setWorkflowId(workflowId);

// vendorId is what makes the issue visible to the right vendor and
        // invisible to every other one. DbRepository.applyVendorScope adds its
        // predicate only when the entity HAS a vendorId attribute, so before
        // Issue.vendorId existed the generic scope rule added nothing at all
        // and the blanket refusal in AuditScopeService was the only thing
        // stopping a vendor from reading every issue in the tenant.
        req.setVendorId(assessment.getVendorId());

        // dueAt carries over when the reviewer set one. When they did not,
        // IssueService.computeDueAt derives it from severity (72h CRITICAL,
        // 720h HIGH, 2160h MEDIUM, 4320h LOW) — leaving it null is the correct
        // way to ask for that, not an omission.
        if (item.getDueAt() != null) req.setDueAt(item.getDueAt());

        // What the vendor was asked to produce is the remediation plan's
        // starting point. Empty is fine; a wrong guess is not.
        if (item.getExpectedEvidence() != null && !item.getExpectedEvidence().isBlank()) {
            req.setRemediationPlan("Expected evidence: " + item.getExpectedEvidence());
        }

        IssueResponse issue = issueService.create(req, userId, tenantId);

        item.setLinkedIssueId(issue.getId());
        item.setStatus(ActionItem.Status.IN_PROGRESS);
        actionItemRepository.save(item);

        log.info("[ASSESSMENT-ESCALATE] itemId={} | assessmentId={} | issueId={} | issueRef={} | workflowId={}",
                actionItemId, assessmentId, issue.getId(), issue.getIssueRef(), workflowId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("actionItemId", actionItemId);
        out.put("issueId",      issue.getId());
        out.put("issueRef",     issue.getIssueRef());
        out.put("issueType",    Issue.IssueType.EXTERNAL.name());
        out.put("severity",     req.getSeverity().name());
        out.put("ownerId",      ownerId);
        out.put("workflowId",   workflowId);
        return out;
    }

    // ══════════════════════════════════════════════════════════════════════
    // RESOLUTION
    // ══════════════════════════════════════════════════════════════════════

    /**
     * The ISSUE workflow to run, by name, most specific first.
     *
     * Refuses rather than guessing. IssueService swallows a missing workflow
     * with a log.warn and creates the issue anyway — an issue with no workflow
     * instance sits in OPEN forever and nobody is told. That failure mode is
     * invisible in the UI, so it is worth a 4xx here instead.
     */
    private Long resolveWorkflowId(Long tenantId) {
        for (String name : EXTERNAL_WORKFLOW_NAMES) {
            Optional<Workflow> hit = workflowRepository.findAll().stream()
                    .filter(Workflow::isActive)
                    .filter(w -> "ISSUE".equalsIgnoreCase(w.getEntityType()))
                    .filter(w -> w.getTenantId() == null || w.getTenantId().equals(tenantId))
                    .filter(w -> name.equalsIgnoreCase(w.getName()))
                    // A tenant's own blueprint beats the platform one of the
                    // same name; higher version beats lower.
                    .max(Comparator
                            .comparing((Workflow w) -> w.getTenantId() != null)
                            .thenComparing(w -> w.getVersion() == null ? 0 : w.getVersion()));
            if (hit.isPresent()) {
                log.debug("[ASSESSMENT-ESCALATE] workflow resolved by name '{}' → id={}",
                        name, hit.get().getId());
                return hit.get().getId();
            }
        }
        throw new BusinessException("NO_ISSUE_WORKFLOW",
                "No active ISSUE workflow found. Tried, in order: "
                        + String.join(", ", EXTERNAL_WORKFLOW_NAMES)
                        + ". Seed one, or pass workflowId explicitly on the escalate call.",
                HttpStatus.PRECONDITION_FAILED);
    }

    /**
     * Remediation severity → issue severity.
     *
     * The remediation modal writes severity as a free string
     * (CRITICAL/HIGH/MEDIUM/LOW — ReviewController's request-remediation), and
     * ActionItem.severity is a String column, not an enum, so an unexpected
     * value is possible. Priority is the fallback because it IS an enum and
     * carries the same four names.
     */
    private Issue.Severity mapSeverity(String severity, ActionItem.Priority priority) {
        if (severity != null) {
            switch (severity.trim().toUpperCase()) {
                case "CRITICAL": return Issue.Severity.CRITICAL;
                case "HIGH":     return Issue.Severity.HIGH;
                case "MEDIUM":   return Issue.Severity.MEDIUM;
                case "LOW", "INFORMATIONAL": return Issue.Severity.LOW;
                default:
                    log.warn("[ASSESSMENT-ESCALATE] Unknown remediation severity '{}' — falling back to priority",
                            severity);
            }
        }
        if (priority != null) {
            switch (priority) {
                case CRITICAL: return Issue.Severity.CRITICAL;
                case HIGH:     return Issue.Severity.HIGH;
                case LOW:      return Issue.Severity.LOW;
                default:       return Issue.Severity.MEDIUM;
            }
        }
        return Issue.Severity.MEDIUM;
    }

    /**
     * The issue body. The reviewer's own words first, then where it came from —
     * because an issue is read weeks later by someone who was not in the review.
     */
    private String buildDescription(ActionItem item, VendorAssessment assessment) {
        StringBuilder sb = new StringBuilder();
        if (item.getDescription() != null && !item.getDescription().isBlank()) {
            sb.append(item.getDescription().trim()).append("\n\n");
        }
        sb.append("Raised from vendor assessment #").append(assessment.getId())
                .append(" (template #").append(assessment.getTemplateId()).append(")")
                .append(", remediation item #").append(item.getId()).append(".");
        if (item.getEntityId() != null
                && item.getEntityType() == ActionItem.EntityType.QUESTION_RESPONSE) {
            sb.append(" Question instance #").append(item.getEntityId()).append(".");
        }
        if (item.getExpectedEvidence() != null && !item.getExpectedEvidence().isBlank()) {
            sb.append("\n\nExpected evidence: ").append(item.getExpectedEvidence().trim());
        }
        return sb.toString();
    }
}