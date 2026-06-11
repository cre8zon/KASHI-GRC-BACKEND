package com.kashi.grc.issue.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ForbiddenException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.service.EmailSenderService;
import com.kashi.grc.issue.domain.Issue;
import com.kashi.grc.issue.dto.IssueIngestRequest;
import com.kashi.grc.issue.dto.IssueRequest;
import com.kashi.grc.issue.dto.IssueResponse;
import com.kashi.grc.issue.repository.IssueRepository;
import com.kashi.grc.notification.service.NotificationService;
import com.kashi.grc.usermanagement.domain.User;
import com.kashi.grc.usermanagement.repository.UserRepository;
import com.kashi.grc.workflow.dto.request.StartWorkflowRequest;
import com.kashi.grc.workflow.dto.response.WorkflowInstanceResponse;
import com.kashi.grc.workflow.repository.WorkflowInstanceRepository;
import com.kashi.grc.workflow.repository.WorkflowRepository;
import com.kashi.grc.workflow.service.WorkflowEngineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * IssueService — core business logic for enterprise issue management.
 *
 * Responsibilities:
 *   1. Create / update / close issues (INTERNAL and EXTERNAL types)
 *   2. Automated ingestion from external tools (AUTOMATED type)
 *   3. SLA enforcement — hourly scheduler marks breaches and escalates
 *   4. Workflow auto-start on issue creation
 *   5. Notification dispatch to owners and managers
 *
 * SLA MATRIX (from GRC Enterprise Reference):
 *   CRITICAL → acknowledge 4h,  resolve 72h,  escalate to: CISO/CRO → CEO → Board
 *   HIGH     → acknowledge 24h, resolve 30d,  escalate to: CCO/CISO → CRO
 *   MEDIUM   → acknowledge 72h, resolve 90d,  escalate to: GRC Manager → CCO at 60d
 *   LOW      → acknowledge 5d,  resolve 180d, escalate to: GRC Analyst monthly report
 *
 * INGESTION DEDUPLICATION:
 *   Re-posting the same (source, externalId) updates severity/status if changed.
 *   No duplicate created. Idempotent by design.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IssueService {

    private final IssueRepository                                    issueRepository;
    private final WorkflowEngineService                              workflowEngineService;
    private final WorkflowRepository                                 workflowRepository;
    private final WorkflowInstanceRepository                         instanceRepository;
    private final com.kashi.grc.workflow.repository.StepInstanceRepository  stepInstanceRepository;
    private final com.kashi.grc.workflow.repository.TaskInstanceRepository  taskInstanceRepository;
    private final NotificationService                                notificationService;
    private final EmailSenderService                                 emailSenderService;
    private final UserRepository                                     userRepository;
    private final ObjectMapper             objectMapper;

    /**
     * System user ID used when the scheduler creates notifications or escalations
     * with no human actor. Configurable per-deployment.
     */
    @Value("${app.system.userId:1}")
    private Long systemUserId;

    // ─────────────────────────────────────────────────────────────────────────
    // SLA matrix — resolve deadlines in hours
    // ─────────────────────────────────────────────────────────────────────────

    private static final Map<Issue.Severity, Long> RESOLVE_SLA_HOURS = Map.of(
            Issue.Severity.CRITICAL, 72L,
            Issue.Severity.HIGH,     720L,  // 30 days
            Issue.Severity.MEDIUM,   2160L, // 90 days
            Issue.Severity.LOW,      4320L  // 180 days
    );

    private static final Map<Issue.Severity, Long> ACK_SLA_HOURS = Map.of(
            Issue.Severity.CRITICAL, 4L,
            Issue.Severity.HIGH,     24L,
            Issue.Severity.MEDIUM,   72L,
            Issue.Severity.LOW,      120L   // 5 business days ≈ 120h
    );

    // ─────────────────────────────────────────────────────────────────────────
    // CREATE — manual issue (INTERNAL / EXTERNAL)
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public IssueResponse create(IssueRequest req, Long createdBy, Long tenantId) {
        String ref = buildIssueRef(tenantId);

        Issue issue = Issue.builder()
                .tenantId(tenantId)
                .issueRef(ref)
                .title(req.getTitle())
                .description(req.getDescription())
                .issueType(req.getIssueType())
                .severity(req.getSeverity())
                .status(Issue.Status.OPEN)
                .category(req.getCategory())
                .sourceModule(req.getSourceModule())
                .sourceEntityType(req.getSourceEntityType())
                .sourceEntityId(req.getSourceEntityId())
                .sourceDescription(req.getSourceDescription())
                .ownerId(req.getOwnerId())
                .createdBy(createdBy)
                .raisedBySide("ORGANIZATION")
                .dueAt(req.getDueAt() != null ? req.getDueAt()
                        : computeDueAt(req.getSeverity()))
                .frameworkRef(req.getFrameworkRef())
                .linkedControlIds(listToJson(req.getLinkedControlIds()))
                .linkedRiskIds(listToJson(req.getLinkedRiskIds()))
                .rcaJson(req.getRcaJson())
                .rootCauseCategory(req.getRootCauseCategory())
                .remediationPlan(req.getRemediationPlan())
                .remediationType(req.getRemediationType())
                .build();

        issueRepository.save(issue);
        log.info("[ISSUE] Created | ref={} | type={} | severity={} | tenantId={}",
                ref, issue.getIssueType(), issue.getSeverity(), tenantId);

        // Auto-start workflow if workflowId provided or default exists
        startWorkflowIfConfigured(issue, req.getWorkflowId(), createdBy, tenantId);

        // If Step 1 auto-completed on creation, sync issue status to TRIAGED
        // so the UI shows the correct state and the Triage button disappears.
        syncStatusAfterWorkflowStart(issue, createdBy, tenantId);

        // Notify owner
        if (req.getOwnerId() != null) {
            notificationService.send(req.getOwnerId(), "ISSUE_ASSIGNED",
                    "Issue " + ref + " has been assigned to you — " + req.getSeverity() + " severity",
                    "ISSUE", issue.getId());
        }

        return toResponse(issue, tenantId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INGEST — automated issue from external tool (AUTOMATED type)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Idempotent ingestion endpoint for external tools.
     *
     * If (tenantId, source, externalId) already exists:
     *   - Updates severity if escalated (e.g. MEDIUM → HIGH)
     *   - Updates title/description if changed
     *   - Does NOT reset status, owner, or workflow
     *   - Returns the existing issue (not a duplicate)
     *
     * If new:
     *   - Creates issue with IssueType.AUTOMATED
     *   - Maps CVSS to Severity
     *   - Starts the AUTOMATED workflow blueprint
     *   - Notifies GRC Manager pool
     */
    @Transactional
    public IssueResponse ingest(IssueIngestRequest req, Long tenantId) {
        // Deduplication check
        Optional<Issue> existing = issueRepository.findByTenantIdAndExternalSourceAndExternalId(
                tenantId, req.getSource().toUpperCase(), req.getExternalId());

        if (existing.isPresent()) {
            return updateIngestedIssue(existing.get(), req);
        }

        Issue.Severity severity = resolveSeverity(req.getCvssScore(), req.getSeverity());
        String ref = buildIssueRef(tenantId);
        String rawPayload = toJson(req.getRawPayload());

        Issue issue = Issue.builder()
                .tenantId(tenantId)
                .issueRef(ref)
                .title(req.getTitle())
                .description(req.getDescription())
                .issueType(Issue.IssueType.AUTOMATED)
                .severity(severity)
                .status(Issue.Status.OPEN)
                .category(req.getCategory() != null ? req.getCategory() : "VULNERABILITY")
                .sourceModule(req.getSource().toUpperCase())
                .externalId(req.getExternalId())
                .externalSource(req.getSource().toUpperCase())
                .externalPayload(rawPayload)
                .cvssScore(req.getCvssScore())
                .sourceDescription(req.getAffectedAsset())
                .raisedBySide("SYSTEM")
                .createdBy(systemUserId)
                .dueAt(computeDueAt(severity))
                .frameworkRef(req.getFrameworkRef())
                .build();

        issueRepository.save(issue);
        log.info("[ISSUE-INGEST] New | ref={} | source={} | externalId={} | severity={} | tenantId={}",
                ref, req.getSource(), req.getExternalId(), severity, tenantId);

        // Auto-start AUTOMATED workflow
        startWorkflowIfConfigured(issue, req.getWorkflowId(), systemUserId, tenantId);

        // Notify GRC Manager group — pool-assigned triage
        notificationService.send(systemUserId, "ISSUE_AUTOMATED_INGEST",
                "[AUTO] New " + severity + " issue from " + req.getSource() + ": " + req.getTitle(),
                "ISSUE", issue.getId());

        return toResponse(issue, tenantId);
    }

    @Transactional
    private IssueResponse updateIngestedIssue(Issue existing, IssueIngestRequest req) {
        Issue.Severity newSeverity = resolveSeverity(req.getCvssScore(), req.getSeverity());
        boolean escalated = newSeverity.ordinal() < existing.getSeverity().ordinal();

        existing.setTitle(req.getTitle());
        if (req.getDescription() != null) existing.setDescription(req.getDescription());
        if (req.getCvssScore() != null)   existing.setCvssScore(req.getCvssScore());
        if (escalated) {
            log.info("[ISSUE-INGEST] Severity escalated | ref={} | {} → {}",
                    existing.getIssueRef(), existing.getSeverity(), newSeverity);
            existing.setSeverity(newSeverity);
            // Recompute due date on escalation
            existing.setDueAt(computeDueAt(newSeverity));
            // Reset SLA breach flag so escalation scheduler re-evaluates
            existing.setSlaBreached(false);
        }

        issueRepository.save(existing);
        log.info("[ISSUE-INGEST] Updated existing | ref={} | externalId={}", existing.getIssueRef(), req.getExternalId());
        return toResponse(existing, existing.getTenantId());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET
    // ─────────────────────────────────────────────────────────────────────────

    public IssueResponse getById(Long id, Long tenantId) {
        Issue issue = issueRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Issue", id));
        if (!issue.getTenantId().equals(tenantId))
            throw new ForbiddenException("Issue does not belong to this tenant");
        return toResponse(issue, tenantId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UPDATE
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public IssueResponse update(Long id, IssueRequest req, Long tenantId) {
        Issue issue = issueRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Issue", id));
        if (!issue.getTenantId().equals(tenantId))
            throw new ForbiddenException("Issue does not belong to this tenant");

        if (req.getTitle()               != null) issue.setTitle(req.getTitle());
        if (req.getDescription()         != null) issue.setDescription(req.getDescription());
        if (req.getSeverity()            != null) issue.setSeverity(req.getSeverity());
        if (req.getCategory()            != null) issue.setCategory(req.getCategory());
        if (req.getOwnerId()             != null) issue.setOwnerId(req.getOwnerId());
        if (req.getDueAt()               != null) issue.setDueAt(req.getDueAt());
        if (req.getFrameworkRef()        != null) issue.setFrameworkRef(req.getFrameworkRef());
        if (req.getLinkedControlIds()    != null) issue.setLinkedControlIds(listToJson(req.getLinkedControlIds()));
        if (req.getLinkedRiskIds()       != null) issue.setLinkedRiskIds(listToJson(req.getLinkedRiskIds()));

        // ── RCA flat fields ──────────────────────────────────────────────────
        if (req.getRcaMethod()           != null) issue.setRcaMethod(req.getRcaMethod());
        if (req.getRootCauseCategory()   != null) issue.setRootCauseCategory(req.getRootCauseCategory());
        if (req.getImmediateCause()      != null) issue.setImmediateCause(req.getImmediateCause());
        if (req.getRootCause()           != null) issue.setRootCause(req.getRootCause());
        if (req.getContributingFactors() != null) issue.setContributingFactors(req.getContributingFactors());
        if (req.getIsSystemic()          != null) issue.setSystemic(req.getIsSystemic());
        if (req.getRcaJson()             != null) issue.setRcaJson(req.getRcaJson()); // legacy

        // ── Remediation fields ───────────────────────────────────────────────
        if (req.getRemediationPlan()     != null) issue.setRemediationPlan(req.getRemediationPlan());
        if (req.getRemediationType()     != null) issue.setRemediationType(req.getRemediationType());
        if (req.getRemediatedAt()        != null) issue.setRemediatedAt(req.getRemediatedAt());
        if (req.getValidatedAt()         != null) issue.setValidatedAt(req.getValidatedAt());
        if (req.getAcceptedRisk()        != null) issue.setAcceptedRisk(req.getAcceptedRisk());
        if (req.getAcceptedRiskNote()    != null) issue.setAcceptedRiskNote(req.getAcceptedRiskNote());
        if (req.getClosureSummary()      != null) issue.setClosureSummary(req.getClosureSummary());

        issueRepository.save(issue);
        return toResponse(issue, tenantId);
    }

    @Transactional
    public IssueResponse updateStatus(Long id, Issue.Status newStatus, Long userId, Long tenantId) {
        Issue issue = issueRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Issue", id));
        if (!issue.getTenantId().equals(tenantId))
            throw new ForbiddenException("Issue does not belong to this tenant");

        issue.setStatus(newStatus);

        // ── Per-status side-effects ───────────────────────────────────────────
        // IMPORTANT: advanceWorkflowTask() is only called when the current step
        // matches the expected step ORDER for this transition.
        // Step 1 auto-completes on issue creation → currentStep is already Step 2
        // when triage is called. In that case we skip advance to avoid double-advancing.
        if (newStatus == Issue.Status.TRIAGED) {
            if (issue.getAcknowledgedAt() == null) issue.setAcknowledgedAt(LocalDateTime.now());
            advanceWorkflowTask(issue, userId, "Triaged via issue action");
        }
        if (newStatus == Issue.Status.IN_PROGRESS) {
            advanceWorkflowTask(issue, userId, "Owner acknowledged and started remediation");
        }
        if (newStatus == Issue.Status.PENDING_REVIEW) {
            advanceWorkflowTask(issue, userId, "Submitted for review");
        }
        if (newStatus == Issue.Status.PENDING_VALIDATION) {
            advanceWorkflowTask(issue, userId, "Submitted for validation");
        }
        if (newStatus == Issue.Status.RESOLVED) {
            issue.setRemediatedAt(LocalDateTime.now());
            advanceWorkflowTask(issue, userId, "Remediation validated");
        }
        if (newStatus == Issue.Status.CLOSED || newStatus == Issue.Status.ACCEPTED_RISK) {
            issue.setClosedAt(LocalDateTime.now());
            issue.setClosedBy(userId);
            advanceWorkflowTask(issue, userId, "Issue closed");
        }


        issueRepository.save(issue);
        log.info("[ISSUE] Status updated | id={} | status={} | by={}", id, newStatus, userId);
        return toResponse(issue, tenantId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // REOPEN — starts a fresh workflow cycle
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Reopens a CLOSED or ACCEPTED_RISK issue and starts a new workflow cycle.
     *
     * Behaviour:
     *   1. Sets issue status back to OPEN.
     *   2. Clears closedAt / closedBy so SLA timer restarts cleanly.
     *   3. If the previous workflow instance is COMPLETED, starts a brand-new
     *      workflow instance on the same workflowId and attaches it.
     *      The old completed instance is preserved in history (Show History tab).
     *   4. If the previous workflow is still IN_PROGRESS (edge case: manual status
     *      change without completing workflow), leaves it as-is — no new instance.
     *
     * This matches Vanta / AuditBoard behaviour: each remediation attempt gets
     * its own workflow instance with a full audit trail.
     */
    @Transactional
    public IssueResponse reopen(Long id, Long userId, Long tenantId) {
        Issue issue = issueRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Issue", id));
        if (!issue.getTenantId().equals(tenantId))
            throw new ForbiddenException("Issue does not belong to this tenant");

        // Reset to OPEN
        issue.setStatus(Issue.Status.OPEN);
        issue.setClosedAt(null);
        issue.setClosedBy(null);
        issueRepository.save(issue);
        log.info("[ISSUE] Reopened | id={} | ref={} | by={}", id, issue.getIssueRef(), userId);

        // Start a new workflow cycle if the previous one is COMPLETED
        if (issue.getWorkflowInstanceId() != null) {
            instanceRepository.findById(issue.getWorkflowInstanceId()).ifPresent(prev -> {
                if (com.kashi.grc.workflow.enums.WorkflowStatus.COMPLETED
                        .name().equals(prev.getStatus() != null ? prev.getStatus().name() : "")) {
                    log.info("[ISSUE] Previous workflow COMPLETED — starting new cycle | issueId={} | prevInstanceId={}",
                            id, prev.getId());
                    // Re-use the same workflowId from the completed instance
                    startWorkflowIfConfigured(issue, prev.getWorkflowId(), userId, tenantId);
                    // Auto-approve step 1 on behalf of the reopener.
                    // Step 1 is ENTITY_CREATOR + autoCompleteActorOnSubmit — on creation the
                    // form submit triggers approval automatically. On reopen there is no form
                    // submit, so we approve the step 1 ACTOR task programmatically here.
                    // The reopener IS the new triage actor — clicking Reopen = intent to retriage.
                    autoApproveStep1ForReopen(issue, userId, tenantId);
                } else {
                    log.info("[ISSUE] Previous workflow still {} — no new instance started | issueId={}",
                            prev.getStatus(), id);
                }
            });
        } else {
            // No previous workflow — start fresh (e.g. manually created issue)
            startWorkflowIfConfigured(issue, null, userId, tenantId);
            autoApproveStep1ForReopen(issue, userId, tenantId);
        }

        return toResponse(issue, tenantId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SLA ESCALATION SCHEDULER
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Runs every hour. Finds issues past their dueAt deadline.
     * First breach: marks slaBreached=true, sends notification + email.
     * Subsequent: re-escalates every 24h to manager chain.
     *
     * Matches the enterprise SLA matrix from the GRC reference document.
     */
    @Scheduled(cron = "0 0 * * * *")  // every hour on the hour
    @Transactional
    public void runSlaEscalation() {
        LocalDateTime now = LocalDateTime.now();
        log.debug("[SLA-ESCALATION] Running at {}", now);

        // First-time breaches
        List<Issue> breached = issueRepository.findBreachedIssues(now);
        for (Issue issue : breached) {
            issue.setSlaBreached(true);
            issue.setEscalationCount(issue.getEscalationCount() + 1);
            issue.setLastEscalatedAt(now);
            issueRepository.save(issue);

            String message = String.format(
                    "⚠ SLA BREACH: Issue %s (%s) — %s severity — was due %s. Immediate action required.",
                    issue.getIssueRef(), issue.getTitle(), issue.getSeverity(), issue.getDueAt()
            );
            log.warn("[SLA-ESCALATION] BREACH | ref={} | severity={} | tenantId={}",
                    issue.getIssueRef(), issue.getSeverity(), issue.getTenantId());

            // Notify issue owner
            if (issue.getOwnerId() != null) {
                notificationService.send(issue.getOwnerId(), "ISSUE_SLA_BREACH", message,
                        "ISSUE", issue.getId());
                userRepository.findById(issue.getOwnerId()).ifPresent(u ->
                        emailSenderService.sendMail(
                                "⚠ SLA Breach — Issue " + issue.getIssueRef(),
                                buildEscalationEmailBody(issue, "owner"),
                                "text/html", u.getEmail()
                        )
                );
            }
        }

        // Re-escalation for already-breached issues (daily nudge)
        LocalDateTime reescCutoff = now.minusHours(24);
        List<Issue> reescalate = issueRepository.findActiveBreachedForReescalation(reescCutoff);
        for (Issue issue : reescalate) {
            issue.setEscalationCount(issue.getEscalationCount() + 1);
            issue.setLastEscalatedAt(now);
            issueRepository.save(issue);

            String message = String.format(
                    "🔴 ESCALATION #%d: Issue %s still unresolved. %s severity — %d days overdue.",
                    issue.getEscalationCount(), issue.getIssueRef(), issue.getSeverity(),
                    Math.abs(ChronoUnit.DAYS.between(now, issue.getDueAt()))
            );

            if (issue.getOwnerId() != null) {
                notificationService.send(issue.getOwnerId(), "ISSUE_ESCALATION", message,
                        "ISSUE", issue.getId());
            }

            log.info("[SLA-ESCALATION] Re-escalated | ref={} | escalationCount={}",
                    issue.getIssueRef(), issue.getEscalationCount());
        }

        if (!breached.isEmpty() || !reescalate.isEmpty()) {
            log.info("[SLA-ESCALATION] Done | newBreaches={} | reescalated={}",
                    breached.size(), reescalate.size());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Dashboard stats
    // ─────────────────────────────────────────────────────────────────────────

    public Map<String, Object> getStats(Long tenantId) {
        Map<String, Object> stats = new LinkedHashMap<>();

        // Status counts
        Map<String, Long> byStatus = new LinkedHashMap<>();
        issueRepository.countByStatusForTenant(tenantId)
                .forEach(r -> byStatus.put(r[0].toString(), (Long) r[1]));
        stats.put("byStatus", byStatus);

        // Open by severity
        Map<String, Long> bySeverity = new LinkedHashMap<>();
        issueRepository.countOpenBySeverityForTenant(tenantId)
                .forEach(r -> bySeverity.put(r[0].toString(), (Long) r[1]));
        stats.put("openBySeverity", bySeverity);

        // SLA breach count
        stats.put("slaBreachedCount", issueRepository.countSlaBreachedForTenant(tenantId));

        return stats;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * After reopen creates a new workflow cycle, auto-approve the step 1 ACTOR task
     * for the reopener. This mirrors what happens at creation time when the frontend
     * calls task APPROVE after form submit (autoCompleteActorOnSubmit).
     *
     * Step 1 is "Raise & Triage Issue" (ENTITY_CREATOR). On creation, the form submit
     * triggers auto-approval. On reopen there is no form — clicking Reopen IS the triage
     * intent, so we approve immediately on their behalf.
     */
    private void autoApproveStep1ForReopen(Issue issue, Long userId, Long tenantId) {
        if (issue.getWorkflowInstanceId() == null) return;
        try {
            Thread.sleep(300); // let new instance fully persist
        } catch (InterruptedException ignored) {}
        try {
            // Find step 1 step instance for the new workflow instance
            stepInstanceRepository
                    .findByWorkflowInstanceId(issue.getWorkflowInstanceId())
                    .stream()
                    .filter(si -> si.getSnapStepOrder() != null && si.getSnapStepOrder() == 1)
                    .findFirst()
                    .ifPresent(si -> {
                        // Find PENDING ACTOR task assigned to this user
                        taskInstanceRepository.findByStepInstanceId(si.getId())
                                .stream()
                                .filter(t -> com.kashi.grc.workflow.enums.TaskStatus.PENDING
                                        .equals(t.getStatus())
                                        && userId.equals(t.getAssignedUserId()))
                                .findFirst()
                                .ifPresent(task -> {
                                    try {
                                        com.kashi.grc.workflow.dto.request.TaskActionRequest req =
                                                new com.kashi.grc.workflow.dto.request.TaskActionRequest();
                                        req.setTaskInstanceId(task.getId());
                                        req.setActionType(com.kashi.grc.workflow.enums.ActionType.APPROVE);
                                        req.setRemarks("Auto-approved on reopen — triage intent confirmed");
                                        workflowEngineService.performAction(req, userId);
                                        log.info("[ISSUE] Step 1 auto-approved on reopen | issueId={} | taskId={} | userId={}",
                                                issue.getId(), task.getId(), userId);
                                    } catch (Exception e) {
                                        log.warn("[ISSUE] Step 1 auto-approve failed | issueId={} | error={}",
                                                issue.getId(), e.getMessage());
                                    }
                                });
                    });
        } catch (Exception e) {
            log.warn("[ISSUE] autoApproveStep1ForReopen error | issueId={} | error={}",
                    issue.getId(), e.getMessage());
        }
    }

    /**
     * Approves the current pending workflow task for this issue.
     * Called by every status-transition action (triage, start-remediation,
     * submit-for-review, etc.) so the workflow advances in sync with the
     * issue status change.
     */
    /**
     * Advances the workflow only if the current step matches the expected stepOrder.
     * Prevents double-advancing when Step 1 auto-completes on creation and triage
     * is then called — at that point currentStep is already Step 2, not Step 1.
     */
    /**
     * After workflow start, if Step 1 auto-completed (currentStep is now Step 2),
     * sync issue status to TRIAGED so the UI reflects the correct state.
     */
    private void syncStatusAfterWorkflowStart(Issue issue, Long userId, Long tenantId) {
        if (issue.getWorkflowInstanceId() == null) return;
        try {
            com.kashi.grc.workflow.domain.WorkflowInstance wfInst =
                    instanceRepository.findById(issue.getWorkflowInstanceId()).orElse(null);
            if (wfInst == null || wfInst.getCurrentStepId() == null) return;
            com.kashi.grc.workflow.domain.StepInstance currentSI =
                    stepInstanceRepository.findById(wfInst.getCurrentStepId()).orElse(null);
            if (currentSI == null) return;
            int currentOrder = currentSI.getSnapStepOrder() != null ? currentSI.getSnapStepOrder() : 1;
            // If already past Step 1, status should be TRIAGED
            if (currentOrder > 1 && issue.getStatus() == Issue.Status.OPEN) {
                issue.setStatus(Issue.Status.TRIAGED);
                if (issue.getAcknowledgedAt() == null)
                    issue.setAcknowledgedAt(LocalDateTime.now());
                issueRepository.save(issue);
                log.info("[ISSUE] Status auto-synced to TRIAGED after Step 1 auto-complete | issueId={}", issue.getId());
            }
        } catch (Exception e) {
            log.warn("[ISSUE] syncStatusAfterWorkflowStart error | issueId={} | {}", issue.getId(), e.getMessage());
        }
    }

    /**
     * Advances the current workflow step by approving the first PENDING task.
     * Does NOT check step order — advances whatever step is currently active.
     * The status transition (TRIAGED, IN_PROGRESS, etc.) already guarantees
     * we are on the right step — no hardcoded step numbers needed.
     * Guard: skips silently if no workflow is running or no PENDING task exists.
     */
    private void advanceWorkflowTask(Issue issue, Long userId, String remarks) {
        log.info("[ISSUE-WF] advanceWorkflowTask | issueId={} | wfInstanceId={} | remarks={}",
                issue.getId(), issue.getWorkflowInstanceId(), remarks);
        if (issue.getWorkflowInstanceId() == null) {
            log.warn("[ISSUE-WF] workflowInstanceId is NULL — workflow was never started for issueId={}", issue.getId());
            return;
        }
        try {
            com.kashi.grc.workflow.domain.WorkflowInstance wfInst =
                    instanceRepository.findById(issue.getWorkflowInstanceId()).orElse(null);
            if (wfInst == null) {
                log.warn("[ISSUE-WF] WorkflowInstance not found | id={}", issue.getWorkflowInstanceId());
                return;
            }
            if (wfInst.getCurrentStepId() == null) {
                log.warn("[ISSUE-WF] currentStepId is NULL | instanceId={} | status={}",
                        wfInst.getId(), wfInst.getStatus());
                return;
            }
            log.info("[ISSUE-WF] currentStepId={} | wfStatus={}", wfInst.getCurrentStepId(), wfInst.getStatus());

            java.util.List<com.kashi.grc.workflow.domain.TaskInstance> allTasks =
                    taskInstanceRepository.findByStepInstanceId(wfInst.getCurrentStepId());
            log.info("[ISSUE-WF] Tasks on currentStep={} | count={} | statuses={}",
                    wfInst.getCurrentStepId(), allTasks.size(),
                    allTasks.stream().map(t -> t.getId() + ":" + t.getStatus() + ":" + t.getTaskRole())
                            .collect(java.util.stream.Collectors.joining(", ")));

            java.util.Optional<com.kashi.grc.workflow.domain.TaskInstance> pendingTask =
                    allTasks.stream()
                            .filter(t -> t.getStatus() == com.kashi.grc.workflow.enums.TaskStatus.PENDING)
                            .findFirst();

            if (pendingTask.isEmpty()) {
                log.warn("[ISSUE-WF] No PENDING task found on currentStep={} — cannot advance",
                        wfInst.getCurrentStepId());
                return;
            }

            com.kashi.grc.workflow.domain.TaskInstance task = pendingTask.get();
            log.info("[ISSUE-WF] Approving taskId={} | assignedTo={}", task.getId(), task.getAssignedUserId());
            com.kashi.grc.workflow.dto.request.TaskActionRequest req =
                    new com.kashi.grc.workflow.dto.request.TaskActionRequest();
            req.setTaskInstanceId(task.getId());
            req.setActionType(com.kashi.grc.workflow.enums.ActionType.APPROVE);
            req.setRemarks(remarks);
            try {
                workflowEngineService.performAction(req, userId);
                log.info("[ISSUE-WF] performAction succeeded | taskId={}", task.getId());
            } catch (Exception e) {
                log.warn("[ISSUE-WF] performAction failed | issueId={} | taskId={} | {}",
                        issue.getId(), task.getId(), e.getMessage(), e);
            }
        } catch (Exception e) {
            log.warn("[ISSUE-WF] advanceWorkflowTask error | issueId={} | {}", issue.getId(), e.getMessage(), e);
        }
    }

    private void startWorkflowIfConfigured(Issue issue, Long overrideWorkflowId,
                                           Long initiatedBy, Long tenantId) {
        // Use override if provided; otherwise look up default for issueType
        Long workflowId = overrideWorkflowId;
        if (workflowId == null) {
            // No workflowId provided — this should not happen in normal flow since
            // the issue create form has a workflowId LOOKUP field and escalateToIssue
            // sets it explicitly. Log a warning and skip workflow start.
            log.warn("[ISSUE] workflowId is null for issueId={} issueType={} — workflow not started. "
                            + "Ensure workflowId is set on IssueRequest (create form hidden field or API caller).",
                    issue.getId(), issue.getIssueType());
        }

        if (workflowId == null) {
            log.warn("[ISSUE] No workflow configured for issueType={} — skipping auto-start",
                    issue.getIssueType());
            return;
        }

        try {
            StartWorkflowRequest wfReq = new StartWorkflowRequest();
            wfReq.setWorkflowId(workflowId);
            wfReq.setEntityType("ISSUE");
            wfReq.setEntityId(issue.getId());
            wfReq.setPriority(issue.getSeverity().name());
            wfReq.setDueDate(issue.getDueAt());

            WorkflowInstanceResponse wfInstance = workflowEngineService
                    .startWorkflow(wfReq, tenantId, initiatedBy);

            issue.setWorkflowInstanceId(wfInstance.getId());
            issueRepository.save(issue);

            log.info("[ISSUE] Workflow started | ref={} | instanceId={}",
                    issue.getIssueRef(), wfInstance.getId());
        } catch (Exception e) {
            // Workflow start failure must not roll back issue creation
            log.error("[ISSUE] Workflow start failed | ref={} | error={}",
                    issue.getIssueRef(), e.getMessage(), e);
        }
    }

    private Issue.Severity resolveSeverity(Double cvssScore, String severityStr) {
        if (cvssScore != null) {
            if (cvssScore >= 9.0) return Issue.Severity.CRITICAL;
            if (cvssScore >= 7.0) return Issue.Severity.HIGH;
            if (cvssScore >= 4.0) return Issue.Severity.MEDIUM;
            return Issue.Severity.LOW;
        }
        if (severityStr != null) {
            try { return Issue.Severity.valueOf(severityStr.toUpperCase()); }
            catch (IllegalArgumentException ignored) {}
        }
        return Issue.Severity.MEDIUM; // safe default
    }

    private LocalDateTime computeDueAt(Issue.Severity severity) {
        long hours = RESOLVE_SLA_HOURS.getOrDefault(severity, 720L);
        return LocalDateTime.now().plusHours(hours);
    }

    private String buildIssueRef(Long tenantId) {
        long seq = issueRepository.nextIssueRefSequence(tenantId);
        return String.format("ISS-%d-%04d", LocalDateTime.now().getYear(), seq);
    }

    private String listToJson(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return null;
        try { return objectMapper.writeValueAsString(ids); }
        catch (Exception e) { return null; }
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try { return objectMapper.writeValueAsString(obj); }
        catch (Exception e) { return null; }
    }

    private String buildEscalationEmailBody(Issue issue, String recipientType) {
        return String.format("""
            <html><body>
            <h2 style="color:#d32f2f">SLA Breach Alert</h2>
            <p><strong>Issue:</strong> %s — %s</p>
            <p><strong>Severity:</strong> %s</p>
            <p><strong>Status:</strong> %s</p>
            <p><strong>Due Date:</strong> %s</p>
            <p><strong>Escalation Count:</strong> %d</p>
            <p>Please log in to DigiOSec GRC to take immediate action.</p>
            </body></html>
            """,
                issue.getIssueRef(), issue.getTitle(),
                issue.getSeverity(), issue.getStatus(),
                issue.getDueAt(), issue.getEscalationCount()
        );
    }

    private IssueResponse toResponse(Issue i, Long tenantId) {
        // Resolve owner name
        String ownerName = null;
        if (i.getOwnerId() != null) {
            ownerName = userRepository.findById(i.getOwnerId())
                    .map(User::getFullName).orElse(null);
        }

        // Compute slaDueInHours
        Long slaDueInHours = null;
        if (i.getDueAt() != null) {
            slaDueInHours = ChronoUnit.HOURS.between(LocalDateTime.now(), i.getDueAt());
        }

        return IssueResponse.builder()
                .id(i.getId())
                .issueRef(i.getIssueRef())
                .title(i.getTitle())
                .description(i.getDescription())
                .issueType(i.getIssueType())
                .severity(i.getSeverity())
                .status(i.getStatus())
                .category(i.getCategory())
                .sourceModule(i.getSourceModule())
                .sourceEntityType(i.getSourceEntityType())
                .sourceEntityId(i.getSourceEntityId())
                .sourceDescription(i.getSourceDescription())
                .externalId(i.getExternalId())
                .externalSource(i.getExternalSource())
                .cvssScore(i.getCvssScore())
                .ownerId(i.getOwnerId())
                .ownerName(ownerName)
                .createdBy(i.getCreatedBy())
                .raisedBySide(i.getRaisedBySide())
                .dueAt(i.getDueAt())
                .slaBreached(i.isSlaBreached())
                .escalationCount(i.getEscalationCount())
                .lastEscalatedAt(i.getLastEscalatedAt())
                .slaDueInHours(slaDueInHours)
                .createdAt(i.getCreatedAt())
                .updatedAt(i.getUpdatedAt())
                .acknowledgedAt(i.getAcknowledgedAt())
                .remediatedAt(i.getRemediatedAt())
                .validatedAt(i.getValidatedAt())
                .closedAt(i.getClosedAt())
                .rcaMethod(i.getRcaMethod())
                .rootCauseCategory(i.getRootCauseCategory())
                .immediateCause(i.getImmediateCause())
                .rootCause(i.getRootCause())
                .contributingFactors(i.getContributingFactors())
                .isSystemic(i.isSystemic())
                .rcaJson(i.getRcaJson())
                .remediationPlan(i.getRemediationPlan())
                .remediationType(i.getRemediationType())
                .acceptedRisk(i.isAcceptedRisk())
                .acceptedRiskNote(i.getAcceptedRiskNote())
                .closureSummary(i.getClosureSummary())
                .linkedControlIds(i.getLinkedControlIds())
                .linkedRiskIds(i.getLinkedRiskIds())
                .frameworkRef(i.getFrameworkRef())
                .workflowInstanceId(i.getWorkflowInstanceId())
                .listScreenKey(i.getListScreenKey())
                .detailScreenKey(i.getDetailScreenKey())
                .itemScreenKey(i.getItemScreenKey())
                .build();
    }
}