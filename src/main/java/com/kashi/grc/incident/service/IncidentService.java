package com.kashi.grc.incident.service;

import com.kashi.grc.asset.domain.Asset;
import com.kashi.grc.asset.repository.AssetRepository;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.exception.ValidationException;
import com.kashi.grc.incident.domain.*;
import com.kashi.grc.incident.dto.*;
import com.kashi.grc.incident.repository.*;
import com.kashi.grc.notification.service.NotificationService;
import com.kashi.grc.risk.domain.Risk;
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

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * IncidentService — NIST SP 800-61 lifecycle plus the regulatory clocks.
 *
 *   NEW -> TRIAGED -> INVESTIGATING -> CONTAINED -> ERADICATED -> RECOVERED -> CLOSED
 *   NEW | TRIAGED -> FALSE_POSITIVE
 *   CLOSED -> INVESTIGATING,  FALSE_POSITIVE -> TRIAGED   (reopen)
 *
 * The table below MUST stay identical to module_blueprints.status_flow_json
 * for INCIDENT and to allowed_statuses_json on each ui_actions row.
 *
 * ── THE REGULATORY CLOCK IS THE POINT OF THIS MODULE ──────────────────────
 * syncNotifications turns applicableFrameworks into IncidentNotification rows,
 * each with dueAt = detectedAt + the rule's dueHours. Ticking CERTIN is what
 * creates the six-hour deadline. Three rules govern it and none is negotiable:
 *
 *   1. dueAt is computed once and never recomputed. Editing a rule later must
 *      not move a deadline that has already been missed or met.
 *   2. A rule with dueHours NULL still creates a row, with no dueAt. SOC 2 has
 *      no statutory clock; recording applicability is not the same as owing a
 *      deadline, and NULL is not zero.
 *   3. Removing a framework deletes its row ONLY if notifiedAt is null. A
 *      notification carrying a reference number is evidence, and a checkbox
 *      must not be able to destroy it.
 *
 * ── PERMISSIONS ───────────────────────────────────────────────────────────
 * No @PreAuthorize, matching every other module controller here. incident:*
 * gate the buttons via ui_actions.required_permission. Tenancy, transition
 * legality and the closure evidence rules are enforced below.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IncidentService {

    private final IncidentRepository                   incidentRepository;
    private final IncidentNotificationRepository       notificationRepository;
    private final RegulatoryNotificationRuleRepository ruleRepository;
    private final IncidentAssetLinkRepository          assetLinkRepository;
    private final IncidentRiskLinkRepository           riskLinkRepository;
    private final AssetRepository                      assetRepository;
    private final RiskRepository                       riskRepository;
    private final UserRepository                       userRepository;
    private final UserTenantMembershipRepository       membershipRepository;
    private final NotificationService                  notificationService;
    private final WorkflowEngineService                workflowEngineService;

    /** Mirrors status_flow_json — see class javadoc. */
    private static final Map<Incident.Status, Set<Incident.Status>> ALLOWED = Map.of(
            Incident.Status.NEW,            EnumSet.of(Incident.Status.TRIAGED, Incident.Status.FALSE_POSITIVE),
            Incident.Status.TRIAGED,        EnumSet.of(Incident.Status.INVESTIGATING, Incident.Status.FALSE_POSITIVE),
            Incident.Status.INVESTIGATING,  EnumSet.of(Incident.Status.CONTAINED),
            Incident.Status.CONTAINED,      EnumSet.of(Incident.Status.ERADICATED),
            Incident.Status.ERADICATED,     EnumSet.of(Incident.Status.RECOVERED),
            Incident.Status.RECOVERED,      EnumSet.of(Incident.Status.CLOSED),
            Incident.Status.CLOSED,         EnumSet.of(Incident.Status.INVESTIGATING),
            Incident.Status.FALSE_POSITIVE, EnumSet.of(Incident.Status.TRIAGED)
    );

    /**
     * Internal response / resolution windows in hours, by severity.
     * Mirrors the values documented on Issue.Severity so the two modules do not
     * disagree about what CRITICAL means.
     */
    private static final Map<Incident.Severity, int[]> SLA_HOURS = Map.of(
            Incident.Severity.CRITICAL, new int[]{4,  72},
            Incident.Severity.HIGH,     new int[]{24, 720},
            Incident.Severity.MEDIUM,   new int[]{72, 2160},
            Incident.Severity.LOW,      new int[]{168, 4320}
    );

    // ═════════════════════════════════════════════════════════════════════════
    // CREATE
    // ═════════════════════════════════════════════════════════════════════════

    @Transactional
    public IncidentResponse create(IncidentRequest req, Long createdBy, Long tenantId) {

        if (req.getOwnerId()      != null) requireTenantUser(req.getOwnerId(), tenantId, "ownerId");
        if (req.getReportedById() != null) requireTenantUser(req.getReportedById(), tenantId, "reportedById");

        // detectedAt defaults to now rather than staying null. An incident with
        // no detection time has no provable regulatory deadline, and "when the
        // record was made" is a far better approximation than nothing — the
        // responder can correct it on the Response tab.
        LocalDateTime detected = parseTemporal(req.getDetectedAt(), "detectedAt");
        if (detected == null) detected = LocalDateTime.now();

        Incident.Severity severity = parseSeverity(req.getSeverity());

        Incident incident = Incident.builder()
                .tenantId(tenantId)
                .incidentRef(resolveRef(req.getIncidentRef(), tenantId))
                .title(req.getTitle())
                .description(req.getDescription())
                .incidentType(trimToNull(req.getIncidentType()))
                .severity(severity)
                .status(Incident.Status.NEW)
                .detectionSource(trimToNull(req.getDetectionSource()))
                .sourceModule(trimToNull(req.getSourceModule()))
                .sourceEntityType(trimToNull(req.getSourceEntityType()))
                .sourceEntityId(req.getSourceEntityId())
                .occurredAt(parseTemporal(req.getOccurredAt(), "occurredAt"))
                .detectedAt(detected)
                .ownerId(req.getOwnerId())
                .ownerTeam(trimToNull(req.getOwnerTeam()))
                .reportedById(req.getReportedById() != null ? req.getReportedById() : createdBy)
                .applicableFrameworks(joinList(req.getApplicableFrameworks()))
                .personalDataInvolved(Boolean.TRUE.equals(req.getPersonalDataInvolved()))
                .personalDataCategories(trimToNull(req.getPersonalDataCategories()))
                .dataPrincipalsAffected(req.getDataPrincipalsAffected())
                .impactSummary(req.getImpactSummary())
                .affectedServices(trimToNull(req.getAffectedServices()))
                .controlTags(trimToNull(req.getControlTags()))
                .frameworkRefs(trimToNull(req.getFrameworkRefs()))
                .createdBy(createdBy)
                .build();

        applySlaWindows(incident);
        incidentRepository.save(incident);

        syncNotifications(incident, tenantId, createdBy);

        log.info("[INCIDENT] Reported | ref={} | severity={} | detectedAt={} | tenantId={}",
                incident.getIncidentRef(), severity, detected, tenantId);

        startWorkflowIfConfigured(incident, req.getWorkflowId(), createdBy, tenantId);
        notifyOwner(incident, createdBy, "INCIDENT_ASSIGNED",
                "Incident " + incident.getIncidentRef() + " has been assigned to you");

        return toResponse(incident, tenantId);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // READ
    // ═════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public IncidentResponse getById(Long id, Long tenantId) {
        return toResponse(requireOwn(id, tenantId), tenantId);
    }

    @Transactional(readOnly = true)
    public List<IncidentResponse.Notification> listNotifications(Long incidentId, Long tenantId) {
        requireOwn(incidentId, tenantId);
        return buildNotifications(incidentId, tenantId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listLinkedAssets(Long incidentId, Long tenantId) {
        requireOwn(incidentId, tenantId);
        List<IncidentAssetLink> links = assetLinkRepository.findByIncidentIdAndTenantId(incidentId, tenantId);
        if (links.isEmpty()) return List.of();

        Map<Long, Asset> byId = new HashMap<>();
        assetRepository.findAllById(links.stream().map(IncidentAssetLink::getAssetId).toList())
                .forEach(a -> byId.put(a.getId(), a));

        List<Map<String, Object>> out = new ArrayList<>(links.size());
        for (IncidentAssetLink l : links) {
            Asset a = byId.get(l.getAssetId());
            if (a == null || a.isDeleted()) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("linkId", l.getId());
            m.put("id", a.getId());
            m.put("ref", a.getAssetRef());
            m.put("title", a.getName());
            m.put("status", a.getStatus() != null ? a.getStatus().name() : null);
            m.put("badge", a.getCriticality());
            m.put("linkNote", l.getImpactNote());
            m.put("navEntityType", "asset");
            out.add(m);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listLinkedRisks(Long incidentId, Long tenantId) {
        requireOwn(incidentId, tenantId);
        List<IncidentRiskLink> links = riskLinkRepository.findByIncidentIdAndTenantId(incidentId, tenantId);
        if (links.isEmpty()) return List.of();

        Map<Long, Risk> byId = new HashMap<>();
        riskRepository.findAllById(links.stream().map(IncidentRiskLink::getRiskId).toList())
                .forEach(r -> byId.put(r.getId(), r));

        List<Map<String, Object>> out = new ArrayList<>(links.size());
        for (IncidentRiskLink l : links) {
            Risk r = byId.get(l.getRiskId());
            if (r == null || r.isDeleted()) continue;
            Integer score = r.getResidualScore() != null ? r.getResidualScore() : r.getInherentScore();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("linkId", l.getId());
            m.put("id", r.getId());
            m.put("ref", r.getRiskRef());
            m.put("title", r.getTitle());
            m.put("status", r.getStatus() != null ? r.getStatus().name() : null);
            m.put("badge", score != null ? "Score " + score : null);
            m.put("linkNote", l.getLinkNote());
            m.put("navEntityType", "risk");
            out.add(m);
        }
        return out;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // UPDATE
    // ═════════════════════════════════════════════════════════════════════════

    @Transactional
    public IncidentResponse update(Long id, IncidentRequest req, Long userId, Long tenantId) {
        Incident inc = requireEditable(id, tenantId);

        if (req.getStatus() != null && !req.getStatus().isBlank()
                && !req.getStatus().equalsIgnoreCase(inc.getStatus().name())) {
            throw new ValidationException(
                    "Status cannot be changed by editing the incident. Use the response "
                            + "actions, which record who changed it and when.");
        }
        if (req.getOwnerId() != null && !req.getOwnerId().equals(inc.getOwnerId())) {
            requireTenantUser(req.getOwnerId(), tenantId, "ownerId");
        }
        if (req.getReportedById() != null && !req.getReportedById().equals(inc.getReportedById())) {
            requireTenantUser(req.getReportedById(), tenantId, "reportedById");
        }

        if (req.getTitle()            != null) inc.setTitle(req.getTitle());
        if (req.getDescription()      != null) inc.setDescription(req.getDescription());
        if (req.getIncidentType()     != null) inc.setIncidentType(trimToNull(req.getIncidentType()));
        if (req.getDetectionSource()  != null) inc.setDetectionSource(trimToNull(req.getDetectionSource()));
        if (req.getSourceModule()     != null) inc.setSourceModule(trimToNull(req.getSourceModule()));
        if (req.getOwnerId()          != null) inc.setOwnerId(req.getOwnerId());
        if (req.getOwnerTeam()        != null) inc.setOwnerTeam(trimToNull(req.getOwnerTeam()));
        if (req.getReportedById()     != null) inc.setReportedById(req.getReportedById());
        if (req.getImpactSummary()    != null) inc.setImpactSummary(req.getImpactSummary());
        if (req.getAffectedServices() != null) inc.setAffectedServices(trimToNull(req.getAffectedServices()));
        if (req.getControlTags()      != null) inc.setControlTags(trimToNull(req.getControlTags()));
        if (req.getFrameworkRefs()    != null) inc.setFrameworkRefs(trimToNull(req.getFrameworkRefs()));

        // Severity drives the internal SLA, so changing it recalculates both
        // windows. Announced in the field's helper text, because silently
        // moving a deadline would be worse than not moving it.
        if (req.getSeverity() != null && !req.getSeverity().isBlank()) {
            Incident.Severity newSeverity = parseSeverity(req.getSeverity());
            if (newSeverity != inc.getSeverity()) {
                inc.setSeverity(newSeverity);
                applySlaWindows(inc);
                log.info("[INCIDENT] Severity changed | id={} | -> {} | SLA recalculated", id, newSeverity);
            }
        }

        String newRef = trimToNull(req.getIncidentRef());
        if (newRef != null && !newRef.equals(inc.getIncidentRef())) {
            if (incidentRepository.existsByIncidentRefAndTenantId(newRef, tenantId)) {
                throw new ValidationException("Incident reference " + newRef + " is already in use.");
            }
            inc.setIncidentRef(newRef);
        }

        if (req.getApplicableFrameworks() != null) {
            inc.setApplicableFrameworks(joinList(req.getApplicableFrameworks()));
        }

        inc.setUpdatedBy(userId);
        incidentRepository.save(inc);

        if (req.getApplicableFrameworks() != null) syncNotifications(inc, tenantId, userId);
        return toResponse(inc, tenantId);
    }

    @Transactional
    public IncidentResponse saveResponse(Long id, IncidentResponseActionsRequest req,
                                         Long userId, Long tenantId) {
        Incident inc = requireEditable(id, tenantId);

        LocalDateTime occurred = parseTemporal(req.getOccurredAt(), "occurredAt");
        LocalDateTime detected = parseTemporal(req.getDetectedAt(), "detectedAt");
        LocalDateTime triaged  = parseTemporal(req.getTriagedAt(),  "triagedAt");

        if (occurred != null) inc.setOccurredAt(occurred);
        if (triaged  != null) inc.setTriagedAt(triaged);

        // Correcting detectedAt moves every regulatory deadline that has not
        // yet been met. That is intended — the clock runs from when the
        // organisation actually noticed — but it is logged loudly, because an
        // edit that quietly buys six more hours is exactly what an auditor
        // will look for.
        if (detected != null && !detected.equals(inc.getDetectedAt())) {
            log.warn("[INCIDENT] detectedAt corrected | id={} | {} -> {} | by={} | "
                            + "outstanding regulatory deadlines recalculated",
                    id, inc.getDetectedAt(), detected, userId);
            inc.setDetectedAt(detected);
            recomputeOutstandingDeadlines(inc, tenantId);
        }

        if (req.getContainmentActions() != null) inc.setContainmentActions(req.getContainmentActions());
        if (req.getEradicationActions() != null) inc.setEradicationActions(req.getEradicationActions());
        if (req.getRecoveryActions()    != null) inc.setRecoveryActions(req.getRecoveryActions());
        if (req.getRootCause()          != null) inc.setRootCause(req.getRootCause());
        if (req.getLessonsLearned()     != null) inc.setLessonsLearned(req.getLessonsLearned());

        inc.setUpdatedBy(userId);
        incidentRepository.save(inc);
        return toResponse(inc, tenantId);
    }

    @Transactional
    public IncidentResponse saveRegulatory(Long id, IncidentRegulatoryRequest req,
                                           Long userId, Long tenantId) {
        Incident inc = requireEditable(id, tenantId);

        if (req.getPersonalDataInvolved() != null)
            inc.setPersonalDataInvolved(req.getPersonalDataInvolved());

        // depends_on_json hides these unless personalDataInvolved is ticked.
        // That is a display rule; a hidden field is still submittable.
        if (inc.isPersonalDataInvolved()) {
            if (req.getPersonalDataCategories() != null)
                inc.setPersonalDataCategories(trimToNull(req.getPersonalDataCategories()));
            if (req.getDataPrincipalsAffected() != null)
                inc.setDataPrincipalsAffected(req.getDataPrincipalsAffected());
        } else {
            inc.setPersonalDataCategories(null);
            inc.setDataPrincipalsAffected(null);
        }

        if (req.getImpactSummary() != null) inc.setImpactSummary(req.getImpactSummary());
        if (req.getApplicableFrameworks() != null)
            inc.setApplicableFrameworks(joinList(req.getApplicableFrameworks()));

        inc.setUpdatedBy(userId);
        incidentRepository.save(inc);

        if (req.getApplicableFrameworks() != null) syncNotifications(inc, tenantId, userId);
        return toResponse(inc, tenantId);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // REGULATORY CLOCKS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Brings IncidentNotification rows into line with applicableFrameworks.
     * See the three rules in the class javadoc — none of them is negotiable.
     */
    private void syncNotifications(Incident inc, Long tenantId, Long userId) {
        Set<String> wanted = splitToSet(inc.getApplicableFrameworks());
        Map<String, RegulatoryNotificationRule> rules = resolveRules(tenantId);

        List<IncidentNotification> existing =
                notificationRepository.findByIncidentIdAndTenantId(inc.getId(), tenantId);
        Map<String, IncidentNotification> byRef = existing.stream()
                .collect(Collectors.toMap(IncidentNotification::getFrameworkRef, n -> n, (a, b) -> a));

        for (String ref : wanted) {
            if (byRef.containsKey(ref)) continue;
            RegulatoryNotificationRule rule = rules.get(ref);
            if (rule == null) {
                // A framework with no rule is not an error — it just has no
                // authority or deadline. Recording it still answers "which
                // regimes did we consider".
                log.debug("[INCIDENT] No notification rule for {} — row created without a clock", ref);
            }
            notificationRepository.save(IncidentNotification.builder()
                    .tenantId(tenantId)
                    .incidentId(inc.getId())
                    .frameworkRef(ref)
                    .authorityName(rule != null ? rule.getAuthorityName() : null)
                    .dueAt(computeDueAt(inc.getDetectedAt(), rule))
                    .createdBy(userId)
                    .build());
        }

        for (IncidentNotification n : existing) {
            if (wanted.contains(n.getFrameworkRef())) continue;
            if (n.getNotifiedAt() != null) {
                // Rule 3. Evidence survives a checkbox.
                log.info("[INCIDENT] {} removed from applicable regimes but its notification "
                        + "is retained — already reported at {}", n.getFrameworkRef(), n.getNotifiedAt());
                continue;
            }
            notificationRepository.delete(n);
        }
    }

    /**
     * Only moves deadlines that are still outstanding. A notification already
     * made has a due date that is part of the record, and rewriting it would
     * change what the evidence says.
     */
    private void recomputeOutstandingDeadlines(Incident inc, Long tenantId) {
        Map<String, RegulatoryNotificationRule> rules = resolveRules(tenantId);
        for (IncidentNotification n : notificationRepository
                .findByIncidentIdAndTenantId(inc.getId(), tenantId)) {
            if (n.getNotifiedAt() != null) continue;
            n.setDueAt(computeDueAt(inc.getDetectedAt(), rules.get(n.getFrameworkRef())));
            notificationRepository.save(n);
        }
    }

    private LocalDateTime computeDueAt(LocalDateTime detectedAt, RegulatoryNotificationRule rule) {
        if (detectedAt == null || rule == null || rule.getDueHours() == null) return null;
        return detectedAt.plusHours(rule.getDueHours());
    }

    /** Platform rules, with this tenant's overrides taking precedence. */
    private Map<String, RegulatoryNotificationRule> resolveRules(Long tenantId) {
        Map<String, RegulatoryNotificationRule> out = new HashMap<>();
        ruleRepository.findByIsActiveTrueAndTenantIdIsNull()
                .forEach(r -> out.put(r.getFrameworkRef(), r));
        ruleRepository.findByIsActiveTrueAndTenantId(tenantId)
                .forEach(r -> out.put(r.getFrameworkRef(), r));
        return out;
    }

    /**
     * Records that a regime was notified. Upsert by (incidentId, frameworkRef),
     * so this both fills in a row the sync created and adds one for a regulator
     * nobody listed until mid-response.
     */
    @Transactional
    public IncidentResponse.Notification recordNotification(Long id, IncidentNotificationRequest req,
                                                            Long userId, Long tenantId) {
        Incident inc = requireOwn(id, tenantId);
        String ref = req.getFrameworkRef().trim().toUpperCase();

        LocalDateTime notifiedAt = parseTemporal(req.getNotifiedAt(), "notifiedAt");
        if (notifiedAt == null) notifiedAt = LocalDateTime.now();

        IncidentNotification n = notificationRepository
                .findByIncidentIdAndFrameworkRef(inc.getId(), ref)
                .orElseGet(() -> {
                    RegulatoryNotificationRule rule = resolveRules(tenantId).get(ref);
                    return IncidentNotification.builder()
                            .tenantId(tenantId)
                            .incidentId(inc.getId())
                            .frameworkRef(ref)
                            .authorityName(rule != null ? rule.getAuthorityName() : null)
                            .dueAt(computeDueAt(inc.getDetectedAt(), rule))
                            .createdBy(userId)
                            .build();
                });

        n.setNotifiedAt(notifiedAt);
        n.setNotifiedBy(userId);
        if (req.getReference() != null) n.setReference(trimToNull(req.getReference()));
        if (req.getNotes()     != null) n.setNotes(req.getNotes());
        notificationRepository.save(n);

        // Keep the incident's own list honest: reporting to a regulator that
        // was not listed makes it applicable by definition.
        Set<String> frameworks = splitToSet(inc.getApplicableFrameworks());
        if (frameworks.add(ref)) {
            inc.setApplicableFrameworks(String.join(",", frameworks));
            incidentRepository.save(inc);
        }

        boolean late = n.getDueAt() != null && notifiedAt.isAfter(n.getDueAt());
        log.info("[INCIDENT] Notification recorded | id={} | {} | at={} | due={} | {}",
                id, ref, notifiedAt, n.getDueAt(), late ? "LATE" : "within window");

        return toNotification(n);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═════════════════════════════════════════════════════════════════════════

    @Transactional
    public IncidentResponse triage(Long id, Long userId, Long tenantId) {
        Incident inc = requireEditable(id, tenantId);
        transition(inc, Incident.Status.TRIAGED, userId);
        if (inc.getTriagedAt() == null) inc.setTriagedAt(LocalDateTime.now());
        incidentRepository.save(inc);
        log.info("[INCIDENT] Triaged | id={} | by={}", id, userId);
        return toResponse(inc, tenantId);
    }

    @Transactional
    public IncidentResponse investigate(Long id, Long userId, Long tenantId) {
        Incident inc = requireEditable(id, tenantId);
        transition(inc, Incident.Status.INVESTIGATING, userId);
        incidentRepository.save(inc);
        return toResponse(inc, tenantId);
    }

    @Transactional
    public IncidentResponse contain(Long id, String remarks, Long userId, Long tenantId) {
        Incident inc = requireEditable(id, tenantId);
        transition(inc, Incident.Status.CONTAINED, userId);
        inc.setContainedAt(LocalDateTime.now());
        if (!isBlank(remarks))
            inc.setContainmentActions(append(inc.getContainmentActions(), remarks));
        incidentRepository.save(inc);
        log.info("[INCIDENT] Contained | id={} | by={}", id, userId);
        return toResponse(inc, tenantId);
    }

    @Transactional
    public IncidentResponse eradicate(Long id, String remarks, Long userId, Long tenantId) {
        Incident inc = requireEditable(id, tenantId);
        transition(inc, Incident.Status.ERADICATED, userId);
        inc.setEradicatedAt(LocalDateTime.now());
        if (!isBlank(remarks))
            inc.setEradicationActions(append(inc.getEradicationActions(), remarks));
        incidentRepository.save(inc);
        return toResponse(inc, tenantId);
    }

    @Transactional
    public IncidentResponse recover(Long id, String remarks, Long userId, Long tenantId) {
        Incident inc = requireEditable(id, tenantId);
        transition(inc, Incident.Status.RECOVERED, userId);
        inc.setRecoveredAt(LocalDateTime.now());
        if (!isBlank(remarks))
            inc.setRecoveryActions(append(inc.getRecoveryActions(), remarks));
        incidentRepository.save(inc);
        return toResponse(inc, tenantId);
    }

    /**
     * Closure requires a root cause and lessons learned — IRP-01.4, and the
     * thing that distinguishes an incident log from a ticket queue. It also
     * refuses while a regulatory deadline is outstanding and unreported:
     * closing an incident you still owe CERT-In a report on is not a state the
     * register should be able to represent.
     */
    @Transactional
    public IncidentResponse close(Long id, String remarks, Long userId, Long tenantId) {
        Incident inc = requireEditable(id, tenantId);

        if (isBlank(inc.getRootCause())) {
            throw new ValidationException(
                    "Record a root cause on the Response tab before closing.");
        }
        if (isBlank(inc.getLessonsLearned())) {
            throw new ValidationException(
                    "Record lessons learned on the Response tab before closing. "
                            + "A closed incident that taught nobody anything is the finding.");
        }

        List<IncidentNotification> outstanding = notificationRepository
                .findByIncidentIdAndTenantId(id, tenantId).stream()
                .filter(n -> n.getNotifiedAt() == null && n.getDueAt() != null)
                .toList();
        if (!outstanding.isEmpty()) {
            throw new ValidationException(
                    "Record the outstanding regulatory notification(s) before closing: "
                            + outstanding.stream().map(IncidentNotification::getFrameworkRef)
                                .collect(Collectors.joining(", "))
                            + ". Use Record notification, or remove the regime on the Regulatory tab "
                            + "if it does not actually apply.");
        }

        transition(inc, Incident.Status.CLOSED, userId);
        inc.setClosedAt(LocalDateTime.now());
        if (inc.getPirCompletedAt() == null) inc.setPirCompletedAt(LocalDateTime.now());
        if (!isBlank(remarks))
            inc.setLessonsLearned(append(inc.getLessonsLearned(), "Closing note: " + remarks));
        incidentRepository.save(inc);
        log.info("[INCIDENT] Closed | id={} | by={}", id, userId);
        return toResponse(inc, tenantId);
    }

    @Transactional
    public IncidentResponse reopen(Long id, String remarks, Long userId, Long tenantId) {
        Incident inc = requireOwn(id, tenantId);   // NOT requireEditable — closed is the point
        Incident.Status target = inc.getStatus() == Incident.Status.FALSE_POSITIVE
                ? Incident.Status.TRIAGED : Incident.Status.INVESTIGATING;
        transition(inc, target, userId);
        inc.setClosedAt(null);
        if (!isBlank(remarks))
            inc.setDescription(append(inc.getDescription(), "Reopened: " + remarks));
        incidentRepository.save(inc);
        log.info("[INCIDENT] Reopened | id={} | -> {} | by={}", id, target, userId);
        return toResponse(inc, tenantId);
    }

    @Transactional
    public IncidentResponse markFalsePositive(Long id, String remarks, Long userId, Long tenantId) {
        Incident inc = requireEditable(id, tenantId);
        transition(inc, Incident.Status.FALSE_POSITIVE, userId);
        inc.setClosedAt(LocalDateTime.now());
        if (!isBlank(remarks))
            inc.setDescription(append(inc.getDescription(), "False positive: " + remarks));

        // Unreported clocks stop mattering the moment it is not an incident.
        // Reported ones stay: you cannot un-tell a regulator.
        notificationRepository.findByIncidentIdAndTenantId(id, tenantId).stream()
                .filter(n -> n.getNotifiedAt() == null)
                .forEach(notificationRepository::delete);

        incidentRepository.save(inc);
        log.info("[INCIDENT] False positive | id={} | by={}", id, userId);
        return toResponse(inc, tenantId);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // LINKAGE
    // ═════════════════════════════════════════════════════════════════════════

    @Transactional
    public void linkAsset(Long incidentId, IncidentLinkRequest req, Long userId, Long tenantId) {
        Incident inc = requireEditable(incidentId, tenantId);
        if (req.getAssetId() == null) throw new ValidationException("assetId is required.");

        Asset asset = assetRepository
                .findByIdAndTenantIdAndIsDeletedFalse(req.getAssetId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Asset", req.getAssetId()));

        if (assetLinkRepository.existsByIncidentIdAndAssetId(inc.getId(), asset.getId())) {
            throw new ValidationException("That asset is already linked to this incident.");
        }
        assetLinkRepository.save(IncidentAssetLink.builder()
                .tenantId(tenantId).incidentId(inc.getId()).assetId(asset.getId())
                .impactNote(trimToNull(req.getNote())).createdBy(userId).build());
        log.info("[INCIDENT] Asset linked | incidentId={} assetId={}", incidentId, asset.getId());
    }

    @Transactional
    public void unlinkAsset(Long incidentId, Long assetId, Long tenantId) {
        requireEditable(incidentId, tenantId);
        IncidentAssetLink l = assetLinkRepository.findByIncidentIdAndAssetId(incidentId, assetId)
                .filter(x -> tenantId.equals(x.getTenantId()))
                .orElseThrow(() -> new ResourceNotFoundException("IncidentAssetLink", "assetId", assetId));
        assetLinkRepository.delete(l);
    }

    @Transactional
    public void linkRisk(Long incidentId, IncidentLinkRequest req, Long userId, Long tenantId) {
        Incident inc = requireEditable(incidentId, tenantId);
        if (req.getRiskId() == null) throw new ValidationException("riskId is required.");

        // The tenant's OWN risk. A platform library row belongs to no tenant
        // and cannot have materialised for anyone.
        Risk risk = riskRepository.findByIdAndTenantId(req.getRiskId(), tenantId)
                .filter(r -> !r.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("Risk", req.getRiskId()));

        if (riskLinkRepository.existsByIncidentIdAndRiskId(inc.getId(), risk.getId())) {
            throw new ValidationException("That risk is already linked to this incident.");
        }
        riskLinkRepository.save(IncidentRiskLink.builder()
                .tenantId(tenantId).incidentId(inc.getId()).riskId(risk.getId())
                .linkNote(trimToNull(req.getNote())).createdBy(userId).build());
        log.info("[INCIDENT] Risk linked | incidentId={} riskId={} — a risk that materialised",
                incidentId, risk.getId());
    }

    @Transactional
    public void unlinkRisk(Long incidentId, Long riskId, Long tenantId) {
        requireEditable(incidentId, tenantId);
        IncidentRiskLink l = riskLinkRepository.findByIncidentIdAndRiskId(incidentId, riskId)
                .filter(x -> tenantId.equals(x.getTenantId()))
                .orElseThrow(() -> new ResourceNotFoundException("IncidentRiskLink", "riskId", riskId));
        riskLinkRepository.delete(l);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // STATS
    // ═════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public Map<String, Object> getStats(Long tenantId) {
        Map<String, Object> stats = new LinkedHashMap<>();
        Map<String, Long> byStatus   = grouped(incidentRepository.countByStatusForTenant(tenantId));
        Map<String, Long> bySeverity = grouped(incidentRepository.countBySeverityForTenant(tenantId));
        Map<String, Long> byType     = grouped(incidentRepository.countByTypeForTenant(tenantId));

        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        long open  = total
                - byStatus.getOrDefault(Incident.Status.CLOSED.name(), 0L)
                - byStatus.getOrDefault(Incident.Status.FALSE_POSITIVE.name(), 0L);

        Double[] means = incidentRepository.meanTimesInHours(tenantId);

        stats.put("total",      total);
        stats.put("open",       open);
        stats.put("byStatus",   byStatus);
        stats.put("bySeverity", bySeverity);
        stats.put("byType",     byType);
        stats.put("slaBreached", incidentRepository.findSlaBreached(tenantId).size());
        // The headline regulatory number: unreported and past the deadline,
        // across every regime at once.
        stats.put("reportingOverdue", notificationRepository
                .findByTenantIdAndNotifiedAtIsNullAndDueAtBefore(tenantId, LocalDateTime.now()).size());
        stats.put("meanHoursToDetect",  means[0]);
        stats.put("meanHoursToContain", means[1]);
        return stats;
    }

    private Map<String, Long> grouped(List<Object[]> rows) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Object[] r : rows) {
            out.put(r[0] == null ? "UNSET" : String.valueOf(r[0]), ((Number) r[1]).longValue());
        }
        return out;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // MAPPING
    // ═════════════════════════════════════════════════════════════════════════

    public IncidentResponse toResponse(Incident inc, Long tenantId) {
        List<IncidentResponse.Notification> notifications = buildNotifications(inc.getId(), tenantId);

        LocalDateTime earliestDue = notifications.stream()
                .filter(n -> n.getNotifiedAt() == null && n.getDueAt() != null)
                .map(IncidentResponse.Notification::getDueAt)
                .min(LocalDateTime::compareTo).orElse(null);
        boolean overdue = earliestDue != null && earliestDue.isBefore(LocalDateTime.now());

        return IncidentResponse.builder()
                .id(inc.getId())
                .incidentRef(inc.getIncidentRef())
                .title(inc.getTitle())
                .description(inc.getDescription())
                .incidentType(inc.getIncidentType())
                .severity(inc.getSeverity() != null ? inc.getSeverity().name() : null)
                .status(inc.getStatus() != null ? inc.getStatus().name() : null)
                .sourceModule(inc.getSourceModule())
                .sourceEntityType(inc.getSourceEntityType())
                .sourceEntityId(inc.getSourceEntityId())
                .detectionSource(inc.getDetectionSource())
                .occurredAt(inc.getOccurredAt())
                .detectedAt(inc.getDetectedAt())
                .triagedAt(inc.getTriagedAt())
                .containedAt(inc.getContainedAt())
                .eradicatedAt(inc.getEradicatedAt())
                .recoveredAt(inc.getRecoveredAt())
                .closedAt(inc.getClosedAt())
                .timeToDetectHours(hoursBetween(inc.getOccurredAt(), inc.getDetectedAt()))
                .timeToContainHours(hoursBetween(inc.getDetectedAt(), inc.getContainedAt()))
                .responseDueAt(inc.getResponseDueAt())
                .resolutionDueAt(inc.getResolutionDueAt())
                .slaBreached(inc.isSlaBreached())
                // List, not a comma string — the MULTI_SELECT renderer needs one.
                .applicableFrameworks(new ArrayList<>(splitToSet(inc.getApplicableFrameworks())))
                .personalDataInvolved(inc.isPersonalDataInvolved())
                .personalDataCategories(inc.getPersonalDataCategories())
                .dataPrincipalsAffected(inc.getDataPrincipalsAffected())
                .reportingDueAt(earliestDue)
                .reportingOverdue(overdue)
                .notificationsOutstanding((int) notifications.stream()
                        .filter(n -> n.getNotifiedAt() == null).count())
                .impactSummary(inc.getImpactSummary())
                .affectedServices(inc.getAffectedServices())
                .containmentActions(inc.getContainmentActions())
                .eradicationActions(inc.getEradicationActions())
                .recoveryActions(inc.getRecoveryActions())
                .rootCause(inc.getRootCause())
                .lessonsLearned(inc.getLessonsLearned())
                .pirCompletedAt(inc.getPirCompletedAt())
                .ownerId(inc.getOwnerId())
                .ownerName(resolveUserName(inc.getOwnerId()))
                .ownerTeam(inc.getOwnerTeam())
                .reportedById(inc.getReportedById())
                .reportedByName(resolveUserName(inc.getReportedById()))
                .controlTags(inc.getControlTags())
                .frameworkRefs(inc.getFrameworkRefs())
                .workflowInstanceId(inc.getWorkflowInstanceId())
                .createdAt(inc.getCreatedAt())
                .updatedAt(inc.getUpdatedAt())
                .createdBy(inc.getCreatedBy())
                .editable(inc.getStatus() != Incident.Status.CLOSED)
                .notifications(notifications)
                .build();
    }

    private List<IncidentResponse.Notification> buildNotifications(Long incidentId, Long tenantId) {
        return notificationRepository.findByIncidentIdAndTenantId(incidentId, tenantId).stream()
                .map(this::toNotification)
                .toList();
    }

    private IncidentResponse.Notification toNotification(IncidentNotification n) {
        String status;
        String badge;
        if (n.getNotifiedAt() != null) {
            status = "REPORTED";
            badge  = "Reported " + n.getNotifiedAt().toLocalDate();
        } else if (n.getDueAt() == null) {
            status = "NO_DEADLINE";
            badge  = "No statutory clock";
        } else if (n.getDueAt().isBefore(LocalDateTime.now())) {
            status = "OVERDUE";
            badge  = "Was due " + n.getDueAt();
        } else {
            status = "DUE";
            badge  = "Due " + n.getDueAt();
        }
        String note = buildLinkNote(n.getReference(), n.getNotes());

        return IncidentResponse.Notification.builder()
                .id(n.getId())
                .ref(n.getFrameworkRef())
                .title(n.getAuthorityName() != null ? n.getAuthorityName() : n.getFrameworkRef())
                .status(status)
                .badge(badge)
                .linkNote(note)
                .dueAt(n.getDueAt())
                .notifiedAt(n.getNotifiedAt())
                .reference(n.getReference())
                .build();
    }

    /** "Ref CERTIN/2026/00412 — filed by the SOC lead" */
    private String buildLinkNote(String reference, String notes) {
        List<String> parts = new ArrayList<>();
        if (!isBlank(reference)) parts.add("Ref " + reference);
        if (!isBlank(notes))     parts.add(notes);
        return parts.isEmpty() ? null : String.join(" — ", parts);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    public Incident requireOwn(Long id, Long tenantId) {
        return incidentRepository.findByIdAndTenantIdAndIsDeletedFalse(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Incident", id));
    }

    /**
     * Refuses a CLOSED incident. A closed incident is evidence; reopen it if it
     * genuinely needs to change, which records who decided that and when.
     * FALSE_POSITIVE stays editable — it may well need correcting.
     */
    private Incident requireEditable(Long id, Long tenantId) {
        Incident inc = requireOwn(id, tenantId);
        if (inc.getStatus() == Incident.Status.CLOSED) {
            throw new ValidationException(
                    "This incident is closed and its record is read-only. Reopen it to make changes.");
        }
        return inc;
    }

    private void transition(Incident inc, Incident.Status target, Long userId) {
        Incident.Status current = inc.getStatus();
        Set<Incident.Status> legal = ALLOWED.getOrDefault(current, Set.of());
        if (!legal.contains(target)) {
            throw new ValidationException(
                    "Cannot move an incident from " + current + " to " + target + ". "
                            + (legal.isEmpty() ? current + " is a terminal state."
                                               : "Allowed from " + current + ": " + legal + "."));
        }
        inc.setStatus(target);
        inc.setUpdatedBy(userId);
    }

    private void applySlaWindows(Incident inc) {
        int[] hours = SLA_HOURS.getOrDefault(inc.getSeverity(), SLA_HOURS.get(Incident.Severity.MEDIUM));
        LocalDateTime from = inc.getDetectedAt() != null ? inc.getDetectedAt() : LocalDateTime.now();
        inc.setResponseDueAt(from.plusHours(hours[0]));
        inc.setResolutionDueAt(from.plusHours(hours[1]));
    }

    private Incident.Severity parseSeverity(String raw) {
        if (isBlank(raw)) return Incident.Severity.MEDIUM;
        try {
            return Incident.Severity.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ValidationException("Unknown severity '" + raw
                    + "'. Expected CRITICAL, HIGH, MEDIUM or LOW.");
        }
    }

    private String resolveRef(String supplied, Long tenantId) {
        String trimmed = trimToNull(supplied);
        if (trimmed != null && !incidentRepository.existsByIncidentRefAndTenantId(trimmed, tenantId)) {
            return trimmed;
        }
        long seq = incidentRepository.nextIncidentRefSequence(tenantId);
        String candidate = String.format("INC-%d-%04d", LocalDate.now().getYear(), seq);
        int guard = 0;
        while (incidentRepository.existsByIncidentRefAndTenantId(candidate, tenantId) && guard++ < 1000) {
            candidate = String.format("INC-%d-%04d", LocalDate.now().getYear(), ++seq);
        }
        return candidate;
    }

    private void notifyOwner(Incident inc, Long actorId, String type, String message) {
        if (inc.getOwnerId() == null || inc.getOwnerId().equals(actorId)) return;
        notificationService.send(inc.getOwnerId(), type, message, "INCIDENT", inc.getId());
    }

    private void startWorkflowIfConfigured(Incident inc, Long workflowId, Long initiatedBy, Long tenantId) {
        if (workflowId == null) {
            log.debug("[INCIDENT] No workflowId supplied for id={} — no workflow started", inc.getId());
            return;
        }
        try {
            StartWorkflowRequest wf = new StartWorkflowRequest();
            wf.setWorkflowId(workflowId);
            wf.setEntityType("INCIDENT");
            wf.setEntityId(inc.getId());
            wf.setPriority(inc.getSeverity() == Incident.Severity.CRITICAL ? "HIGH" : "MEDIUM");
            WorkflowInstanceResponse instance =
                    workflowEngineService.startWorkflow(wf, tenantId, initiatedBy);
            inc.setWorkflowInstanceId(instance.getId());
            incidentRepository.save(inc);
        } catch (Exception e) {
            log.error("[INCIDENT] Workflow start failed | ref={} | {}",
                    inc.getIncidentRef(), e.getMessage(), e);
        }
    }

    private void requireTenantUser(Long userId, Long tenantId, String field) {
        if (userId == null) return;
        boolean ok = userRepository.findById(userId)
                .filter(u -> !u.isDeleted())
                .map(u -> tenantId.equals(u.getTenantId())
                        || membershipRepository.findByUserIdAndTenantId(userId, tenantId)
                        .map(m -> m.isUsable()).orElse(false))
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

    private Double hoursBetween(LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null || to.isBefore(from)) return null;
        return Math.round(Duration.between(from, to).toMinutes() / 6.0) / 10.0;
    }

    /** MULTI_SELECT arrives as an array; the column is a comma-separated string. */
    private String joinList(List<String> values) {
        if (values == null || values.isEmpty()) return null;
        return values.stream().filter(Objects::nonNull)
                .map(String::trim).map(String::toUpperCase)
                .filter(s -> !s.isEmpty()).distinct()
                .collect(Collectors.joining(","));
    }

    /** LinkedHashSet so the order the user chose survives the round trip. */
    private Set<String> splitToSet(String csv) {
        if (isBlank(csv)) return new LinkedHashSet<>();
        return Arrays.stream(csv.split(","))
                .map(String::trim).map(String::toUpperCase)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private LocalDateTime parseTemporal(String raw, String field) {
        if (isBlank(raw)) return null;
        String v = raw.trim();
        try {
            if (v.length() == 10) return LocalDate.parse(v).atStartOfDay();
            String iso = v.replace(" ", "T");
            return LocalDateTime.parse(iso.substring(0, Math.min(19, iso.length())));
        } catch (DateTimeParseException ex) {
            throw new ValidationException(
                    "Could not read " + field + " = '" + raw + "'. Expected a date (yyyy-MM-dd).");
        }
    }

    private String append(String existing, String addition) {
        String stamp = "[" + LocalDateTime.now() + "] " + addition;
        return isBlank(existing) ? stamp : existing + "\n" + stamp;
    }

    private static boolean isBlank(String s)    { return s == null || s.isBlank(); }
    private static String  trimToNull(String s) { return isBlank(s) ? null : s.trim(); }
}
