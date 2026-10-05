package com.kashi.grc.assessment.service;

import com.kashi.grc.actionitem.domain.ActionItem;
import com.kashi.grc.actionitem.repository.ActionItemRepository;
import com.kashi.grc.assessment.domain.VendorAssessment;
import com.kashi.grc.assessment.repository.VendorAssessmentRepository;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.util.UtilityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Closing a remediation item: validated, or risk accepted.
 *
 * ── THE BUG THIS EXISTS TO REMOVE ─────────────────────────────────────────
 * validateRemediation and acceptRisk in ReviewController are structurally
 * identical, and neither checks whether the item is still open. Both:
 *
 *     item.setStatus(RESOLVED);          // unconditionally
 *     decrementAndMaybeReport(...);      // unconditionally
 *
 * and decrementAndMaybeReport does:
 *
 *     next = max(0, current - 1);
 *     if (next == 0 && current > 0) generateReport(...);
 *
 * So closing one item twice decrements the counter twice. Two items look
 * closed when one was. And if the second call is the one that lands on zero, a
 * report generates for a state that never existed — or generates twice, which
 * is the most likely source of the 409 you are seeing, since report versioning
 * is the only thing in that path that can conflict.
 *
 * It does not need a double-click. A retried request, a slow network, two
 * reviewers on the same item, or the UI not having updated after the first
 * success all reproduce it.
 *
 * ── THE FIX IS IDEMPOTENCE, NOT A LOCK ────────────────────────────────────
 * Closing an already-closed item returns what the first call returned, and
 * changes nothing. Same property as the exception expiry sweep and the metric
 * capture: running it twice produces what running it once did. That removes
 * the whole class of bug rather than the one path that was reported — and it
 * means the client no longer has to be careful, which is the only kind of
 * guarantee worth having.
 *
 * ── AND WHEN IT MUST REFUSE ENTIRELY ──────────────────────────────────────
 * A remediation finding now escalates to an Issue when it is raised, and the
 * Issue carries the lifecycle: the vendor fixes and evidences, the organisation
 * validates, CloseIssueAction resolves the finding and re-derives the
 * assessment's scores. Two closure paths for one finding, and this one knows
 * nothing about the other.
 *
 * Left alone, closing from here resolves the finding and leaves the Issue open
 * — a live workflow instance and a task still sitting on the vendor for work
 * that has just been accepted, with nothing in the product saying so. While
 * escalation was a manual step that was rare. Now it would be the normal case.
 *
 * So close() refuses an item carrying linkedIssueId and names the issue. Not a
 * cascade: closing an Issue properly means its workflow running to completion,
 * and short-circuiting that would strand instances and tasks, which is worse
 * than the bug. The one place a finding is closed is the one place that knows
 * how.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssessmentRemediationService {

    private final ActionItemRepository       actionItemRepository;
    private final VendorAssessmentRepository assessmentRepository;
    private final AssessmentSideResolver     sideResolver;
    private final UtilityService             utilityService;
    /**
     * The one place an assessment's derived numbers are recomputed. Injected
     * rather than duplicated — see recount() below for what the duplicate was
     * getting wrong. No cycle: that service holds four repositories and no
     * services.
     */
    private final AssessmentScoreSyncService scoreSyncService;

    public static final String REMEDIATION_REQUEST = "REMEDIATION_REQUEST";

    // ═════════════════════════════════════════════════════════════════════════
    // CLOSING AN ITEM
    // ═════════════════════════════════════════════════════════════════════════

    /** The reviewer accepts the vendor's fix. */
    @Transactional
    public Map<String, Object> validate(Long assessmentId, Long actionItemId, String note) {
        return close(assessmentId, actionItemId,
                note == null || note.isBlank() ? "Remediation validated" : note,
                false);
    }

    /** The reviewer closes it without a fix, carrying the risk. */
    @Transactional
    public Map<String, Object> acceptRisk(Long assessmentId, Long actionItemId, String note) {
        String reason = note == null ? "" : note.trim();
        if (reason.isEmpty()) {
            // Unlike validation, accepting risk needs a reason: it is a
            // decision to leave a known gap open, and it is the row an auditor
            // samples. "Risk accepted by reviewer" as a default explains
            // nothing to the person who reads it in nine months.
            throw new BusinessException("INVALID_OPERATION",
                    "Accepting the risk needs a reason — it records a decision to leave a "
                            + "known gap open, and it is what an auditor will ask about.");
        }
        return close(assessmentId, actionItemId, reason, true);
    }

    /**
     * The one path both routes take.
     *
     * Two calls with the same arguments produce the same end state, and only
     * the first one moves the counter.
     */
    private Map<String, Object> close(Long assessmentId, Long actionItemId,
                                      String note, boolean riskAccepted) {
        Long userId   = utilityService.getLoggedInDataContext().getId();
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();

        ActionItem item = actionItemRepository.findById(actionItemId)
                .orElseThrow(() -> new ResourceNotFoundException("ActionItem", actionItemId));

        if (!tenantId.equals(item.getTenantId())) {
            throw new BusinessException("ACCESS_DENIED", "Not your tenant.", HttpStatus.FORBIDDEN);
        }
        if (!REMEDIATION_REQUEST.equals(item.getRemediationType())) {
            throw new BusinessException("INVALID_OPERATION",
                    "This action applies only to remediation requests.");
        }
        // Only the assessing side closes a remediation. A vendor marking their
        // own gap as validated is the one thing this workflow exists to
        // prevent, and it was not checked anywhere.
        if (sideResolver.isVendor()) {
            throw new BusinessException("ACCESS_DENIED",
                    "Only the assessing organisation can close a remediation item.",
                    HttpStatus.FORBIDDEN);
        }

        // ── THE ISSUE OWNS THE CLOSURE ───────────────────────────────────
        //
        // See the class javadoc. Resolving the finding here would orphan the
        // Issue: its workflow keeps running, its task stays on the vendor, and
        // the assessment's count and report move as if the work were done.
        //
        // 409 rather than 403: nothing is wrong with the caller or their
        // rights, the request is just aimed at the wrong record. The message
        // carries the issue id because the next thing they want is to go
        // there, and the UI hides these buttons on an escalated finding for
        // the same reason — this is the guard that makes that a mirror rather
        // than a hope.
        if (item.getLinkedIssueId() != null) {
            throw new BusinessException("ALREADY_ESCALATED",
                    "This finding is tracked as Issue #" + item.getLinkedIssueId()
                            + ". Close it there — validating here would leave the issue open "
                            + "with its remediation workflow still running. It resolves "
                            + "automatically when the issue closes.",
                    HttpStatus.CONFLICT);
        }

        VendorAssessment assessment = assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("VendorAssessment", assessmentId));

        // ── THE GUARD ────────────────────────────────────────────────────
        // Already closed: report the existing state and touch nothing. Not an
        // error — the caller asked for an outcome that already holds, and
        // throwing here would make an honest retry look like a failure.
        if (item.getStatus() == ActionItem.Status.RESOLVED) {
            log.info("[REMEDIATION] Already closed, no-op | item={} | assessment={} | by={}",
                    actionItemId, assessmentId, userId);
            return result(item, assessment, false, true);
        }

        item.setStatus(ActionItem.Status.RESOLVED);
        item.setResolvedAt(LocalDateTime.now());
        item.setResolvedBy(userId);
        item.setResolutionNote(riskAccepted ? "RISK_ACCEPTED: " + note : note);
        if (riskAccepted) {
            item.setAcceptedRisk(true);
            item.setAcceptedRiskBy(userId);
            item.setAcceptedRiskAt(LocalDateTime.now());
            item.setAcceptedRiskNote(note);
        }
        actionItemRepository.save(item);

        boolean lastOne = decrementOnce(assessment);

        log.info("[REMEDIATION] Closed | item={} | assessment={} | riskAccepted={} | remaining={} | by={}",
                actionItemId, assessmentId, riskAccepted,
                assessment.getOpenRemediationCount(), userId);

        return result(item, assessment, lastOne, false);
    }

    /**
     * Decrements the open count, and says whether that was the last one.
     *
     * ── DELIBERATELY DOES NOT GENERATE THE REPORT ─────────────────────────
     * The old helper both decremented and fired report generation. That
     * coupling is why closing an item twice could generate two reports: the
     * side effect rode along with the arithmetic.
     *
     * Here the arithmetic returns a fact — "that was the last one" — and the
     * caller decides what to do about it. Report generation moves up a level,
     * where it can be made idempotent on its own terms rather than inheriting
     * whatever the counter happened to do.
     */
    private boolean decrementOnce(VendorAssessment assessment) {
        int current = assessment.getOpenRemediationCount() == null
                ? 0 : assessment.getOpenRemediationCount();
        int next = Math.max(0, current - 1);
        assessment.setOpenRemediationCount(next);
        assessmentRepository.save(assessment);
        return next == 0 && current > 0;
    }

    /**
     * Recomputes the open count from the items themselves.
     *
     * openRemediationCount is a cached number, and a cached number that only
     * ever gets decremented drifts. It is already reported in the response DTO
     * and printed into the generated report, so it being wrong is visible to
     * customers.
     *
     * Call this after any bulk change, and run it once over existing data —
     * the double-decrement bug means some assessments are almost certainly
     * carrying a count lower than the truth.
     *
     * ── NOW A DELEGATE, AND WHAT IT USED TO DO WRONG ──────────────────────
     * This method used to count here, and it counted the wrong thing. It
     * loaded every action item in the database, filtered to the tenant and to
     * REMEDIATION_REQUEST, and then asked belongsTo(item, assessmentId) —
     * which returned `true` unconditionally, with a comment saying so and
     * calling itself a seam. So it wrote the tenant's TOTAL open remediation
     * count onto whichever assessment you called it for. On a tenant with two
     * vendors it was wrong the first time it ran.
     *
     * AssessmentScoreSyncService does it properly: it scopes by
     * parentEntityType/parentEntityId, resolves the rows that predate that
     * field through the assessment's own question instances, counts with a
     * Specification instead of findAll(), and re-derives totalEarnedScore and
     * totalPossibleScore at the same time — which this never touched, so a
     * recount used to leave the score stale while fixing the count.
     *
     * One counting rule, one place. The tenant still comes from the request
     * context here because this method is only reachable from one, and the
     * service takes it as a parameter precisely so the workflow-automation
     * caller can supply its own.
     */
    @Transactional
    public int recount(Long assessmentId) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();

        // Kept so the behaviour on a bad id is unchanged — a 404 rather than
        // the service's quiet -1, because a person asked for this one.
        assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("VendorAssessment", assessmentId));

        int open = scoreSyncService.syncAssessmentScore(assessmentId, tenantId);
        log.info("[REMEDIATION] Recount via score sync | assessment={} | open={}", assessmentId, open);
        return open;
    }

    private Map<String, Object> result(ActionItem item, VendorAssessment assessment,
                                       boolean wasLast, boolean noOp) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("actionItemId",     item.getId());
        out.put("status",           item.getStatus());
        out.put("acceptedRisk",     Boolean.TRUE.equals(item.getAcceptedRisk()));
        out.put("openRemediations", assessment.getOpenRemediationCount() == null
                ? 0 : assessment.getOpenRemediationCount());
        // Says the item was already closed, rather than pretending this call
        // did the work. A client that retried deserves to know which.
        out.put("alreadyClosed",    noOp);
        // The caller decides whether to generate a report. See decrementOnce.
        out.put("allRemediationsClosed", wasLast);
        return out;
    }
}