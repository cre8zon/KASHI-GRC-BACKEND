package com.kashi.grc.risk.service;

import com.kashi.grc.asset.domain.Asset;
import com.kashi.grc.asset.domain.RiskAssetLink;
import com.kashi.grc.asset.repository.AssetRepository;
import com.kashi.grc.asset.repository.RiskAssetLinkRepository;
import com.kashi.grc.audit.domain.AuditControl;
import com.kashi.grc.audit.repository.AuditControlRepository;
import com.kashi.grc.common.exception.ForbiddenException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.exception.ValidationException;
import com.kashi.grc.issue.domain.Issue;
import com.kashi.grc.issue.repository.IssueRepository;
import com.kashi.grc.notification.service.NotificationService;
import com.kashi.grc.risk.domain.Risk;
import com.kashi.grc.risk.domain.RiskControlLink;
import com.kashi.grc.risk.dto.*;
import com.kashi.grc.risk.repository.RiskControlLinkRepository;
import com.kashi.grc.risk.repository.RiskRepository;
import com.kashi.grc.usermanagement.repository.UserRepository;
import com.kashi.grc.usermanagement.repository.UserTenantMembershipRepository;
import com.kashi.grc.workflow.dto.request.StartWorkflowRequest;
import com.kashi.grc.workflow.dto.response.WorkflowInstanceResponse;
import com.kashi.grc.workflow.service.WorkflowEngineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * RiskService — the ISO 27005 / Clause 6.1 register loop.
 *
 *   IDENTIFIED -> ASSESSED -> TREATMENT_PLANNED -> TREATED -> CLOSED
 *                     \-> ACCEPTED -> CLOSED
 *   CLOSED / ACCEPTED -> IDENTIFIED   (reopen)
 *
 * The transition table below MUST stay identical to
 * module_blueprints.status_flow_json for RISK and to the allowed_statuses_json
 * on each ui_actions row. Three copies of one truth is not ideal, but the
 * alternative — deriving the server's rules from a config row a tenant admin
 * can edit — makes the lifecycle editable by anyone with Screen Designer
 * access. The comment is the seam; keep them in step.
 *
 * ── LIBRARY ROWS ARE READ-ONLY ────────────────────────────────────────────
 * Every write path calls requireOwnRisk, which refuses a row whose tenant_id
 * is NULL. A tenant works on its own adopted COPY. This is enforced here and
 * not only in the UI, because the id of a library row is guessable and the
 * detail route accepts any id.
 *
 * ── PERMISSIONS ───────────────────────────────────────────────────────────
 * No @PreAuthorize, matching every other controller in this codebase (eight
 * exist project-wide, none on a module controller). risk:assess, risk:treat,
 * risk:accept and risk:close gate the BUTTONS via ui_actions.
 * required_permission; they do not gate the endpoints. What IS enforced here
 * is tenancy, row ownership, and transition legality — the things a missing
 * button would not stop. See the note at the end of the handover if you want
 * endpoint-level RBAC; it is a deliberate one-line-per-method addition, not an
 * oversight.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskService {

    private final RiskRepository                 riskRepository;
    private final RiskControlLinkRepository      linkRepository;
    private final AuditControlRepository         controlRepository;
    private final IssueRepository                issueRepository;
    private final AssetRepository                assetRepository;
    private final RiskAssetLinkRepository        riskAssetLinkRepository;
    private final UserRepository                 userRepository;
    private final UserTenantMembershipRepository membershipRepository;
    private final NotificationService            notificationService;
    private final WorkflowEngineService          workflowEngineService;

    /** Treatment option that requires an acceptance record. */
    private static final String RETAIN = "RETAIN";

    /**
     * Legal transitions. Mirrors status_flow_json — see class javadoc.
     */
    private static final Map<Risk.Status, Set<Risk.Status>> ALLOWED = Map.of(
            Risk.Status.IDENTIFIED,        EnumSet.of(Risk.Status.ASSESSED),
            Risk.Status.ASSESSED,          EnumSet.of(Risk.Status.TREATMENT_PLANNED, Risk.Status.ACCEPTED),
            Risk.Status.TREATMENT_PLANNED, EnumSet.of(Risk.Status.TREATED, Risk.Status.ACCEPTED),
            Risk.Status.TREATED,           EnumSet.of(Risk.Status.CLOSED),
            Risk.Status.ACCEPTED,          EnumSet.of(Risk.Status.CLOSED, Risk.Status.IDENTIFIED),
            Risk.Status.CLOSED,            EnumSet.of(Risk.Status.IDENTIFIED)
    );

    // ═════════════════════════════════════════════════════════════════════════
    // CREATE
    // ═════════════════════════════════════════════════════════════════════════

    @Transactional
    public RiskResponse create(RiskRequest req, Long createdBy, Long tenantId) {

        // ownerId is optional on a risk (unlike an issue) — a risk can be
        // raised before anyone has been made accountable for it, and refusing
        // that would push people to park risks in a spreadsheet instead. But if
        // one IS named it must be a real member of this tenant, because
        // ENTITY_OWNER actor resolution reads the column directly and a stray
        // id assigns a task to a stranger in another organisation.
        if (req.getOwnerId() != null) {
            requireTenantUser(req.getOwnerId(), tenantId, "ownerId");
        }

        Risk risk = Risk.builder()
                .tenantId(tenantId)
                .riskRef(resolveRef(req.getRiskRef(), tenantId))
                .title(req.getTitle())
                .description(req.getDescription())
                .category(req.getCategory())
                .riskSource(req.getRiskSource())
                .status(Risk.Status.IDENTIFIED)
                .ownerId(req.getOwnerId())
                .ownerTeam(trimToNull(req.getOwnerTeam()))
                .controlTags(trimToNull(req.getControlTags()))
                .frameworkRefs(trimToNull(req.getFrameworkRefs()))
                .inherentLikelihood(req.getInherentLikelihood())
                .inherentImpact(req.getInherentImpact())
                .inherentScore(score(req.getInherentLikelihood(), req.getInherentImpact()))
                .nextReviewDate(req.getNextReviewDate())
                .reviewFrequencyMonths(req.getReviewFrequencyMonths() != null
                        ? req.getReviewFrequencyMonths() : 12)
                .createdBy(createdBy)
                .build();

        riskRepository.save(risk);
        log.info("[RISK] Created | ref={} | category={} | tenantId={}",
                risk.getRiskRef(), risk.getCategory(), tenantId);

        startWorkflowIfConfigured(risk, req.getWorkflowId(), createdBy, tenantId);

        if (risk.getOwnerId() != null && !risk.getOwnerId().equals(createdBy)) {
            notificationService.send(risk.getOwnerId(), "RISK_ASSIGNED",
                    "Risk " + risk.getRiskRef() + " has been assigned to you",
                    "RISK", risk.getId());
        }

        return toResponse(risk, tenantId, createdBy);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // READ
    // ═════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public RiskResponse getById(Long id, Long tenantId, Long userId) {
        Risk risk = riskRepository.findById(id)
                .filter(r -> !r.isDeleted())
                // A library row is readable by everyone — that is how a tenant
                // decides whether to adopt it. Another TENANT's row is not.
                .filter(r -> r.getTenantId() == null || tenantId.equals(r.getTenantId()))
                .orElseThrow(() -> new ResourceNotFoundException("Risk", id));
        return toResponse(risk, tenantId, userId);
    }

    @Transactional(readOnly = true)
    public List<RiskResponse.LinkedControl> listLinkedControls(Long riskId, Long tenantId) {
        // Read-visibility only: linking is a write and is guarded separately.
        riskRepository.findById(riskId)
                .filter(r -> r.getTenantId() == null || tenantId.equals(r.getTenantId()))
                .orElseThrow(() -> new ResourceNotFoundException("Risk", riskId));
        return buildLinkedControls(riskId, tenantId);
    }

    /**
     * Issues raised against this risk.
     *
     * Matched on Issue.sourceEntityType = 'RISK' AND sourceEntityId = riskId —
     * the same source-linkage columns the audit module already uses to escalate
     * a finding into an issue.
     *
     * Issue.linkedRiskIds (a JSON array) is deliberately NOT searched. Matching
     * it means a LIKE against an unindexed JSON column, and '%1%' matches 11,
     * 21 and 100 as readily as 1 — a wrong answer that looks right. If that
     * column is to become the canonical link, it needs a proper join table
     * first, not a string search bolted on here.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listLinkedIssues(Long riskId, Long tenantId) {
        riskRepository.findById(riskId)
                .filter(r -> r.getTenantId() == null || tenantId.equals(r.getTenantId()))
                .orElseThrow(() -> new ResourceNotFoundException("Risk", riskId));

        List<Issue> issues = issueRepository.findAll((root, query, cb) -> cb.and(
                cb.equal(root.get("tenantId"), tenantId),
                cb.equal(root.get("sourceEntityType"), "RISK"),
                cb.equal(root.get("sourceEntityId"), riskId)
        ));

        List<Map<String, Object>> out = new ArrayList<>(issues.size());
        for (Issue i : issues) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",        i.getId());
            m.put("issueRef",  i.getIssueRef());
            m.put("title",     i.getTitle());
            m.put("severity",  i.getSeverity());
            m.put("status",    i.getStatus());
            m.put("ownerId",   i.getOwnerId());
            m.put("dueAt",     i.getDueAt());
            m.put("createdAt", i.getCreatedAt());
            out.add(m);
        }
        return out;
    }

    /**
     * Assets this risk is exposed to — the other half of the ISO 27005
     * asset-based identification link.
     *
     * Read-only from here on purpose. Linking and unlinking live on
     * AssetService, because the Asset module owns risk_asset_links; Risk only
     * reads through it. Two write paths for one join table is how the two ends
     * drift apart.
     *
     * Shaped for the generic LinkedEntitiesTab: id / ref / title / status /
     * badge / navEntityType.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listLinkedAssets(Long riskId, Long tenantId) {
        Risk risk = riskRepository.findById(riskId)
                .filter(r -> !r.isDeleted())
                .filter(r -> r.getTenantId() == null || tenantId.equals(r.getTenantId()))
                .orElseThrow(() -> new ResourceNotFoundException("Risk", riskId));

        // A platform library risk has no asset links by construction — assets
        // are tenant-specific and a library row belongs to no tenant.
        if (risk.getTenantId() == null) return List.of();

        List<RiskAssetLink> links = riskAssetLinkRepository.findByRiskIdAndTenantId(riskId, tenantId);
        if (links.isEmpty()) return List.of();

        List<Long> assetIds = links.stream().map(RiskAssetLink::getAssetId).toList();
        Map<Long, Asset> assetsById = new HashMap<>();
        assetRepository.findAllById(assetIds).forEach(a -> assetsById.put(a.getId(), a));

        List<Map<String, Object>> out = new ArrayList<>(links.size());
        for (RiskAssetLink link : links) {
            Asset asset = assetsById.get(link.getAssetId());
            if (asset == null || asset.isDeleted()) continue;

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("linkId",        link.getId());
            m.put("id",            asset.getId());
            m.put("ref",           asset.getAssetRef());
            m.put("title",         asset.getName());
            m.put("status",        asset.getStatus() != null ? asset.getStatus().name() : null);
            m.put("badge",         asset.getCriticality());
            m.put("linkNote",      link.getLinkNote());
            m.put("navEntityType", "asset");
            out.add(m);
        }
        return out;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // UPDATE — header and overview tabs
    // ═════════════════════════════════════════════════════════════════════════

    @Transactional
    public RiskResponse update(Long id, RiskRequest req, Long userId, Long tenantId) {
        Risk risk = requireOwnRisk(id, tenantId);

        // The header form carries a status dropdown. Echoing the current value
        // back is a harmless no-op; changing it here is refused, because every
        // real transition carries a permission, a timestamp and sometimes a
        // workflow advance, and none of those would run on this path.
        if (req.getStatus() != null && !req.getStatus().isBlank()
                && !req.getStatus().equalsIgnoreCase(risk.getStatus().name())) {
            throw new ValidationException(
                    "Status cannot be changed by editing the risk. Use the "
                            + "Record assessment / Plan treatment / Accept / Close actions, "
                            + "which record who changed it and when.");
        }

        if (req.getOwnerId() != null && !req.getOwnerId().equals(risk.getOwnerId())) {
            requireTenantUser(req.getOwnerId(), tenantId, "ownerId");
        }

        if (req.getTitle()       != null) risk.setTitle(req.getTitle());
        if (req.getDescription() != null) risk.setDescription(req.getDescription());
        if (req.getCategory()    != null) risk.setCategory(req.getCategory());
        if (req.getRiskSource()  != null) risk.setRiskSource(req.getRiskSource());
        if (req.getOwnerId()     != null) risk.setOwnerId(req.getOwnerId());
        if (req.getOwnerTeam()   != null) risk.setOwnerTeam(trimToNull(req.getOwnerTeam()));
        if (req.getControlTags() != null) risk.setControlTags(trimToNull(req.getControlTags()));
        if (req.getFrameworkRefs() != null) risk.setFrameworkRefs(trimToNull(req.getFrameworkRefs()));
        if (req.getNextReviewDate() != null) risk.setNextReviewDate(req.getNextReviewDate());
        if (req.getReviewFrequencyMonths() != null)
            risk.setReviewFrequencyMonths(req.getReviewFrequencyMonths());

        // riskRef is editable on the header form. Blank means "leave it"; a new
        // value must not collide with another entry in the same tenant.
        String newRef = trimToNull(req.getRiskRef());
        if (newRef != null && !newRef.equals(risk.getRiskRef())) {
            if (riskRepository.existsByRiskRefAndTenantId(newRef, tenantId)) {
                throw new ValidationException("Risk reference " + newRef + " is already in use.");
            }
            risk.setRiskRef(newRef);
        }

        // The create form also carries inherent likelihood/impact, and the
        // overview edit can therefore submit them. Recompute rather than trust.
        if (req.getInherentLikelihood() != null) risk.setInherentLikelihood(req.getInherentLikelihood());
        if (req.getInherentImpact()     != null) risk.setInherentImpact(req.getInherentImpact());
        risk.setInherentScore(score(risk.getInherentLikelihood(), risk.getInherentImpact()));

        risk.setUpdatedBy(userId);
        riskRepository.save(risk);
        log.info("[RISK] Updated | id={} | by={}", id, userId);
        return toResponse(risk, tenantId, userId);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // ASSESSMENT
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * PUT /assessment — saves the assessment tab without moving the status.
     * Editing a completed assessment must not silently re-run the transition.
     */
    @Transactional
    public RiskResponse saveAssessment(Long id, RiskAssessmentRequest req, Long userId, Long tenantId) {
        Risk risk = requireOwnRisk(id, tenantId);
        applyAssessment(risk, req);
        risk.setUpdatedBy(userId);
        riskRepository.save(risk);
        log.info("[RISK] Assessment saved | id={} | inherent={} residual={}",
                id, risk.getInherentScore(), risk.getResidualScore());
        return toResponse(risk, tenantId, userId);
    }

    /**
     * POST /assess — the RISK_ASSESS action. Saves the same fields AND moves
     * IDENTIFIED -> ASSESSED.
     */
    @Transactional
    public RiskResponse assess(Long id, RiskAssessmentRequest req, Long userId, Long tenantId) {
        Risk risk = requireOwnRisk(id, tenantId);
        if (req != null) applyAssessment(risk, req);

        if (risk.getInherentLikelihood() == null || risk.getInherentImpact() == null) {
            throw new ValidationException(
                    "Record an inherent likelihood and impact before marking the risk assessed.");
        }
        if (risk.getAssessedAt() == null) risk.setAssessedAt(LocalDateTime.now());

        transition(risk, Risk.Status.ASSESSED, userId);
        riskRepository.save(risk);
        log.info("[RISK] Assessed | id={} | score={} | by={}", id, risk.getInherentScore(), userId);
        return toResponse(risk, tenantId, userId);
    }

    private void applyAssessment(Risk risk, RiskAssessmentRequest req) {
        if (req.getInherentLikelihood() != null) risk.setInherentLikelihood(req.getInherentLikelihood());
        if (req.getInherentImpact()     != null) risk.setInherentImpact(req.getInherentImpact());
        if (req.getResidualLikelihood() != null) risk.setResidualLikelihood(req.getResidualLikelihood());
        if (req.getResidualImpact()     != null) risk.setResidualImpact(req.getResidualImpact());
        if (req.getAssessmentNotes()    != null) risk.setAssessmentNotes(req.getAssessmentNotes());

        LocalDateTime assessedAt = parseTemporal(req.getAssessedAt(), "assessedAt");
        if (assessedAt != null) risk.setAssessedAt(assessedAt);

        // Scores are ALWAYS derived. The form posts them; they are discarded.
        risk.setInherentScore(score(risk.getInherentLikelihood(), risk.getInherentImpact()));
        risk.setResidualScore(score(risk.getResidualLikelihood(), risk.getResidualImpact()));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // TREATMENT
    // ═════════════════════════════════════════════════════════════════════════

    /** PUT /treatment — saves the treatment tab without moving the status. */
    @Transactional
    public RiskResponse saveTreatment(Long id, RiskTreatmentRequest req, Long userId, Long tenantId) {
        Risk risk = requireOwnRisk(id, tenantId);
        applyTreatment(risk, req, tenantId);
        risk.setUpdatedBy(userId);
        riskRepository.save(risk);
        log.info("[RISK] Treatment saved | id={} | option={}", id, risk.getTreatmentOption());
        return toResponse(risk, tenantId, userId);
    }

    /** POST /plan-treatment — ASSESSED -> TREATMENT_PLANNED. */
    @Transactional
    public RiskResponse planTreatment(Long id, RiskTreatmentRequest req, Long userId, Long tenantId) {
        Risk risk = requireOwnRisk(id, tenantId);
        if (req != null) applyTreatment(risk, req, tenantId);

        if (isBlank(risk.getTreatmentOption())) {
            throw new ValidationException(
                    "Choose a treatment option (Modify, Retain, Avoid or Share) before planning treatment.");
        }
        if (isBlank(risk.getTreatmentPlan())) {
            throw new ValidationException("Record a treatment plan before planning treatment.");
        }

        transition(risk, Risk.Status.TREATMENT_PLANNED, userId);
        riskRepository.save(risk);
        log.info("[RISK] Treatment planned | id={} | option={} | by={}",
                id, risk.getTreatmentOption(), userId);
        return toResponse(risk, tenantId, userId);
    }

    /** POST /mark-treated — TREATMENT_PLANNED -> TREATED. */
    @Transactional
    public RiskResponse markTreated(Long id, RiskTreatmentRequest req, Long userId, Long tenantId) {
        Risk risk = requireOwnRisk(id, tenantId);
        if (req != null) applyTreatment(risk, req, tenantId);

        // A risk is treated BY controls. Marking it treated with none linked
        // records a claim the register cannot evidence, which is precisely what
        // an ISO 27001 auditor tests at Clause 8.2. MODIFY is the option that
        // asserts controls were applied; AVOID and SHARE legitimately have none.
        if ("MODIFY".equalsIgnoreCase(nullToEmpty(risk.getTreatmentOption()))
                && linkRepository.countByRiskId(risk.getId()) == 0) {
            throw new ValidationException(
                    "Link at least one control before marking a MODIFY-treated risk as treated. "
                            + "Use the Linked controls tab.");
        }
        if (risk.getResidualLikelihood() == null || risk.getResidualImpact() == null) {
            throw new ValidationException(
                    "Record the residual likelihood and impact before marking the risk treated. "
                            + "Treatment without a residual score cannot be evidenced.");
        }
        if (risk.getTreatedAt() == null) risk.setTreatedAt(LocalDateTime.now());

        transition(risk, Risk.Status.TREATED, userId);
        riskRepository.save(risk);
        log.info("[RISK] Treated | id={} | residual={} | by={}", id, risk.getResidualScore(), userId);
        return toResponse(risk, tenantId, userId);
    }

    /** POST /accept — ASSESSED or TREATMENT_PLANNED -> ACCEPTED. */
    @Transactional
    public RiskResponse accept(Long id, RiskTreatmentRequest req, Long userId, Long tenantId) {
        Risk risk = requireOwnRisk(id, tenantId);
        if (req != null) applyTreatment(risk, req, tenantId);

        // requires_remarks = 1 on RISK_ACCEPT, so the UI collects a reason.
        // It is recorded as the justification when the form left that blank —
        // an acceptance with no stated reason is an audit finding waiting to
        // happen, and the one moment to capture it is now.
        String justification = firstNonBlank(
                risk.getAcceptanceJustification(), req != null ? req.getRemarks() : null);
        if (isBlank(justification)) {
            throw new ValidationException(
                    "A written justification is required to accept a risk.");
        }
        risk.setAcceptanceJustification(justification);

        // The accepter is whoever pressed the button, not whoever the form
        // named. Acceptance is a personal act of accountability; letting the
        // payload nominate somebody else makes the record worthless.
        risk.setAcceptedById(userId);
        risk.setAcceptedAt(LocalDateTime.now());
        if (isBlank(risk.getTreatmentOption())) risk.setTreatmentOption(RETAIN);

        transition(risk, Risk.Status.ACCEPTED, userId);
        riskRepository.save(risk);
        log.info("[RISK] Accepted | id={} | by={}", id, userId);

        if (risk.getOwnerId() != null && !risk.getOwnerId().equals(userId)) {
            notificationService.send(risk.getOwnerId(), "RISK_ACCEPTED",
                    "Risk " + risk.getRiskRef() + " has been formally accepted",
                    "RISK", risk.getId());
        }
        return toResponse(risk, tenantId, userId);
    }

    /** POST /close — TREATED or ACCEPTED -> CLOSED. */
    @Transactional
    public RiskResponse close(Long id, String remarks, Long userId, Long tenantId) {
        Risk risk = requireOwnRisk(id, tenantId);
        transition(risk, Risk.Status.CLOSED, userId);
        risk.setClosedAt(LocalDateTime.now());
        if (!isBlank(remarks)) {
            risk.setAssessmentNotes(appendNote(risk.getAssessmentNotes(), "Closed: " + remarks));
        }
        riskRepository.save(risk);
        log.info("[RISK] Closed | id={} | by={}", id, userId);
        return toResponse(risk, tenantId, userId);
    }

    /** POST /reopen — CLOSED or ACCEPTED -> IDENTIFIED. */
    @Transactional
    public RiskResponse reopen(Long id, String remarks, Long userId, Long tenantId) {
        Risk risk = requireOwnRisk(id, tenantId);
        transition(risk, Risk.Status.IDENTIFIED, userId);

        // Reopening genuinely restarts the loop, so the derived lifecycle
        // timestamps are cleared. The assessment and treatment CONTENT is kept:
        // the previous analysis is the starting point for the new cycle, and
        // wiping it would make reopening indistinguishable from raising a
        // duplicate. Acceptance is the exception — it is cleared, because an
        // accepted risk that has been reopened is by definition no longer
        // accepted, and leaving the accepter's name on it misattributes a
        // decision they did not make.
        risk.setClosedAt(null);
        risk.setTreatedAt(null);
        risk.setAcceptedAt(null);
        risk.setAcceptedById(null);
        risk.setAcceptanceJustification(null);

        if (!isBlank(remarks)) {
            risk.setAssessmentNotes(appendNote(risk.getAssessmentNotes(), "Reopened: " + remarks));
        }
        riskRepository.save(risk);
        log.info("[RISK] Reopened | id={} | by={}", id, userId);
        return toResponse(risk, tenantId, userId);
    }

    private void applyTreatment(Risk risk, RiskTreatmentRequest req, Long tenantId) {
        if (req.getTreatmentOption() != null) risk.setTreatmentOption(trimToNull(req.getTreatmentOption()));
        if (req.getTreatmentPlan()   != null) risk.setTreatmentPlan(req.getTreatmentPlan());

        LocalDateTime treatedAt = parseTemporal(req.getTreatedAt(), "treatedAt");
        if (treatedAt != null) risk.setTreatedAt(treatedAt);

        // depends_on_json hides the acceptance fields unless the option is
        // RETAIN. That is a display rule; a hidden field is still submittable,
        // so the same rule is applied here rather than assumed.
        boolean retaining = RETAIN.equalsIgnoreCase(nullToEmpty(risk.getTreatmentOption()));
        if (retaining) {
            if (req.getAcceptanceJustification() != null)
                risk.setAcceptanceJustification(req.getAcceptanceJustification());
            if (req.getAcceptedById() != null) {
                requireTenantUser(req.getAcceptedById(), tenantId, "acceptedById");
                risk.setAcceptedById(req.getAcceptedById());
            }
            LocalDateTime acceptedAt = parseTemporal(req.getAcceptedAt(), "acceptedAt");
            if (acceptedAt != null) risk.setAcceptedAt(acceptedAt);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // CONTROL LINKAGE
    // ═════════════════════════════════════════════════════════════════════════

    @Transactional
    public RiskResponse.LinkedControl linkControl(Long riskId, RiskControlLinkRequest req,
                                                  Long userId, Long tenantId) {
        Risk risk = requireOwnRisk(riskId, tenantId);

        // Global controls plus this tenant's own. Anything else is another
        // organisation's private control and must not be linkable, or the link
        // itself leaks the fact that it exists.
        AuditControl control = controlRepository.findById(req.getControlId())
                .filter(c -> c.getTenantId() == null || tenantId.equals(c.getTenantId()))
                .orElseThrow(() -> new ResourceNotFoundException("AuditControl", req.getControlId()));

        if (linkRepository.existsByRiskIdAndControlId(riskId, control.getId())) {
            throw new ValidationException("That control is already linked to this risk.");
        }

        RiskControlLink link = RiskControlLink.builder()
                .tenantId(tenantId)
                .riskId(risk.getId())
                .controlId(control.getId())
                .linkNote(trimToNull(req.getLinkNote()))
                .createdBy(userId)
                .build();
        linkRepository.save(link);

        log.info("[RISK] Control linked | riskId={} controlId={} by={}", riskId, control.getId(), userId);

        Map<Long, String> effectiveness =
                linkRepository.findEffectivenessByControlIds(List.of(control.getId()), tenantId);
        return toLinkedControl(link, control, effectiveness);
    }

    @Transactional
    public void unlinkControl(Long riskId, Long controlId, Long tenantId) {
        requireOwnRisk(riskId, tenantId);
        RiskControlLink link = linkRepository.findByRiskIdAndControlId(riskId, controlId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "RiskControlLink", "controlId", controlId));
        linkRepository.delete(link);
        log.info("[RISK] Control unlinked | riskId={} controlId={}", riskId, controlId);
    }

    private List<RiskResponse.LinkedControl> buildLinkedControls(Long riskId, Long tenantId) {
        List<RiskControlLink> links = linkRepository.findByRiskId(riskId);
        if (links.isEmpty()) return List.of();

        List<Long> controlIds = links.stream().map(RiskControlLink::getControlId).toList();

        // One IN query for the controls, one for the instances. The obvious
        // version — findById per link — is two round trips per linked control,
        // and a well-used risk has a dozen.
        Map<Long, AuditControl> controlsById = new HashMap<>();
        controlRepository.findAllById(controlIds).forEach(c -> controlsById.put(c.getId(), c));

        Map<Long, String> effectiveness =
                linkRepository.findEffectivenessByControlIds(controlIds, tenantId);

        List<RiskResponse.LinkedControl> out = new ArrayList<>(links.size());
        for (RiskControlLink link : links) {
            AuditControl control = controlsById.get(link.getControlId());
            if (control == null) continue;   // control deleted out from under the link
            out.add(toLinkedControl(link, control, effectiveness));
        }
        return out;
    }

    private RiskResponse.LinkedControl toLinkedControl(RiskControlLink link,
                                                       AuditControl control,
                                                       Map<Long, String> effectiveness) {
        return RiskResponse.LinkedControl.builder()
                .linkId(link.getId())
                .controlId(control.getId())
                .controlCode(control.getControlCode())
                .name(control.getName())
                .frameworkRef(control.getFrameworkRef())
                .controlTag(control.getControlTag())
                .linkNote(link.getLinkNote())
                .effectiveness(effectiveness.getOrDefault(control.getId(), "NOT_TESTED"))
                .build();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // STATS
    // ═════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public Map<String, Object> getStats(Long tenantId) {
        Map<String, Object> stats = new LinkedHashMap<>();

        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (Object[] row : riskRepository.countByStatusForTenant(tenantId)) {
            byStatus.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        Map<String, Long> byCategory = new LinkedHashMap<>();
        for (Object[] row : riskRepository.countByCategoryForTenant(tenantId)) {
            byCategory.put(row[0] == null ? "UNCATEGORISED" : String.valueOf(row[0]),
                    ((Number) row[1]).longValue());
        }

        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        long open  = total - byStatus.getOrDefault(Risk.Status.CLOSED.name(), 0L);

        stats.put("total",           total);
        stats.put("open",            open);
        stats.put("byStatus",        byStatus);
        stats.put("byCategory",      byCategory);
        stats.put("reviewOverdue",   riskRepository.findOverdueForReview(tenantId, LocalDate.now()).size());
        return stats;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // MAPPING
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Full detail payload, including linked controls.
     * Used by the GET endpoints; the list endpoint maps inline in the
     * controller so that DbRepository's pagination is not bypassed.
     */
    public RiskResponse toResponse(Risk risk, Long tenantId, Long userId) {
        boolean library = risk.getTenantId() == null;

        List<RiskResponse.LinkedControl> controls =
                library ? List.of() : buildLinkedControls(risk.getId(), tenantId);

        return RiskResponse.builder()
                .id(risk.getId())
                .riskRef(risk.getRiskRef())
                .title(risk.getTitle())
                .description(risk.getDescription())
                .category(risk.getCategory())
                .riskSource(risk.getRiskSource())
                .status(risk.getStatus() != null ? risk.getStatus().name() : null)
                .inherentLikelihood(risk.getInherentLikelihood())
                .inherentImpact(risk.getInherentImpact())
                .inherentScore(risk.getInherentScore())
                .residualLikelihood(risk.getResidualLikelihood())
                .residualImpact(risk.getResidualImpact())
                .residualScore(risk.getResidualScore())
                .assessmentNotes(risk.getAssessmentNotes())
                .assessedAt(risk.getAssessedAt())
                .treatmentOption(risk.getTreatmentOption())
                .treatmentPlan(risk.getTreatmentPlan())
                .treatedAt(risk.getTreatedAt())
                .acceptanceJustification(risk.getAcceptanceJustification())
                .acceptedById(risk.getAcceptedById())
                .acceptedByName(resolveUserName(risk.getAcceptedById()))
                .acceptedAt(risk.getAcceptedAt())
                .ownerId(risk.getOwnerId())
                .ownerName(resolveUserName(risk.getOwnerId()))
                .ownerTeam(risk.getOwnerTeam())
                .controlTags(risk.getControlTags())
                .frameworkRefs(risk.getFrameworkRefs())
                .nextReviewDate(risk.getNextReviewDate())
                .reviewFrequencyMonths(risk.getReviewFrequencyMonths())
                .reviewOverdue(risk.getNextReviewDate() != null
                        && risk.getNextReviewDate().isBefore(LocalDate.now())
                        && risk.getStatus() != Risk.Status.CLOSED)
                .closedAt(risk.getClosedAt())
                .createdAt(risk.getCreatedAt())
                .updatedAt(risk.getUpdatedAt())
                .createdBy(risk.getCreatedBy())
                .workflowInstanceId(risk.getWorkflowInstanceId())
                .sourceRiskId(risk.getSourceRiskId())
                // GLOBAL / ORG, not LIBRARY / TENANT. This is the vocabulary
                // AuditPolicy and AuditLibraryController.buildControlMap already
                // use, and the one the origin filter in ModuleListView sends and
                // its All / Platform / Custom toggle renders. A third vocabulary
                // would have made the toggle match nothing.
                .origin(library ? "GLOBAL" : "ORG")
                .editable(!library)
                .isAssignedToCurrentUser(!library && isAssignedTo(risk, userId))
                .linkedControls(controls)
                .linkedControlCount(controls.size())
                .build();
    }

    /**
     * Backs ui_actions.requires_assignment on the risk detail screen.
     *
     * "Assigned" is read broadly on purpose: the owner, the person who raised
     * it, or anyone at all while the risk has no owner. A narrow reading (owner
     * only) would hide every action on every unowned risk, which is the state
     * all 28 adopted library rows start in — the register would ship inert.
     */
    private boolean isAssignedTo(Risk risk, Long userId) {
        if (userId == null) return false;
        if (risk.getOwnerId() == null) return true;
        return userId.equals(risk.getOwnerId()) || userId.equals(risk.getCreatedBy());
    }

    // ═════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * The tenant's own, undeleted risk — or a refusal.
     *
     * Library rows (tenant_id IS NULL) are refused with 403 rather than 404,
     * because the row demonstrably exists and pretending otherwise sends people
     * hunting for a bug. Another tenant's row gets 404, because its existence
     * is not theirs to learn.
     */
    private Risk requireOwnRisk(Long id, Long tenantId) {
        Risk risk = riskRepository.findById(id)
                .filter(r -> !r.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("Risk", id));

        if (risk.getTenantId() == null) {
            throw new ForbiddenException(
                    "This is a platform library risk and is read-only. Adopt it first — "
                            + "your organisation then works on its own copy.");
        }
        if (!tenantId.equals(risk.getTenantId())) {
            throw new ResourceNotFoundException("Risk", id);
        }
        return risk;
    }

    private void transition(Risk risk, Risk.Status target, Long userId) {
        Risk.Status current = risk.getStatus();
        Set<Risk.Status> legal = ALLOWED.getOrDefault(current, Set.of());
        if (!legal.contains(target)) {
            throw new ValidationException(
                    "Cannot move a risk from " + current + " to " + target + ". "
                            + (legal.isEmpty() ? "No transitions are available from " + current + "."
                            : "Allowed from " + current + ": " + legal + "."));
        }
        risk.setStatus(target);
        risk.setUpdatedBy(userId);
    }

    /** likelihood x impact, or null when either factor is missing. */
    private Integer score(Integer likelihood, Integer impact) {
        if (likelihood == null || impact == null) return null;
        return likelihood * impact;
    }

    private String resolveRef(String supplied, Long tenantId) {
        String trimmed = trimToNull(supplied);
        if (trimmed != null && !riskRepository.existsByRiskRefAndTenantId(trimmed, tenantId)) {
            return trimmed;
        }
        return buildRiskRef(tenantId);
    }

    private String buildRiskRef(Long tenantId) {
        long seq = riskRepository.nextRiskRefSequence(tenantId);
        String candidate = String.format("RSK-%d-%04d", LocalDate.now().getYear(), seq);
        // The sequence is a COUNT, so a deleted row makes it repeat. Walk
        // forward rather than fail the create on a duplicate reference.
        int guard = 0;
        while (riskRepository.existsByRiskRefAndTenantId(candidate, tenantId) && guard++ < 1000) {
            candidate = String.format("RSK-%d-%04d", LocalDate.now().getYear(), ++seq);
        }
        return candidate;
    }

    /**
     * Accepts "yyyy-MM-dd" (what a DATE field posts) or a full ISO datetime.
     * A blank string means "not supplied" and returns null, so an untouched
     * field never wipes a stored timestamp.
     */
    private LocalDateTime parseTemporal(String raw, String field) {
        if (isBlank(raw)) return null;
        String value = raw.trim();
        try {
            if (value.length() == 10) return LocalDate.parse(value).atStartOfDay();
            return LocalDateTime.parse(value.replace(" ", "T").substring(0,
                    Math.min(19, value.replace(" ", "T").length())));
        } catch (DateTimeParseException ex) {
            throw new ValidationException(
                    "Could not read " + field + " = '" + raw + "'. Expected a date (yyyy-MM-dd).");
        }
    }

    /**
     * Rejects a user id that is null, deleted, or not a usable member of this
     * tenant. Membership, not users.tenant_id — an external auditor's home
     * tenant is their firm, and checking the column would reject exactly the
     * people the guest model exists to admit.
     */
    private void requireTenantUser(Long userId, Long tenantId, String field) {
        if (userId == null) return;
        boolean ok = userRepository.findById(userId)
                .filter(u -> !u.isDeleted())
                .map(u -> tenantId.equals(u.getTenantId())
                        || membershipRepository.findByUserIdAndTenantId(userId, tenantId)
                        .map(m -> m.isUsable())
                        .orElse(false))
                .orElse(false);
        if (!ok) {
            throw new ValidationException(
                    field + "=" + userId + " is not an active user of this organization.");
        }
    }

    private String resolveUserName(Long userId) {
        if (userId == null) return null;
        return userRepository.findById(userId)
                .map(u -> {
                    String full = u.getFullName();
                    return (full != null && !full.isBlank()) ? full : u.getEmail();
                })
                .orElse(null);
    }

    /**
     * Workflow start is best-effort and never rolls back the risk.
     *
     * Unlike ISSUE this logs at debug when no workflow is configured, because a
     * risk register is genuinely useful without one and warning on every create
     * would train people to ignore the log.
     */
    private void startWorkflowIfConfigured(Risk risk, Long workflowId, Long initiatedBy, Long tenantId) {
        if (workflowId == null) {
            log.debug("[RISK] No workflowId supplied for riskId={} — no workflow started", risk.getId());
            return;
        }
        try {
            StartWorkflowRequest wfReq = new StartWorkflowRequest();
            wfReq.setWorkflowId(workflowId);
            wfReq.setEntityType("RISK");
            wfReq.setEntityId(risk.getId());
            wfReq.setPriority(risk.getInherentScore() != null && risk.getInherentScore() >= 15
                    ? "HIGH" : "MEDIUM");

            WorkflowInstanceResponse instance =
                    workflowEngineService.startWorkflow(wfReq, tenantId, initiatedBy);

            risk.setWorkflowInstanceId(instance.getId());
            riskRepository.save(risk);
            log.info("[RISK] Workflow started | ref={} | instanceId={}",
                    risk.getRiskRef(), instance.getId());
        } catch (Exception e) {
            log.error("[RISK] Workflow start failed | ref={} | error={}",
                    risk.getRiskRef(), e.getMessage(), e);
        }
    }

    private String appendNote(String existing, String addition) {
        String stamp = "[" + LocalDateTime.now() + "] " + addition;
        return isBlank(existing) ? stamp : existing + "\n" + stamp;
    }

    private static boolean isBlank(String s)      { return s == null || s.isBlank(); }
    private static String  nullToEmpty(String s)  { return s == null ? "" : s; }
    private static String  trimToNull(String s)   { return isBlank(s) ? null : s.trim(); }

    private static String firstNonBlank(String a, String b) {
        if (!isBlank(a)) return a;
        return isBlank(b) ? null : b;
    }
}