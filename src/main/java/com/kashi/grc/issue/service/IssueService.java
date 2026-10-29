package com.kashi.grc.issue.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ValidationException;
import com.kashi.grc.common.exception.ForbiddenException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.service.MailService;
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
    private final MailService                                        mailService;
    private final UserRepository                                     userRepository;
    private final com.kashi.grc.usermanagement.repository.UserTenantMembershipRepository membershipRepository;
    private final com.kashi.grc.common.repository.DbRepository       dbRepository;
    private final com.kashi.grc.tenant.repository.TenantRepository   tenantRepository;
    private final com.kashi.grc.common.security.AccountAccessService   accountAccess;
    private final com.kashi.grc.audit.repository.AuditFindingRepository     auditFindingRepository;
    private final com.kashi.grc.audit.repository.AuditEngagementRepository  auditEngagementRepository;
    private final ObjectMapper             objectMapper;
    private final org.springframework.context.ApplicationEventPublisher      eventPublisher;
    private final com.kashi.grc.document.repository.DocumentLinkRepository   documentLinkRepository;
    private final com.kashi.grc.module.repository.ModuleBlueprintRepository  blueprintRepository;
    private final com.kashi.grc.workflow.repository.WorkflowStepRepository   workflowStepRepository;
    private final com.kashi.grc.actionitem.repository.ActionItemRepository   actionItemRepository;

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

        // ── Guard: createdBy and ownerId must be real users of THIS tenant ──
        // Workflow actor resolution reads these columns directly — step 1 of the
        // issue workflow is ENTITY_CREATOR and step 2 is ENTITY_OWNER — so a bad
        // id here does not fail loudly, it silently assigns a task to whoever
        // holds that id. User ids are global, so that person is usually in a
        // different tenant entirely.
        //
        // This is exactly how audit findings escalated with createdBy set to the
        // TENANT id: the value was a valid user id, just not the right user, and
        // nothing downstream could tell the difference.
        requireTenantUser(createdBy, tenantId, "createdBy");
        requireTenantUser(req.getOwnerId(), tenantId, "ownerId");

        String ref = buildIssueRef(tenantId);

        Issue issue = Issue.builder()
                .tenantId(tenantId)
                .issueRef(ref)
                .title(req.getTitle())
                .description(req.getDescription())
                .issueType(req.getIssueType())
                .severity(req.getSeverity())
                .status(Boolean.TRUE.equals(req.getStartTriaged()) ? Issue.Status.TRIAGED : Issue.Status.OPEN)
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
                // Null for everything except a TPRM escalation. Scoping, not
                // decoration — see the note on Issue.vendorId.
                .vendorId(req.getVendorId())
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
        linkSourceEvidence(issue, createdBy);

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
    /**
     * Resolves the "system" user for a given tenant — used as createdBy/initiator
     * for automated ingestion. Multi-tenant safe: each tenant's own person, never
     * a single global hard-coded user.
     *
     *   1. the organisation's owner (tenants.owner_user_id) — always exactly one,
     *      and the one account that cannot be suspended on its own;
     *   2. else an active organisation-side member holding USER_EDIT — the
     *      permission user management gates on, i.e. someone who administers
     *      the organisation. This used to be role id 8: a database id baked
     *      into the code, pointing at a different role (or none) on any
     *      database seeded in another order. A permission, not a role name,
     *      so a renamed or added admin role keeps working;
     *   3. else app.system.userId (global config).
     *
     * Suspended people are never picked (the membership query filters them).
     */
    private Long resolveSystemUserId(Long tenantId) {
        Long owner = tenantRepository.findById(tenantId)
                .map(com.kashi.grc.tenant.domain.Tenant::getOwnerUserId).orElse(null);
        if (owner != null && userRepository.findById(owner)
                .filter(u -> !u.isDeleted() && (u.getStatus() == com.kashi.grc.usermanagement.domain.UserStatus.ACTIVE
                        || u.getStatus() == com.kashi.grc.usermanagement.domain.UserStatus.LOCKED))
                .isPresent()) {
            return owner;
        }
        List<Long> admins = dbRepository.findUserIdsByPermissionAndTenant(
                com.kashi.grc.usermanagement.service.user.UserLifecycleService.EDIT_PERMISSION, tenantId,
                com.kashi.grc.usermanagement.domain.RoleSide.ORGANIZATION.name());
        if (!admins.isEmpty()) {
            log.info("[ISSUE-INGEST] No usable owner for tenantId={} — using an organisation administrator as system user", tenantId);
            return admins.get(0);
        }
        log.warn("[ISSUE-INGEST] No owner or administrator found for tenantId={} — falling back to app.system.userId={}",
                tenantId, systemUserId);
        return systemUserId;
    }

    @Transactional
    public IssueResponse ingest(IssueIngestRequest req, Long tenantId) {
        // Resolve tenant-scoped system user (each tenant's own ORG_ADMIN)
        Long tenantSystemUserId = resolveSystemUserId(tenantId);

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
                .createdBy(tenantSystemUserId)
                .dueAt(computeDueAt(severity))
                .frameworkRef(req.getFrameworkRef())
                .build();

        issueRepository.save(issue);
        log.info("[ISSUE-INGEST] New | ref={} | source={} | externalId={} | severity={} | tenantId={}",
                ref, req.getSource(), req.getExternalId(), severity, tenantId);

        // Auto-start AUTOMATED workflow
        startWorkflowIfConfigured(issue, req.getWorkflowId(), tenantSystemUserId, tenantId);

        // If Step 1 (and possibly Step 2) auto-completed, sync issue status to TRIAGED
        // so the UI shows the correct action buttons (Start Remediation, not Triage).
        syncStatusAfterWorkflowStart(issue, tenantSystemUserId, tenantId);

        // Notify GRC Manager group — pool-assigned triage
        notificationService.send(tenantSystemUserId, "ISSUE_AUTOMATED_INGEST",
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
    public IssueResponse update(Long id, IssueRequest req, Long userId, Long tenantId) {
        Issue issue = issueRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Issue", id));
        if (!issue.getTenantId().equals(tenantId))
            throw new ForbiddenException("Issue does not belong to this tenant");

        // Capture old values BEFORE mutation, for generic advanceOnFieldSet check below.
        // Only fields that are realistically used as "fill this to advance" triggers
        // need to be captured — extend this map as new advanceOnFieldSet use cases arise.
        java.util.Map<String, Object> oldValues = java.util.Map.of(
                "ownerId", java.util.Optional.ofNullable(issue.getOwnerId())
        );

        applyFields(issue, req);
        issueRepository.save(issue);

        // ── Generic advanceOnFieldSet ───────────────────────────────────────
        // Steps can declare in step_ui_override_json: {"advanceOnFieldSet": "ownerId"}
        // When that field transitions null → non-null in this update, the current
        // PENDING task on this step is auto-approved, advancing the workflow.
        // Add more cases to newValues/oldValues as new "fill X to advance" steps appear —
        // no further engine changes needed, just one SQL line per step.
        if (issue.getWorkflowInstanceId() != null) {
            String advanceField = getAdvanceOnFieldSet(issue);
            if (advanceField != null) {
                java.util.Map<String, Object> newValues = java.util.Map.of(
                        "ownerId", java.util.Optional.ofNullable(issue.getOwnerId())
                );
                Object oldVal = ((java.util.Optional<?>) oldValues.getOrDefault(advanceField, java.util.Optional.empty())).orElse(null);
                Object newVal = ((java.util.Optional<?>) newValues.getOrDefault(advanceField, java.util.Optional.empty())).orElse(null);
                if (oldVal == null && newVal != null) {
                    log.info("[ISSUE-WF] advanceOnFieldSet '{}' transitioned null -> {} | issueId={}",
                            advanceField, newVal, issue.getId());
                    // Only the step's own holder completes it by filling the
                    // field. Anyone else editing the issue (Edit details) just
                    // saves the value; the step stays with its holder.
                    if (holdsCurrentStep(issue, userId)) {
                        advanceWorkflowTask(issue, userId, advanceField + " set");
                    } else {
                        log.info("[ISSUE-WF] {} set by user {} who does not hold the current step — saved, step not advanced",
                                advanceField, userId);
                    }
                }
            }
        }

        return toResponse(issue, tenantId);
    }

    /** Every editable field on IssueRequest that is present, onto the issue. Shared by update and transition. */
    private void applyFields(Issue issue, IssueRequest req) {
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
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TRANSITION — the step's form and its completion, in one request
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Saves the fields the current step owns, checks the move is complete, then
     * moves the status — which approves the step's task (updateStatus →
     * advanceWorkflowTask).
     *
     * Every issue status button ends a step, so this is where "a step cannot
     * finish half-done" is enforced. The page's modal collects the fields and
     * marks them required, but the rule lives here so a direct call to the
     * endpoint is held to it too. What each move needs is a property of the
     * issue's lifecycle, not of any one workflow, so it holds on every issue
     * workflow without naming a step:
     *
     *   → IN_PROGRESS     a plan: root cause, remediation type and plan
     *   → PENDING_REVIEW  at least one remediation evidence file — the basis
     *                     on which anyone can validate the fix
     *   → CLOSED          a closure summary
     *   → ACCEPTED_RISK   the reason the risk is accepted
     *
     * The check runs after the fields are applied and throws on a gap, so the
     * transaction rolls back and nothing half-written is kept; the user's input
     * stays in the modal to correct.
     *
     * @param fields may be null — a move with nothing to fill in.
     */
    @Transactional
    public IssueResponse transition(Long id, Issue.Status to, IssueRequest fields, Long userId, Long tenantId) {
        Issue issue = issueRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Issue", id));
        if (!issue.getTenantId().equals(tenantId))
            throw new ForbiddenException("Issue does not belong to this tenant");

        requireStepAllowsMove(issue, to, tenantId);

        if (fields != null) applyFields(issue, fields);

        java.util.List<String> missing = new java.util.ArrayList<>();
        switch (to) {
            case IN_PROGRESS -> {
                if (isBlank(issue.getRootCause()))       missing.add("root cause");
                if (isBlank(issue.getRemediationType())) missing.add("remediation type");
                if (isBlank(issue.getRemediationPlan())) missing.add("remediation plan");
            }
            case PENDING_REVIEW -> {
                // Rejected files stay on the record but are not evidence of the fix.
                boolean anyUsable = remediationEvidence(id).stream()
                        .anyMatch(l -> !REJECTED.equals(l.getReviewStatus()));
                if (!anyUsable)
                    missing.add("remediation evidence (attach it on the Evidence tab)");
            }
            case RESOLVED -> {
                // The validator's decision rests on the evidence, so every file
                // is decided and at least one is accepted before it can resolve.
                java.util.List<com.kashi.grc.document.domain.DocumentLink> files = remediationEvidence(id);
                long undecided = files.stream().filter(l -> l.getReviewStatus() == null).count();
                boolean anyAccepted = files.stream().anyMatch(l -> ACCEPTED.equals(l.getReviewStatus()));
                if (undecided > 0)
                    missing.add("a decision on every remediation evidence file (" + undecided + " not reviewed)");
                else if (!anyAccepted)
                    missing.add("at least one accepted remediation evidence file — or use Request changes");
            }
            case CLOSED -> {
                if (isBlank(issue.getClosureSummary()))  missing.add("closure summary");
            }
            case ACCEPTED_RISK -> {
                if (isBlank(issue.getAcceptedRiskNote())) missing.add("the reason the risk is accepted");
                issue.setAcceptedRisk(true);
            }
            default -> { }
        }
        if (!missing.isEmpty()) {
            throw new ValidationException("Before this step can be completed, add: "
                    + String.join(", ", missing) + ".");
        }

        issueRepository.save(issue);
        return updateStatus(id, to, userId, tenantId);
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    // ─────────────────────────────────────────────────────────────────────────
    // EVIDENCE REVIEW — the validator decides each remediation file
    // ─────────────────────────────────────────────────────────────────────────

    private static final String ACCEPTED = "ACCEPTED";
    private static final String REJECTED = "REJECTED";

    private java.util.List<com.kashi.grc.document.domain.DocumentLink> remediationEvidence(Long issueId) {
        return documentLinkRepository.findActiveByEntity("ISSUE", issueId, "REMEDIATION");
    }

    /**
     * Whether the issue's current step is the one that validates the fix — the
     * step whose button is Validate & Resolve. Where a step lists its buttons
     * ("transitions"), that list decides; on a step without one, the review
     * statuses decide. The vendor's own CISO review hands on with Send for
     * validation and does not decide files: the organisation's validator does.
     */
    private boolean atValidationStep(Issue issue) {
        if (issue.getStatus() != Issue.Status.PENDING_REVIEW
                && issue.getStatus() != Issue.Status.PENDING_VALIDATION) return false;
        java.util.List<String> keys = issue.getWorkflowInstanceId() != null ? currentStepTransitions(issue) : null;
        return keys == null || keys.isEmpty() || keys.contains("ISSUE_VALIDATE");
    }

    /**
     * Accept or reject one remediation evidence file. Rejecting needs a reason.
     * A decision can be changed while the issue is still at validation; once
     * the issue moves on, reviewed files are locked (DocumentController
     * refuses to remove a reviewed link).
     *
     * Who: the holder of the validation step's task — the controller checks
     * that (requireCanMoveStatus) before calling this.
     */
    @Transactional
    public Map<String, Object> reviewEvidence(Long issueId, Long linkId, String decision, String note,
                                              Long userId, Long tenantId) {
        Issue issue = issueRepository.findById(issueId)
                .filter(i -> i.getTenantId().equals(tenantId))
                .orElseThrow(() -> new ResourceNotFoundException("Issue", issueId));
        if (!atValidationStep(issue))
            throw new BusinessException("ISSUE_NOT_AT_VALIDATION",
                    "Evidence is reviewed at the validation step. This issue is " + issue.getStatus() + ".",
                    org.springframework.http.HttpStatus.CONFLICT);

        com.kashi.grc.document.domain.DocumentLink link = documentLinkRepository.findById(linkId)
                .filter(l -> tenantId.equals(l.getTenantId())
                        && "ISSUE".equals(l.getEntityType()) && issueId.equals(l.getEntityId())
                        && "REMEDIATION".equals(l.getLinkType()))
                .orElseThrow(() -> new ResourceNotFoundException("Remediation evidence", linkId));

        String d = decision == null ? "" : decision.trim().toUpperCase();
        if (!ACCEPTED.equals(d) && !REJECTED.equals(d))
            throw new ValidationException("decision must be ACCEPTED or REJECTED.");
        if (REJECTED.equals(d) && isBlank(note))
            throw new ValidationException("Say what is wrong with the file, so the owner can fix it.");

        link.setReviewStatus(d);
        link.setReviewedBy(userId);
        link.setReviewedAt(LocalDateTime.now());
        link.setReviewNote(isBlank(note) ? null : note.trim());
        documentLinkRepository.save(link);
        log.info("[ISSUE-EVIDENCE] {} | issueId={} | linkId={} | by={}", d, issueId, linkId, userId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("linkId",       link.getId());
        out.put("reviewStatus", link.getReviewStatus());
        out.put("reviewedAt",   link.getReviewedAt());
        out.put("reviewNote",   link.getReviewNote());
        return out;
    }

    /**
     * The validator sends the fix back to the owner: the workflow returns to
     * the step that submits it for review, the issue goes back to IN_PROGRESS,
     * and the reason is recorded on the step's history.
     *
     * The target is the step whose button is Submit for Review — read from
     * the steps' "transitions" lists, so no step number is named — falling
     * back to the previous step on a workflow that has no lists.
     */
    @Transactional
    public IssueResponse requestChanges(Long issueId, String note, Long userId, Long tenantId) {
        Issue issue = issueRepository.findById(issueId)
                .filter(i -> i.getTenantId().equals(tenantId))
                .orElseThrow(() -> new ResourceNotFoundException("Issue", issueId));
        if (isBlank(note))
            throw new ValidationException("Say what needs to change, so the owner knows what to fix.");
        if (issue.getWorkflowInstanceId() == null)
            throw new BusinessException("ISSUE_NO_WORKFLOW", "This issue has no running workflow to send back.",
                    org.springframework.http.HttpStatus.CONFLICT);
        requireStepAllowsMove(issue, Issue.Status.IN_PROGRESS, tenantId);

        com.kashi.grc.workflow.domain.WorkflowInstance wf =
                instanceRepository.findById(issue.getWorkflowInstanceId()).orElseThrow(() ->
                        new ResourceNotFoundException("WorkflowInstance", issue.getWorkflowInstanceId()));
        com.kashi.grc.workflow.domain.StepInstance current = stepInstanceRepository.findById(wf.getCurrentStepId())
                .orElseThrow(() -> new ResourceNotFoundException("StepInstance", wf.getCurrentStepId()));
        com.kashi.grc.workflow.domain.TaskInstance task = taskInstanceRepository.findByStepInstanceId(current.getId())
                .stream()
                .filter(t -> userId.equals(t.getAssignedUserId())
                        && (t.getStatus() == com.kashi.grc.workflow.enums.TaskStatus.PENDING
                        || t.getStatus() == com.kashi.grc.workflow.enums.TaskStatus.IN_PROGRESS))
                .findFirst()
                .orElseThrow(() -> new BusinessException("ISSUE_NOT_YOUR_STEP",
                        "Only the person holding \"" + current.getSnapName() + "\" can send it back.",
                        org.springframework.http.HttpStatus.FORBIDDEN));

        Integer currentOrder = current.getSnapStepOrder();
        Long targetStepId = workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc(wf.getWorkflowId()).stream()
                .filter(s -> currentOrder == null || s.getStepOrder() < currentOrder)
                .filter(s -> stepTransitions(s.getStepUiOverrideJson()).contains("ISSUE_SUBMIT_REVIEW"))
                .reduce((a, b) -> b)                     // the latest one before this step
                .map(com.kashi.grc.workflow.domain.WorkflowStep::getId)
                .orElse(null);                           // none listed: the previous step

        workflowEngineService.returnToEarlierStep(task.getId(), targetStepId,
                "Changes requested: " + note.trim(), userId);

        issue.setStatus(Issue.Status.IN_PROGRESS);
        issue.setRemediatedAt(null);
        issueRepository.save(issue);
        log.info("[ISSUE] Changes requested | issueId={} | by={} | targetStepId={}", issueId, userId, targetStepId);
        return toResponse(issue, tenantId);
    }

    private java.util.List<String> stepTransitions(String json) {
        if (json == null || json.isBlank()) return java.util.List.of();
        try {
            com.fasterxml.jackson.databind.JsonNode list = objectMapper.readTree(json).path("transitions");
            java.util.List<String> out = new java.util.ArrayList<>();
            if (list.isArray()) list.forEach(n -> out.add(n.asText()));
            return out;
        } catch (Exception e) {
            return java.util.List.of();
        }
    }

    /**
     * An issue raised from a finding carries that finding's evidence as its
     * issue evidence — linked, not copied, so nobody uploads the same files
     * again and both records point at one document.
     *
     *   AUDIT_FINDING           the finding's deficiency / attachment files
     *   ASSESSMENT_REMEDIATION  the files on the vendor's answer to that question
     *
     * Linked as DEFICIENCY ("proof the issue exists"); remediation evidence is
     * always new. Failure is logged, never fatal to raising the issue.
     */
    private void linkSourceEvidence(Issue issue, Long createdBy) {
        try {
            String srcType = null;
            Long   srcId   = null;
            String label   = null;
            if ("AUDIT_FINDING".equals(issue.getSourceEntityType()) && issue.getSourceEntityId() != null) {
                srcType = "AUDIT_FINDING";
                srcId   = issue.getSourceEntityId();
                label   = "audit finding";
            } else if ("ASSESSMENT_REMEDIATION".equals(issue.getSourceEntityType()) && issue.getSourceEntityId() != null) {
                var item = actionItemRepository.findById(issue.getSourceEntityId()).orElse(null);
                if (item != null && item.getEntityType() != null && item.getEntityId() != null) {
                    srcType = item.getEntityType().name();
                    srcId   = item.getEntityId();
                    label   = "vendor's answer";
                }
            }
            if (srcType == null) return;

            java.util.Set<Long> already = new java.util.HashSet<>();
            documentLinkRepository.findByEntityTypeAndEntityIdAndTenantId("ISSUE", issue.getId(), issue.getTenantId())
                    .forEach(l -> already.add(l.getDocumentId()));
            int linked = 0;
            for (var src : documentLinkRepository.findAllActiveByEntity(srcType, srcId)) {
                if (!issue.getTenantId().equals(src.getTenantId())) continue;
                if ("REPORT".equals(src.getLinkType()) || "TEMPLATE".equals(src.getLinkType())) continue;
                if (!already.add(src.getDocumentId())) continue;
                documentLinkRepository.save(com.kashi.grc.document.domain.DocumentLink.builder()
                        .tenantId(issue.getTenantId())
                        .documentId(src.getDocumentId())
                        .entityType("ISSUE")
                        .entityId(issue.getId())
                        .linkType("DEFICIENCY")
                        .createdBy(createdBy)
                        .createdAt(LocalDateTime.now())
                        .notes("Linked from the " + label + " this issue was raised from.")
                        .build());
                linked++;
            }
            if (linked > 0)
                log.info("[ISSUE] Linked {} source evidence file(s) | issueId={} | from {}#{}",
                        linked, issue.getId(), srcType, srcId);
        } catch (Exception e) {
            log.warn("[ISSUE] Could not link source evidence | issueId={} | {}", issue.getId(), e.getMessage());
        }
    }

    /**
     * The current step may name the status buttons that complete it
     * (step_ui_override_json "transitions": [actionKey, …]). When it does, only
     * the moves those buttons make from the issue's current status are allowed.
     *
     * Every status move approves whatever step is current, so a button pressed
     * on the wrong step completes the wrong step — "Send for validation" on a
     * step meant for "Validate & Resolve" skips a step and leaves the rest of
     * the workflow one behind its status. The page hides the other buttons;
     * this refuses them for anyone calling the endpoint directly.
     *
     * The action keys are turned into target statuses through the ISSUE
     * blueprint's status_flow_json, which is the same table the page uses, so
     * the two cannot disagree. No list on the step = no restriction, which is
     * every step that predates this. ACCEPTED_RISK is outside the step flow —
     * it is the way out of any step — and is never restricted here.
     */
    private void requireStepAllowsMove(Issue issue, Issue.Status to, Long tenantId) {
        if (to == Issue.Status.ACCEPTED_RISK || issue.getWorkflowInstanceId() == null) return;
        java.util.List<String> allowedKeys = currentStepTransitions(issue);
        if (allowedKeys == null || allowedKeys.isEmpty()) return;

        String flow = blueprintRepository.findByEntityTypeIgnoreCaseAndTenantId("ISSUE", tenantId)
                .or(() -> blueprintRepository.findByEntityTypeIgnoreCaseAndTenantIdIsNull("ISSUE"))
                .map(com.kashi.grc.module.domain.ModuleBlueprint::getStatusFlowJson)
                .orElse(null);
        if (flow == null) return;   // no status flow to read — nothing to compare against

        java.util.Set<String> reachable = new java.util.HashSet<>();
        java.util.List<String> labels = new java.util.ArrayList<>();
        try {
            com.fasterxml.jackson.databind.JsonNode transitions = objectMapper.readTree(flow).path("transitions");
            String from = issue.getStatus() != null ? issue.getStatus().name() : null;
            for (com.fasterxml.jackson.databind.JsonNode t : transitions) {
                String key = t.path("actionKey").asText(null);
                if (key == null || !allowedKeys.contains(key)) continue;
                if (from != null && !from.equals(t.path("from").asText(null))) continue;
                reachable.add(t.path("to").asText());
                labels.add(t.path("label").asText(key));
            }
        } catch (Exception e) {
            log.warn("[ISSUE] Could not read ISSUE status flow — step move check skipped | {}", e.getMessage());
            return;
        }
        if (!reachable.contains(to.name())) {
            throw new BusinessException("ISSUE_WRONG_STEP_MOVE",
                    labels.isEmpty()
                            ? "This step is not completed by moving the issue to " + to.name() + "."
                            : "This step is completed with: " + String.join(", ", labels) + ".",
                    org.springframework.http.HttpStatus.CONFLICT);
        }
    }

    /** The current step instance's "transitions" list, or null. */
    private java.util.List<String> currentStepTransitions(Issue issue) {
        try {
            com.kashi.grc.workflow.domain.WorkflowInstance wf =
                    instanceRepository.findById(issue.getWorkflowInstanceId()).orElse(null);
            if (wf == null || wf.getCurrentStepId() == null) return null;
            String json = stepInstanceRepository.findById(wf.getCurrentStepId())
                    .map(com.kashi.grc.workflow.domain.StepInstance::getSnapUiOverrideJson).orElse(null);
            if (json == null || json.isBlank()) return null;
            com.fasterxml.jackson.databind.JsonNode list = objectMapper.readTree(json).path("transitions");
            if (!list.isArray()) return null;
            java.util.List<String> out = new java.util.ArrayList<>();
            list.forEach(n -> out.add(n.asText()));
            return out;
        } catch (Exception e) {
            log.warn("[ISSUE] Could not read the step's transitions | issueId={} | {}", issue.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * Reads the snap_ui_override_json of the issue's current step instance and
     * returns its "advanceOnFieldSet" value, or null if not configured / not on a step.
     */
    private String getAdvanceOnFieldSet(Issue issue) {
        try {
            com.kashi.grc.workflow.domain.WorkflowInstance wfInst =
                    instanceRepository.findById(issue.getWorkflowInstanceId()).orElse(null);
            if (wfInst == null || wfInst.getCurrentStepId() == null) return null;
            com.kashi.grc.workflow.domain.StepInstance si =
                    stepInstanceRepository.findById(wfInst.getCurrentStepId()).orElse(null);
            if (si == null || si.getSnapUiOverrideJson() == null) return null;
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(si.getSnapUiOverrideJson());
            com.fasterxml.jackson.databind.JsonNode field = node.get("advanceOnFieldSet");
            return (field != null && !field.isNull()) ? field.asText() : null;
        } catch (Exception e) {
            log.warn("[ISSUE-WF] Failed to read advanceOnFieldSet for issueId={}: {}", issue.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * Who may move an issue's status while its workflow is running: the person
     * holding an open task on the CURRENT step, or an override holder.
     *
     * Every status move approves the current step's task (advanceWorkflowTask).
     * The page already offers the buttons only to that task's holder
     * (status_flow_json transitions are task-gated), but the endpoints took
     * anyone in the tenant — and for anyone else the status changed while the
     * workflow silently did not, so the two drifted apart.
     *
     * No running workflow (none started, or finished — e.g. Reopen from CLOSED):
     * unchanged, the existing button permissions decide.
     */
    @Transactional(readOnly = true)
    public void requireCanMoveStatus(Long issueId, Long userId, boolean overrideHolder) {
        Issue issue = issueRepository.findById(issueId)
                .orElseThrow(() -> new ResourceNotFoundException("Issue", issueId));
        if (issue.getWorkflowInstanceId() == null || overrideHolder) return;
        com.kashi.grc.workflow.domain.WorkflowInstance wf =
                instanceRepository.findById(issue.getWorkflowInstanceId()).orElse(null);
        if (wf == null || wf.getCurrentStepId() == null) return;
        boolean holdsTask = taskInstanceRepository.findByStepInstanceId(wf.getCurrentStepId()).stream()
                .anyMatch(t -> userId.equals(t.getAssignedUserId())
                        && (t.getStatus() == com.kashi.grc.workflow.enums.TaskStatus.PENDING
                        || t.getStatus() == com.kashi.grc.workflow.enums.TaskStatus.IN_PROGRESS));
        if (holdsTask) return;
        String step = stepInstanceRepository.findById(wf.getCurrentStepId())
                .map(si -> si.getSnapName()).orElse("the current step");
        throw new com.kashi.grc.common.exception.BusinessException("ISSUE_NOT_YOUR_STEP",
                "This issue is at \"" + step + "\", which is assigned to someone else. "
                        + "Only the person holding that step (or someone with override) can move it on.",
                org.springframework.http.HttpStatus.FORBIDDEN);
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
            // The fix is done when the owner submits it, not when someone
            // else validates it — the old code stamped remediatedAt at RESOLVED.
            if (issue.getRemediatedAt() == null) issue.setRemediatedAt(LocalDateTime.now());
            advanceWorkflowTask(issue, userId, "Submitted for review");
        }
        if (newStatus == Issue.Status.PENDING_VALIDATION) {
            advanceWorkflowTask(issue, userId, "Submitted for validation");
        }
        if (newStatus == Issue.Status.RESOLVED) {
            if (issue.getRemediatedAt() == null) issue.setRemediatedAt(LocalDateTime.now());
            issue.setValidatedAt(LocalDateTime.now());
            advanceWorkflowTask(issue, userId, "Remediation validated");
        }
        if (newStatus == Issue.Status.CLOSED || newStatus == Issue.Status.ACCEPTED_RISK) {
            issue.setClosedAt(LocalDateTime.now());
            issue.setClosedBy(userId);
            advanceWorkflowTask(issue, userId, "Issue closed");
        }


        issueRepository.save(issue);
        log.info("[ISSUE] Status updated | id={} | status={} | by={}", id, newStatus, userId);
        // Close what the issue was raised from — the audit finding or vendor
        // remediation. See CloseIssueAction.onIssueClosed for why this is not
        // left to the workflow's automated step.
        if (newStatus == Issue.Status.CLOSED) {
            eventPublisher.publishEvent(new com.kashi.grc.issue.workflow.IssueClosedEvent(
                    issue.getId(), tenantId, userId));
        }
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
            // Suspended organisation: not escalated while suspended. The query is
            // "past due and not yet marked", so it is caught on the first run after
            // reactivation.
            if (!accountAccess.tenantOperating(issue.getTenantId())) continue;
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

            // Notify issue owner. Email now flows through the notification
            // pipeline (sla-alert template rule) instead of a direct sendRaw —
            // the old dual path double-emailed the owner once notifications
            // gained email fanout. Pipeline adds preferences, dispatch log,
            // and email_log lineage; context feeds template placeholders.
            if (issue.getOwnerId() != null) {
                notificationService.send(issue.getOwnerId(), "ISSUE_SLA_BREACH", message,
                        "ISSUE", issue.getId(),
                        null, java.util.Map.of(
                                "issueRef", issue.getIssueRef() != null ? issue.getIssueRef() : "",
                                "severity", String.valueOf(issue.getSeverity())));
            }
        }

        // Re-escalation for already-breached issues (daily nudge)
        LocalDateTime reescCutoff = now.minusHours(24);
        List<Issue> reescalate = issueRepository.findActiveBreachedForReescalation(reescCutoff);
        for (Issue issue : reescalate) {
            if (!accountAccess.tenantOperating(issue.getTenantId())) continue;
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
        {
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

            java.util.List<com.kashi.grc.workflow.domain.TaskInstance> openTasks = allTasks.stream()
                    .filter(t -> t.getStatus() == com.kashi.grc.workflow.enums.TaskStatus.PENDING
                            || t.getStatus() == com.kashi.grc.workflow.enums.TaskStatus.IN_PROGRESS)
                    .filter(t -> t.getTaskRole() == null
                            || t.getTaskRole() == com.kashi.grc.workflow.enums.TaskRole.ACTOR)
                    .toList();

            if (openTasks.isEmpty()) {
                log.warn("[ISSUE-WF] No open task on currentStep={} — nothing to advance",
                        wfInst.getCurrentStepId());
                return;
            }

            // ── WHOSE TASK ────────────────────────────────────────────────
            // The CALLER's. This used to approve the first pending task on the
            // step, whoever it belonged to. On a step with two holders
            // (Organisation Validation: Arjun and Priyanka) Priyanka's
            // "Validate & Resolve" tried to approve Arjun's task, the engine
            // refused (not your task), and that refusal — raised inside the
            // engine's transaction — marked the whole save rollback-only:
            // nothing saved, ERR-… on screen. With ANY_ONE approval the
            // caller's approval completes the step and the engine closes the
            // other holder's task.
            //
            // A caller with no task here reached this through override
            // (requireCanMoveStatus lets an override holder through), so the
            // step moves by override and is recorded as one.
            //
            // Failures are NOT swallowed any more: a status move whose step
            // could not complete must fail as a whole, with its real reason,
            // rather than leave the status moved and the workflow behind it.
            java.util.Optional<com.kashi.grc.workflow.domain.TaskInstance> mine = openTasks.stream()
                    .filter(t -> userId != null && userId.equals(t.getAssignedUserId()))
                    .findFirst();

            if (mine.isPresent()) {
                com.kashi.grc.workflow.domain.TaskInstance task = mine.get();
                log.info("[ISSUE-WF] Approving the caller's taskId={} | user={}", task.getId(), userId);
                com.kashi.grc.workflow.dto.request.TaskActionRequest req =
                        new com.kashi.grc.workflow.dto.request.TaskActionRequest();
                req.setTaskInstanceId(task.getId());
                req.setActionType(com.kashi.grc.workflow.enums.ActionType.APPROVE);
                req.setRemarks(remarks);
                workflowEngineService.performAction(req, userId);
                log.info("[ISSUE-WF] performAction succeeded | taskId={}", task.getId());
            } else {
                log.info("[ISSUE-WF] Caller {} holds no task on step {} — advancing by override",
                        userId, wfInst.getCurrentStepId());
                workflowEngineService.overrideAdvanceStep(wfInst.getCurrentStepId(), "APPROVE", remarks, userId);
            }
        }
    }

    /** Whether the user holds an open task on the issue's current step. */
    private boolean holdsCurrentStep(Issue issue, Long userId) {
        if (issue.getWorkflowInstanceId() == null || userId == null) return false;
        return instanceRepository.findById(issue.getWorkflowInstanceId())
                .map(com.kashi.grc.workflow.domain.WorkflowInstance::getCurrentStepId)
                .map(stepId -> taskInstanceRepository.findByStepInstanceId(stepId).stream()
                        .anyMatch(t -> userId.equals(t.getAssignedUserId())
                                && (t.getStatus() == com.kashi.grc.workflow.enums.TaskStatus.PENDING
                                || t.getStatus() == com.kashi.grc.workflow.enums.TaskStatus.IN_PROGRESS)))
                .orElse(false);
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

    /**
     * Rejects a user id that is null, deleted, or belongs to another tenant.
     * An issue with a stray actor id is worse than a failed create: it produces
     * a real task in a real inbox belonging to the wrong organisation.
     */
    private void requireTenantUser(Long userId, Long tenantId, String field) {
        if (userId == null) {
            throw new ValidationException(
                    "Cannot create issue: " + field + " is required. "
                            + "Assign an owner before escalating.");
        }
        // Membership, not users.tenant_id.
        //
        // An external auditor's HOME tenant is their firm; they work in the
        // client through a GUEST membership. Checking users.tenant_id therefore
        // rejects exactly the people this whole model exists to admit — it was
        // failing every auto-escalation whose finding was raised by a guest
        // auditor, with "createdBy=169 is not an active user of this
        // organization".
        boolean ok = userRepository.findById(userId)
                .filter(u -> !u.isDeleted())
                .map(u -> tenantId.equals(u.getTenantId())
                        || membershipRepository.findByUserIdAndTenantId(userId, tenantId)
                        .map(m -> m.isUsable())
                        .orElse(false))
                .orElse(false);
        if (!ok) {
            throw new ValidationException(
                    "Cannot create issue: " + field + "=" + userId
                            + " is not an active user of this organization.");
        }
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
                    .map(u -> {
                        String full = u.getFullName();
                        // getFullName() returns " " when both names are null — use email as fallback
                        return (full != null && !full.isBlank()) ? full : u.getEmail();
                    }).orElse(null);
        }

        // Source engagement, for issues escalated from an audit finding.
        // audit_findings carries engagement_id directly (idx_finding_engagement),
        // so this is one hop - going via control_instance_id would drop findings
        // that have none, which is a real case in the data.
        Long   sourceEngagementId   = null;
        String sourceEngagementRef  = null;
        String sourceEngagementName = null;
        if ("AUDIT_FINDING".equals(i.getSourceEntityType()) && i.getSourceEntityId() != null) {
            var eng = auditFindingRepository.findById(i.getSourceEntityId())
                    .map(f -> f.getEngagementId())
                    .flatMap(auditEngagementRepository::findById);
            if (eng.isPresent()) {
                sourceEngagementId   = eng.get().getId();
                sourceEngagementRef  = eng.get().getEngagementRef();
                sourceEngagementName = eng.get().getName();
            }
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
                .sourceEngagementId(sourceEngagementId)
                .sourceEngagementRef(sourceEngagementRef)
                .sourceEngagementName(sourceEngagementName)
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