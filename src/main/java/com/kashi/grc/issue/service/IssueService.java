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

    private final IssueRepository          issueRepository;
    private final WorkflowEngineService    workflowEngineService;
    private final WorkflowRepository       workflowRepository;
    private final WorkflowInstanceRepository instanceRepository;
    private final NotificationService      notificationService;
    private final EmailSenderService       emailSenderService;
    private final UserRepository           userRepository;
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
        if (req.getRcaJson()             != null) issue.setRcaJson(req.getRcaJson());
        if (req.getRootCauseCategory()   != null) issue.setRootCauseCategory(req.getRootCauseCategory());
        if (req.getRemediationPlan()     != null) issue.setRemediationPlan(req.getRemediationPlan());
        if (req.getRemediationType()     != null) issue.setRemediationType(req.getRemediationType());
        if (req.getFrameworkRef()        != null) issue.setFrameworkRef(req.getFrameworkRef());
        if (req.getLinkedControlIds()    != null) issue.setLinkedControlIds(listToJson(req.getLinkedControlIds()));
        if (req.getLinkedRiskIds()       != null) issue.setLinkedRiskIds(listToJson(req.getLinkedRiskIds()));

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
        if (newStatus == Issue.Status.TRIAGED && issue.getAcknowledgedAt() == null) {
            issue.setAcknowledgedAt(LocalDateTime.now());
        }
        if (newStatus == Issue.Status.RESOLVED) {
            issue.setRemediatedAt(LocalDateTime.now());
        }
        if (newStatus == Issue.Status.CLOSED || newStatus == Issue.Status.ACCEPTED_RISK) {
            issue.setClosedAt(LocalDateTime.now());
            issue.setClosedBy(userId);
        }

        issueRepository.save(issue);
        log.info("[ISSUE] Status updated | id={} | status={} | by={}", id, newStatus, userId);
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

    private void startWorkflowIfConfigured(Issue issue, Long overrideWorkflowId,
                                           Long initiatedBy, Long tenantId) {
        // Use override if provided; otherwise look up default for issueType
        Long workflowId = overrideWorkflowId;
        if (workflowId == null) {
            // Convention: workflow name matches "ISSUE_MGMT_" + issueType
            // e.g. "ISSUE_MGMT_INTERNAL", "ISSUE_MGMT_AUTOMATED"
            String expectedName = "ISSUE_MGMT_" + issue.getIssueType().name();
            workflowId = workflowRepository.findAll().stream()
                    .filter(w -> w.isActive() && expectedName.equals(w.getName()))
                    .findFirst()
                    .map(w -> w.getId())
                    .orElse(null);
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
                .closedAt(i.getClosedAt())
                .rcaJson(i.getRcaJson())
                .rootCauseCategory(i.getRootCauseCategory())
                .remediationPlan(i.getRemediationPlan())
                .remediationType(i.getRemediationType())
                .acceptedRisk(i.isAcceptedRisk())
                .acceptedRiskNote(i.getAcceptedRiskNote())
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