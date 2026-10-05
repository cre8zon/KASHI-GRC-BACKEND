package com.kashi.grc.personnel.service;

import com.kashi.grc.asset.domain.Asset;
import com.kashi.grc.asset.repository.AssetRepository;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.exception.ValidationException;
import com.kashi.grc.personnel.domain.Personnel;
import com.kashi.grc.personnel.domain.PersonnelAssetLink;
import com.kashi.grc.personnel.domain.PersonnelExclusion;
import com.kashi.grc.personnel.dto.*;
import com.kashi.grc.personnel.repository.PersonnelAssetLinkRepository;
import com.kashi.grc.personnel.repository.PersonnelExclusionRepository;
import com.kashi.grc.personnel.repository.PersonnelRepository;
import com.kashi.grc.training.service.TrainingAutoAssignService;
import com.kashi.grc.usermanagement.domain.User;
import com.kashi.grc.usermanagement.repository.UserRepository;
import com.kashi.grc.vendor.repository.VendorRepository;
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
 * PersonnelService — the roster, its compliance rollup and the offboarding gate.
 *
 *   PENDING_START -> ACTIVE -> {ON_LEAVE, NOTICE_PERIOD} -> OFFBOARDING -> OFFBOARDED
 *   PENDING_START -> OFFBOARDED  (offer withdrawn)
 *
 * The table below MUST stay identical to module_blueprints.status_flow_json for
 * PERSONNEL and to allowed_statuses_json on each ui_actions row.
 *
 * ── THE COMPLIANCE ROLLUP IS DERIVED, NEVER STORED ────────────────────────
 * computeCompliance runs on every read. Storing it would mean a person whose
 * NDA was signed yesterday still reads NON_COMPLIANT until something happened
 * to refresh them, and the thing that refreshes them would be the bug. It is
 * cheap: the inputs are all columns on the row plus one batch exclusion query.
 *
 * ── THE OFFBOARDING GATE IS THE POINT OF THE MODULE ───────────────────────
 * completeOffboarding refuses while any issued asset has no returnedAt, and
 * warns when a linked platform account is still ACTIVE. That is A.5.11 and the
 * orphaned-account risk (RSK-L-002) enforced at the one moment anybody is
 * paying attention.
 *
 * ── DELETION IS FOR MISTAKES ONLY ─────────────────────────────────────────
 * softDelete refuses anyone who has ever been ACTIVE. Their offboarding record
 * is the artifact an auditor tests; removing it is the opposite of the point.
 * People become OFFBOARDED and stay.
 *
 * ── PERMISSIONS ───────────────────────────────────────────────────────────
 * No @PreAuthorize, matching every other module controller here. What IS
 * enforced: tenancy, transition legality, the offboarding gate, and the fact
 * that screening fields are only writable through saveScreening — so the
 * separate personnel:screen permission on that endpoint is not decorative.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersonnelService {

    private final PersonnelRepository          personnelRepository;
    private final PersonnelExclusionRepository exclusionRepository;
    private final PersonnelAssetLinkRepository assetLinkRepository;
    private final AssetRepository              assetRepository;
    private final UserRepository               userRepository;
    private final VendorRepository             vendorRepository;

    /**
     * Optional so Personnel still starts if the training module is absent.
     * Spring injects null for an unsatisfied @Autowired(required=false) field,
     * and every call site below null-checks it.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private TrainingAutoAssignService trainingAutoAssignService;

    /**
     * Optional for the same reason as the training service above: Personnel
     * must still start if the onboarding module is not deployed. Spring injects
     * null for an unsatisfied @Autowired(required = false) field, and the call
     * site null-checks it.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.kashi.grc.onboarding.service.OnboardingService onboardingService;

    /** Mirrors status_flow_json — see class javadoc. */
    private static final Map<Personnel.Status, Set<Personnel.Status>> ALLOWED = Map.of(
            Personnel.Status.PENDING_START, EnumSet.of(Personnel.Status.ACTIVE, Personnel.Status.OFFBOARDED),
            Personnel.Status.ACTIVE,        EnumSet.of(Personnel.Status.ON_LEAVE, Personnel.Status.NOTICE_PERIOD,
                    Personnel.Status.OFFBOARDING),
            Personnel.Status.ON_LEAVE,      EnumSet.of(Personnel.Status.ACTIVE, Personnel.Status.OFFBOARDING),
            Personnel.Status.NOTICE_PERIOD, EnumSet.of(Personnel.Status.OFFBOARDING),
            Personnel.Status.OFFBOARDING,   EnumSet.of(Personnel.Status.OFFBOARDED),
            Personnel.Status.OFFBOARDED,    EnumSet.noneOf(Personnel.Status.class)
    );

    /**
     * Days after start_date before an unmet requirement counts against someone.
     *
     * A new joiner has not failed to sign an NDA on day one; they have not got
     * to it yet. Without a grace period every hire shows red for their first
     * week and the colour stops meaning anything — the same reason exclusions
     * exist. Thirty days matches the window most programmes give.
     */
    private static final int ONBOARDING_GRACE_DAYS = 30;

    /** Requirement keys, shared with personnel_exclusion_requirement options. */
    private static final String REQ_BGC   = "BACKGROUND_CHECK";
    private static final String REQ_NDA   = "NDA";
    private static final String REQ_AUP   = "ACCEPTABLE_USE";
    private static final String REQ_CODE  = "CODE_OF_CONDUCT";

    private static final Map<String, String> REQUIREMENT_LABELS = Map.of(
            REQ_BGC,  "Background check",
            REQ_NDA,  "Confidentiality agreement",
            REQ_AUP,  "Acceptable use policy",
            REQ_CODE, "Code of conduct",
            "TRAINING",          "Security awareness training",
            "DEVICE_COMPLIANCE", "Device compliance");

    /** Screening outcomes that satisfy the requirement. */
    private static final Set<String> BGC_SATISFIED = Set.of("CLEARED", "NOT_REQUIRED", "WAIVED");

    // ═════════════════════════════════════════════════════════════════════════
    // CREATE
    // ═════════════════════════════════════════════════════════════════════════

    @Transactional
    public PersonnelResponse create(PersonnelRequest req, Long createdBy, Long tenantId) {

        if (req.getUserId() != null)   requireUnlinkedUser(req.getUserId(), tenantId, null);
        if (req.getVendorId() != null) requireTenantVendor(req.getVendorId(), tenantId);
        if (req.getManagerPersonnelId() != null) requireOwn(req.getManagerPersonnelId(), tenantId);

        String source = trimToNull(req.getSourceSystem()) != null
                ? req.getSourceSystem().trim().toUpperCase() : "MANUAL";
        String externalId = trimToNull(req.getExternalId());

        // A sync re-running must update, not duplicate. This is the whole
        // reason external_id exists, so it is enforced on the way in rather
        // than left to the unique key to reject with a stack trace.
        if (externalId != null) {
            personnelRepository.findByTenantIdAndSourceSystemAndExternalId(tenantId, source, externalId)
                    .ifPresent(existing -> {
                        throw new ValidationException(
                                "A person with external id " + externalId + " from " + source
                                        + " already exists on the roster (" + existing.getPersonRef()
                                        + "). Update that record instead of creating a second one.");
                    });
        }

        Personnel person = Personnel.builder()
                .tenantId(tenantId)
                .personRef(resolveRef(req.getPersonRef(), tenantId))
                .firstName(req.getFirstName())
                .lastName(trimToNull(req.getLastName()))
                .workEmail(trimToNull(req.getWorkEmail()))
                .employeeNumber(trimToNull(req.getEmployeeNumber()))
                .employmentType(trimToNull(req.getEmploymentType()))
                .status(Personnel.Status.PENDING_START)
                .department(trimToNull(req.getDepartment()))
                .jobTitle(trimToNull(req.getJobTitle()))
                .managerPersonnelId(req.getManagerPersonnelId())
                .workLocation(trimToNull(req.getWorkLocation()))
                .location(trimToNull(req.getLocation()))
                .userId(req.getUserId())
                .vendorId(req.getVendorId())
                .startDate(req.getStartDate())
                .sourceSystem(source)
                .externalId(externalId)
                .hasPrivilegedAccess(Boolean.TRUE.equals(req.getHasPrivilegedAccess()))
                .isInScope(req.getIsInScope() == null || req.getIsInScope())
                .outOfScopeReason(trimToNull(req.getOutOfScopeReason()))
                .controlTags(trimToNull(req.getControlTags()))
                .frameworkRefs(trimToNull(req.getFrameworkRefs()))
                .notes(req.getNotes())
                .createdBy(createdBy)
                .build();

        requireScopeReason(person);
        personnelRepository.save(person);
        log.info("[PERSONNEL] Added | ref={} | type={} | source={} | tenantId={}",
                person.getPersonRef(), person.getEmploymentType(), source, tenantId);

        // Build the onboarding checklist NOW, at creation, not on activation.
        //
        // Template items carry a due offset from start_date and negative
        // offsets are the point: a background check due at -7 has to exist a
        // week before the person starts, which is impossible if the checklist
        // only appears the day they do.
        //
        // Swallows its own failures inside the service, same as training
        // auto-assign: adding somebody to the roster is an HR fact and must not
        // roll back because a checklist template is misconfigured.
        if (onboardingService != null) {
            onboardingService.instantiateFor(person, createdBy);
        }
        return toResponse(person, tenantId);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // READ
    // ═════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public PersonnelResponse getById(Long id, Long tenantId) {
        return toResponse(requireOwn(id, tenantId), tenantId);
    }

    @Transactional(readOnly = true)
    public List<PersonnelResponse.Exclusion> listExclusions(Long personnelId, Long tenantId) {
        requireOwn(personnelId, tenantId);
        return buildExclusions(personnelId, tenantId);
    }

    /** Assets issued to this person, shaped for the generic LinkedEntitiesTab. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listLinkedAssets(Long personnelId, Long tenantId) {
        requireOwn(personnelId, tenantId);
        List<PersonnelAssetLink> links = assetLinkRepository.findByPersonnelIdAndTenantId(personnelId, tenantId);
        if (links.isEmpty()) return List.of();

        Map<Long, Asset> byId = new HashMap<>();
        assetRepository.findAllById(links.stream().map(PersonnelAssetLink::getAssetId).toList())
                .forEach(a -> byId.put(a.getId(), a));

        List<Map<String, Object>> out = new ArrayList<>(links.size());
        for (PersonnelAssetLink l : links) {
            Asset a = byId.get(l.getAssetId());
            if (a == null || a.isDeleted()) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("linkId", l.getId());
            m.put("id", a.getId());
            m.put("ref", a.getAssetRef());
            m.put("title", a.getName());
            m.put("status", l.getReturnedAt() != null ? "RETURNED" : "OUTSTANDING");
            m.put("badge", l.getReturnedAt() != null
                    ? "Returned " + l.getReturnedAt().toLocalDate()
                    : (l.getAssignedAt() != null ? "Issued " + l.getAssignedAt().toLocalDate() : "Issued"));
            m.put("linkNote", l.getAssignmentNote());
            m.put("navEntityType", "asset");
            out.add(m);
        }
        return out;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // UPDATE
    // ═════════════════════════════════════════════════════════════════════════

    @Transactional
    public PersonnelResponse update(Long id, PersonnelRequest req, Long userId, Long tenantId) {
        Personnel p = requireEditable(id, tenantId);

        if (req.getStatus() != null && !req.getStatus().isBlank()
                && !req.getStatus().equalsIgnoreCase(p.getStatus().name())) {
            throw new ValidationException(
                    "Status cannot be changed by editing the record. Use the lifecycle "
                            + "actions, which record who changed it and when.");
        }
        if (req.getUserId() != null && !req.getUserId().equals(p.getUserId())) {
            requireUnlinkedUser(req.getUserId(), tenantId, p.getId());
        }
        if (req.getVendorId() != null && !req.getVendorId().equals(p.getVendorId())) {
            requireTenantVendor(req.getVendorId(), tenantId);
        }
        if (req.getManagerPersonnelId() != null
                && !req.getManagerPersonnelId().equals(p.getManagerPersonnelId())) {
            requireSafeManager(p, req.getManagerPersonnelId(), tenantId);
        }

        if (req.getFirstName()      != null) p.setFirstName(req.getFirstName());
        if (req.getLastName()       != null) p.setLastName(trimToNull(req.getLastName()));
        if (req.getWorkEmail()      != null) p.setWorkEmail(trimToNull(req.getWorkEmail()));
        if (req.getEmployeeNumber() != null) p.setEmployeeNumber(trimToNull(req.getEmployeeNumber()));
        if (req.getEmploymentType() != null) p.setEmploymentType(trimToNull(req.getEmploymentType()));
        if (req.getDepartment()     != null) p.setDepartment(trimToNull(req.getDepartment()));
        if (req.getJobTitle()       != null) p.setJobTitle(trimToNull(req.getJobTitle()));
        if (req.getManagerPersonnelId() != null) p.setManagerPersonnelId(req.getManagerPersonnelId());
        if (req.getWorkLocation()   != null) p.setWorkLocation(trimToNull(req.getWorkLocation()));
        if (req.getLocation()       != null) p.setLocation(trimToNull(req.getLocation()));
        if (req.getUserId()         != null) p.setUserId(req.getUserId());
        if (req.getVendorId()       != null) p.setVendorId(req.getVendorId());
        if (req.getStartDate()      != null) p.setStartDate(req.getStartDate());
        if (req.getControlTags()    != null) p.setControlTags(trimToNull(req.getControlTags()));
        if (req.getFrameworkRefs()  != null) p.setFrameworkRefs(trimToNull(req.getFrameworkRefs()));
        if (req.getNotes()          != null) p.setNotes(req.getNotes());
        if (req.getHasPrivilegedAccess() != null) p.setHasPrivilegedAccess(req.getHasPrivilegedAccess());
        if (req.getIsInScope()      != null) p.setInScope(req.getIsInScope());
        if (req.getOutOfScopeReason() != null) p.setOutOfScopeReason(trimToNull(req.getOutOfScopeReason()));
        if (req.getSyncPaused()     != null) p.setSyncPaused(req.getSyncPaused());

        // Hand-editing a synced record without pausing the sync is a trap: the
        // next run silently reverts it and the person who made the correction
        // never finds out. Pause it for them and say so.
        if (!"MANUAL".equals(p.getSourceSystem()) && !p.isSyncPaused()
                && touchesSyncedFields(req)) {
            p.setSyncPaused(true);
            log.info("[PERSONNEL] Sync auto-paused | id={} | source={} | a synced field was edited by hand",
                    id, p.getSourceSystem());
        }

        String newRef = trimToNull(req.getPersonRef());
        if (newRef != null && !newRef.equals(p.getPersonRef())) {
            if (personnelRepository.existsByPersonRefAndTenantId(newRef, tenantId)) {
                throw new ValidationException("Reference " + newRef + " is already in use.");
            }
            p.setPersonRef(newRef);
        }

        requireScopeReason(p);
        p.setUpdatedBy(userId);
        personnelRepository.save(p);
        return toResponse(p, tenantId);
    }

    /** Fields an HRIS or IdP would own, so editing them must pause the sync. */
    private boolean touchesSyncedFields(PersonnelRequest req) {
        return req.getFirstName() != null || req.getLastName() != null
                || req.getWorkEmail() != null || req.getDepartment() != null
                || req.getJobTitle() != null || req.getManagerPersonnelId() != null
                || req.getStartDate() != null || req.getEmploymentType() != null;
    }

    @Transactional
    public PersonnelResponse saveEmployment(Long id, PersonnelEmploymentRequest req,
                                            Long userId, Long tenantId) {
        Personnel p = requireOwn(id, tenantId);   // employment detail is editable after offboarding

        if (req.getStartDate()      != null) p.setStartDate(req.getStartDate());
        if (req.getLastWorkingDay() != null) p.setLastWorkingDay(req.getLastWorkingDay());
        if (req.getEndDate()        != null) p.setEndDate(req.getEndDate());
        if (req.getSeparationReason() != null)
            p.setSeparationReason(trimToNull(req.getSeparationReason()));

        LocalDateTime notice = parseTemporal(req.getNoticeGivenAt(), "noticeGivenAt");
        if (notice != null) p.setNoticeGivenAt(notice);

        LocalDateTime revoked = parseTemporal(req.getAccessRevokedAt(), "accessRevokedAt");
        if (revoked != null) p.setAccessRevokedAt(revoked);
        LocalDateTime rotated = parseTemporal(req.getCredentialsRotatedAt(), "credentialsRotatedAt");
        if (rotated != null) p.setCredentialsRotatedAt(rotated);
        LocalDateTime exit = parseTemporal(req.getExitInterviewAt(), "exitInterviewAt");
        if (exit != null) p.setExitInterviewAt(exit);
        if (req.getOffboardingEvidenceRef() != null)
            p.setOffboardingEvidenceRef(trimToNull(req.getOffboardingEvidenceRef()));

        p.setUpdatedBy(userId);
        personnelRepository.save(p);
        return toResponse(p, tenantId);
    }

    /**
     * Behind personnel:screen. The screening fields exist on no other endpoint,
     * which is what makes that permission mean something.
     */
    @Transactional
    public PersonnelResponse saveScreening(Long id, PersonnelScreeningRequest req,
                                           Long userId, Long tenantId) {
        Personnel p = requireOwn(id, tenantId);

        if (req.getBackgroundCheckStatus() != null && !req.getBackgroundCheckStatus().isBlank()) {
            p.setBackgroundCheckStatus(req.getBackgroundCheckStatus().trim().toUpperCase());
        }
        LocalDateTime done = parseTemporal(req.getBackgroundCheckCompletedAt(), "backgroundCheckCompletedAt");
        if (done != null) p.setBackgroundCheckCompletedAt(done);
        if (req.getBackgroundCheckReference() != null)
            p.setBackgroundCheckReference(trimToNull(req.getBackgroundCheckReference()));

        LocalDateTime nda = parseTemporal(req.getNdaSignedAt(), "ndaSignedAt");
        if (nda != null) p.setNdaSignedAt(nda);
        LocalDateTime aup = parseTemporal(req.getAcceptableUseAcceptedAt(), "acceptableUseAcceptedAt");
        if (aup != null) p.setAcceptableUseAcceptedAt(aup);
        LocalDateTime code = parseTemporal(req.getCodeOfConductAcceptedAt(), "codeOfConductAcceptedAt");
        if (code != null) p.setCodeOfConductAcceptedAt(code);

        // A cleared check with no completion date is not evidence of anything.
        if (BGC_SATISFIED.contains(p.getBackgroundCheckStatus())
                && !"NOT_REQUIRED".equals(p.getBackgroundCheckStatus())
                && p.getBackgroundCheckCompletedAt() == null) {
            p.setBackgroundCheckCompletedAt(LocalDateTime.now());
        }

        p.setUpdatedBy(userId);
        personnelRepository.save(p);
        log.info("[PERSONNEL] Screening updated | id={} | bgc={} | by={}",
                id, p.getBackgroundCheckStatus(), userId);
        return toResponse(p, tenantId);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═════════════════════════════════════════════════════════════════════════

    @Transactional
    public PersonnelResponse activate(Long id, Long userId, Long tenantId) {
        Personnel p = requireEditable(id, tenantId);
        transition(p, Personnel.Status.ACTIVE, userId);
        if (p.getStartDate() == null) p.setStartDate(LocalDate.now());
        personnelRepository.save(p);
        log.info("[PERSONNEL] Started | id={} | by={}", id, userId);

        // Assign whatever this tenant requires of joiners.
        //
        // REQUIRES_NEW inside the service it calls, and it swallows its own
        // failures: somebody becoming ACTIVE is an HR fact and must not roll
        // back because a course is misconfigured. Anything missed is picked up
        // by the nightly recurrence sweep.
        if (trainingAutoAssignService != null) {
            trainingAutoAssignService.assignOnJoining(p, userId);
        }
        return toResponse(p, tenantId);
    }

    /**
     * Offer withdrawn. Straight to OFFBOARDED with a reason, because deleting
     * the row would lose the useful fact: an account was raised and never
     * provisioned.
     */
    @Transactional
    public PersonnelResponse neverStarted(Long id, String remarks, Long userId, Long tenantId) {
        Personnel p = requireEditable(id, tenantId);
        transition(p, Personnel.Status.OFFBOARDED, userId);
        p.setSeparationReason("NEVER_STARTED");
        p.setOffboardedAt(LocalDateTime.now());
        p.setInScope(false);
        p.setOutOfScopeReason(isBlank(remarks) ? "Never started" : "Never started: " + remarks);
        personnelRepository.save(p);
        log.info("[PERSONNEL] Never started | id={} | by={}", id, userId);
        return toResponse(p, tenantId);
    }

    @Transactional
    public PersonnelResponse startLeave(Long id, Long userId, Long tenantId) {
        Personnel p = requireEditable(id, tenantId);
        transition(p, Personnel.Status.ON_LEAVE, userId);
        personnelRepository.save(p);
        return toResponse(p, tenantId);
    }

    @Transactional
    public PersonnelResponse returnFromLeave(Long id, Long userId, Long tenantId) {
        Personnel p = requireEditable(id, tenantId);
        transition(p, Personnel.Status.ACTIVE, userId);
        personnelRepository.save(p);
        return toResponse(p, tenantId);
    }

    @Transactional
    public PersonnelResponse giveNotice(Long id, Long userId, Long tenantId) {
        Personnel p = requireEditable(id, tenantId);
        transition(p, Personnel.Status.NOTICE_PERIOD, userId);
        if (p.getNoticeGivenAt() == null) p.setNoticeGivenAt(LocalDateTime.now());
        personnelRepository.save(p);
        return toResponse(p, tenantId);
    }

    @Transactional
    public PersonnelResponse startOffboarding(Long id, Long userId, Long tenantId) {
        Personnel p = requireEditable(id, tenantId);
        transition(p, Personnel.Status.OFFBOARDING, userId);
        p.setOffboardingStartedAt(LocalDateTime.now());
        personnelRepository.save(p);

        long outstanding = assetLinkRepository
                .findByPersonnelIdAndTenantIdAndReturnedAtIsNull(id, tenantId).size();
        log.info("[PERSONNEL] Offboarding started | id={} | privileged={} | {} asset(s) outstanding | by={}",
                id, p.isHasPrivilegedAccess(), outstanding, userId);
        return toResponse(p, tenantId);
    }

    /**
     * The gate. Refuses while any issued asset is unreturned, and refuses
     * without recorded access revocation.
     *
     * A linked platform account still ACTIVE is a WARNING rather than a refusal:
     * disabling it lives in the identity system, not here, and blocking the
     * record from reflecting reality would just teach people to skip the step
     * entirely. The warning is logged and surfaced in the response.
     */
    @Transactional
    public PersonnelResponse completeOffboarding(Long id, String remarks, Long userId, Long tenantId) {
        Personnel p = requireEditable(id, tenantId);

        List<PersonnelAssetLink> outstanding =
                assetLinkRepository.findByPersonnelIdAndTenantIdAndReturnedAtIsNull(id, tenantId);
        if (!outstanding.isEmpty()) {
            List<Long> assetIds = outstanding.stream().map(PersonnelAssetLink::getAssetId).toList();
            String names = assetRepository.findAllById(assetIds).stream()
                    .map(a -> a.getAssetRef() != null ? a.getAssetRef() : a.getName())
                    .limit(5).collect(Collectors.joining(", "));
            throw new ValidationException(
                    "Record the return of " + outstanding.size() + " issued asset(s) before completing "
                            + "offboarding: " + names + (outstanding.size() > 5 ? ", …" : "")
                            + ". Use the Assigned assets tab.");
        }

        if (p.getAccessRevokedAt() == null) {
            throw new ValidationException(
                    "Record when access was revoked on the Employment tab before completing "
                            + "offboarding. A leaver with no revocation timestamp is the finding an "
                            + "auditor looks for first.");
        }

        // Privileged holders and involuntary exits are the two cases where
        // shared credential rotation is specifically expected.
        if ((p.isHasPrivilegedAccess() || "INVOLUNTARY".equals(p.getSeparationReason()))
                && p.getCredentialsRotatedAt() == null) {
            throw new ValidationException(
                    "This person held privileged access or left involuntarily. Record when shared "
                            + "credentials were rotated on the Employment tab before completing offboarding.");
        }

        transition(p, Personnel.Status.OFFBOARDED, userId);
        p.setOffboardedAt(LocalDateTime.now());
        p.setInScope(false);
        if (isBlank(p.getOutOfScopeReason())) p.setOutOfScopeReason("Offboarded");
        if (!isBlank(remarks)) p.setNotes(append(p.getNotes(), "Offboarding: " + remarks));
        if (p.getEndDate() == null) p.setEndDate(LocalDate.now());
        personnelRepository.save(p);

        if (p.getUserId() != null) {
            userRepository.findById(p.getUserId())
                    .filter(u -> !u.isDeleted())
                    .filter(u -> u.getStatus() != null && "ACTIVE".equals(u.getStatus().name()))
                    .ifPresent(u -> log.warn("[PERSONNEL] Offboarded with a live account | personnelId={} "
                                    + "| userId={} | the platform account is still ACTIVE and should be disabled",
                            id, u.getId()));
        }

        Double hours = hoursBetween(p.getOffboardingStartedAt(), p.getAccessRevokedAt());
        log.info("[PERSONNEL] Offboarded | id={} | reason={} | revocation took {}h | by={}",
                id, p.getSeparationReason(), hours, userId);
        return toResponse(p, tenantId);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // EXCLUSIONS
    // ═════════════════════════════════════════════════════════════════════════

    @Transactional
    public PersonnelResponse.Exclusion addExclusion(Long id, PersonnelExclusionRequest req,
                                                    Long userId, Long tenantId) {
        Personnel p = requireOwn(id, tenantId);
        String key = req.getRequirementKey().trim().toUpperCase();
        if (!REQUIREMENT_LABELS.containsKey(key)) {
            throw new ValidationException("Unknown requirement '" + key + "'. Expected one of "
                    + REQUIREMENT_LABELS.keySet() + ".");
        }

        PersonnelExclusion ex = exclusionRepository
                .findByPersonnelIdAndRequirementKey(p.getId(), key)
                .orElseGet(() -> PersonnelExclusion.builder()
                        .tenantId(tenantId).personnelId(p.getId()).requirementKey(key)
                        .createdBy(userId).build());

        ex.setReason(req.getReason().trim());
        ex.setExcludedBy(userId);
        ex.setExcludedAt(LocalDateTime.now());
        ex.setExpiresAt(parseTemporal(req.getExpiresAt(), "expiresAt"));
        ex.setActive(true);
        exclusionRepository.save(ex);

        log.info("[PERSONNEL] Exclusion recorded | id={} | {} | expires={} | by={}",
                id, key, ex.getExpiresAt() == null ? "never" : ex.getExpiresAt(), userId);
        return toExclusion(ex);
    }

    @Transactional
    public void revokeExclusion(Long id, Long exclusionId, Long userId, Long tenantId) {
        requireOwn(id, tenantId);
        PersonnelExclusion ex = exclusionRepository.findById(exclusionId)
                .filter(e -> tenantId.equals(e.getTenantId()) && id.equals(e.getPersonnelId()))
                .orElseThrow(() -> new ResourceNotFoundException("PersonnelExclusion", exclusionId));

        // Deactivated, not deleted. That a carve-out once existed, and who made
        // it, is the record an auditor asks about when a gap reappears.
        ex.setActive(false);
        exclusionRepository.save(ex);
        log.info("[PERSONNEL] Exclusion revoked | id={} | {} | by={}", id, ex.getRequirementKey(), userId);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // ASSET ASSIGNMENT
    // ═════════════════════════════════════════════════════════════════════════

    @Transactional
    public void assignAsset(Long id, PersonnelAssetRequest req, Long userId, Long tenantId) {
        Personnel p = requireEditable(id, tenantId);
        Asset asset = assetRepository.findByIdAndTenantIdAndIsDeletedFalse(req.getAssetId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Asset", req.getAssetId()));

        if (assetLinkRepository.existsByPersonnelIdAndAssetId(p.getId(), asset.getId())) {
            throw new ValidationException("That asset is already assigned to this person.");
        }
        LocalDateTime assignedAt = parseTemporal(req.getAssignedAt(), "assignedAt");
        assetLinkRepository.save(PersonnelAssetLink.builder()
                .tenantId(tenantId).personnelId(p.getId()).assetId(asset.getId())
                .assignedAt(assignedAt != null ? assignedAt : LocalDateTime.now())
                .assignmentNote(trimToNull(req.getAssignmentNote()))
                .createdBy(userId).build());
        log.info("[PERSONNEL] Asset assigned | personnelId={} assetId={}", id, asset.getId());
    }

    /** Records the return. The row stays: that it was issued is part of the record. */
    @Transactional
    public void returnAsset(Long id, Long assetId, Long userId, Long tenantId) {
        requireOwn(id, tenantId);
        PersonnelAssetLink l = assetLinkRepository.findByPersonnelIdAndAssetId(id, assetId)
                .filter(x -> tenantId.equals(x.getTenantId()))
                .orElseThrow(() -> new ResourceNotFoundException("PersonnelAssetLink", "assetId", assetId));
        if (l.getReturnedAt() != null) {
            throw new ValidationException("That asset is already recorded as returned.");
        }
        l.setReturnedAt(LocalDateTime.now());
        assetLinkRepository.save(l);
        log.info("[PERSONNEL] Asset returned | personnelId={} assetId={} | by={}", id, assetId, userId);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SOFT DELETE
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * For records created in error only — a duplicate, a typo, someone entered
     * against the wrong tenant.
     *
     * Refuses anyone who has ever been ACTIVE. Their offboarding evidence is the
     * artifact an auditor tests and deleting it defeats the module. People
     * become OFFBOARDED and stay, which is why the roster outlives the accounts.
     */
    @Transactional
    public void softDelete(Long id, String reason, Long userId, Long tenantId) {
        Personnel p = requireOwn(id, tenantId);

        if (p.hasEmploymentHistory() && p.getStatus() != Personnel.Status.PENDING_START) {
            throw new ValidationException(
                    "This person has an employment history and cannot be deleted. Their record is "
                            + "the evidence that onboarding and offboarding happened. Offboard them "
                            + "instead — the roster is meant to outlive the accounts.");
        }

        List<PersonnelAssetLink> outstanding =
                assetLinkRepository.findByPersonnelIdAndTenantIdAndReturnedAtIsNull(id, tenantId);
        if (!outstanding.isEmpty()) {
            throw new ValidationException(
                    "This person still holds " + outstanding.size() + " issued asset(s). "
                            + "Record their return before removing the record.");
        }

        p.setDeleted(true);
        p.setDeletedAt(LocalDateTime.now());
        p.setDeletedBy(userId);
        p.setDeletionReason(reason);
        p.setUpdatedBy(userId);
        personnelRepository.save(p);
        log.info("[PERSONNEL] Soft-deleted | id={} | ref={} | by={} | reason={}",
                id, p.getPersonRef(), userId, reason);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // ORG CHART
    // ═════════════════════════════════════════════════════════════════════════


    // ═════════════════════════════════════════════════════════════════════════
    // ORG CHART
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Every person in the tenant, for the reporting hierarchy. Unpaginated.
     *
     * A hierarchy cannot be paginated and remain a hierarchy: EntityTreeView
     * built its tree from the page and promoted any node whose manager was
     * absent to a root, so with take=20 and 45 people the chart was
     * structurally wrong rather than merely short.
     *
     * Offboarded people are excluded. They are evidence, not the current
     * organisation, and a chart of who reports to whom should not be half
     * populated by people who left.
     */
    /**
     * Convenience overload: the current organisation, leavers excluded.
     *
     * Delegates, so there is one filtering rule rather than two that drift.
     *
     * This file previously carried a SECOND listForTree(Long) returning
     * List<Map<String,Object>> with its own 5,000-row cap, written in an
     * earlier pass and orphaned when its controller endpoint was removed. Two
     * methods with the same erasure do not compile, and two different caps for
     * the same chart would have been worse if they had. The size limit now
     * lives in one place only — MAX_TREE_NODES on PersonnelController, at 500,
     * which is where the decision to refuse a chart actually belongs.
     */
    @Transactional(readOnly = true)
    public List<Personnel> listForTree(Long tenantId) {
        return listForTree(tenantId, false);
    }

    @Transactional(readOnly = true)
    public List<Personnel> listForTree(Long tenantId, boolean includeOffboarded) {
        return personnelRepository.findAll().stream()
                .filter(p -> tenantId.equals(p.getTenantId()) && !p.isDeleted())
                .filter(p -> includeOffboarded || p.getStatus() != Personnel.Status.OFFBOARDED)
                .toList();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PULL USERS ONTO THE ROSTER
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Creates a roster row for every user of this tenant who lacks one.
     *
     * ── WHY THIS DIRECTION, AND NOT ASSIGNMENT BY user_id ─────────────────
     * Training, asset custody and access review all key on personnel_id,
     * because the record must outlive the login. A user with no roster row is
     * therefore invisible to all three — and it is also a finding in its own
     * right: somebody holds access and is not tracked as a person.
     *
     * Most of these accounts predate the Personnel module entirely, so this is
     * not a migration to run once. It is idempotent and safe to run whenever
     * people have been invited.
     *
     * ── WHAT IT REFUSES TO GUESS ──────────────────────────────────────────
     * A user whose email already matches an UNLINKED roster row is SKIPPED and
     * reported, not merged. That person is probably already on the roster with
     * screening dates and issued assets against them; auto-linking on an email
     * match would be right most of the time and silently wrong the rest, and
     * the wrong cases are the ones with an audit trail attached.
     *
     * Rows are marked source_system = IMPORT so machine-created people stay
     * distinguishable from hand-entered ones and from a future HRIS sync, and
     * is_in_scope = true because somebody with a login is in scope for the
     * compliance programme almost by definition. Correct individuals after.
     */
    @Transactional
    public Map<String, Object> pullUsersOntoRoster(Long tenantId, Long actorId) {
        List<User> users = userRepository.findAll().stream()
                .filter(u -> tenantId.equals(u.getTenantId()) && !u.isDeleted())
                .toList();

        List<Personnel> roster = personnelRepository.findAll().stream()
                .filter(p -> tenantId.equals(p.getTenantId()) && !p.isDeleted())
                .toList();

        Set<Long> linkedUserIds = roster.stream()
                .map(Personnel::getUserId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, Personnel> byEmail = new HashMap<>();
        for (Personnel p : roster) {
            if (!isBlank(p.getWorkEmail())) byEmail.put(p.getWorkEmail().toLowerCase(), p);
        }

        int created = 0;
        List<String> needsLinking = new ArrayList<>();

        for (User u : users) {
            if (linkedUserIds.contains(u.getId())) continue;

            String email = u.getEmail() == null ? null : u.getEmail().toLowerCase();
            Personnel existing = email == null ? null : byEmail.get(email);
            if (existing != null) {
                needsLinking.add(existing.getPersonRef() + " (" + u.getEmail() + ")");
                continue;
            }

            String first = !isBlank(u.getFirstName()) ? u.getFirstName().trim()
                    : (email != null ? email.split("@")[0] : "Unknown");

            Personnel p = Personnel.builder()
                    .tenantId(tenantId)
                    .personRef(resolveRef(null, tenantId))
                    .userId(u.getId())
                    .firstName(first)
                    .lastName(trimToNull(u.getLastName()))
                    .workEmail(trimToNull(u.getEmail()))
                    .employmentType("EMPLOYEE")
                    .status(Personnel.Status.ACTIVE)
                    .jobTitle(trimToNull(u.getJobTitle()))
                    .department(trimToNull(u.getDepartment()))
                    .sourceSystem("IMPORT")
                    .externalId("user:" + u.getId())
                    .isInScope(true)
                    .backgroundCheckStatus("NOT_STARTED")
                    .startDate(u.getCreatedAt() != null ? u.getCreatedAt().toLocalDate() : LocalDate.now())
                    .createdBy(actorId)
                    .build();
            personnelRepository.save(p);
            created++;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("created", created);
        out.put("alreadyLinked", linkedUserIds.size());
        out.put("needsManualLinking", needsLinking);
        out.put("message", created + " added to the roster."
                + (needsLinking.isEmpty() ? ""
                : " " + needsLinking.size() + " matched an existing person by email and were left "
                  + "alone — link those by hand so their screening and asset history is kept."));
        log.info("[PERSONNEL] Roster pull | tenantId={} | created={} | needsLinking={} | by={}",
                tenantId, created, needsLinking.size(), actorId);
        return out;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // COMPLIANCE ROLLUP
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Derived on every read. See the class javadoc for why it is not stored.
     *
     *   OUT_OF_SCOPE  — not in scope, or already offboarded
     *   COMPLIANT     — every requirement met or excluded
     *   GRACE_PERIOD  — something outstanding, but within 30 days of starting
     *   NON_COMPLIANT — past grace, and either unscreened or privileged
     *   AT_RISK       — past grace, missing something less severe
     *
     * The NON_COMPLIANT / AT_RISK split exists so the list can be triaged. An
     * unscreened admin and an unsigned code of conduct are both gaps; treating
     * them identically means the urgent one gets the same attention as the
     * trivial one, which in practice means none.
     */
    private Personnel.ComplianceStatus computeCompliance(
            Personnel p, Collection<PersonnelExclusion> exclusions, List<String> outstandingOut) {

        if (!p.isInScope() || p.getStatus() == Personnel.Status.OFFBOARDED) {
            return Personnel.ComplianceStatus.OUT_OF_SCOPE;
        }

        Set<String> excluded = exclusions.stream()
                .filter(PersonnelExclusion::isEffective)
                .map(PersonnelExclusion::getRequirementKey)
                .collect(Collectors.toSet());

        if (!excluded.contains(REQ_BGC) && !BGC_SATISFIED.contains(p.getBackgroundCheckStatus())) {
            outstandingOut.add(REQ_BGC);
        }
        if (!excluded.contains(REQ_NDA)  && p.getNdaSignedAt() == null)               outstandingOut.add(REQ_NDA);
        if (!excluded.contains(REQ_AUP)  && p.getAcceptableUseAcceptedAt() == null)   outstandingOut.add(REQ_AUP);
        if (!excluded.contains(REQ_CODE) && p.getCodeOfConductAcceptedAt() == null)   outstandingOut.add(REQ_CODE);

        if (outstandingOut.isEmpty()) return Personnel.ComplianceStatus.COMPLIANT;

        // A new joiner has not failed to sign an NDA on day one.
        LocalDate graceEnds = p.getStartDate() != null
                ? p.getStartDate().plusDays(ONBOARDING_GRACE_DAYS) : null;
        boolean inGrace = p.getStatus() == Personnel.Status.PENDING_START
                || (graceEnds != null && !LocalDate.now().isAfter(graceEnds));
        if (inGrace) return Personnel.ComplianceStatus.GRACE_PERIOD;

        boolean severe = outstandingOut.contains(REQ_BGC) || p.isHasPrivilegedAccess();
        return severe ? Personnel.ComplianceStatus.NON_COMPLIANT
                : Personnel.ComplianceStatus.AT_RISK;
    }

    /** Batch variant for the list: one exclusion query for the whole page. */
    public Map<Long, Personnel.ComplianceStatus> computeComplianceBatch(
            List<Personnel> people, Long tenantId) {
        if (people.isEmpty()) return Map.of();
        List<Long> ids = people.stream().map(Personnel::getId).toList();

        Map<Long, List<PersonnelExclusion>> byPerson = exclusionRepository
                .findByPersonnelIdInAndTenantId(ids, tenantId).stream()
                .collect(Collectors.groupingBy(PersonnelExclusion::getPersonnelId));

        Map<Long, Personnel.ComplianceStatus> out = new HashMap<>();
        for (Personnel p : people) {
            out.put(p.getId(), computeCompliance(
                    p, byPerson.getOrDefault(p.getId(), List.of()), new ArrayList<>()));
        }
        return out;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // STATS
    // ═════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public Map<String, Object> getStats(Long tenantId) {
        Map<String, Object> stats = new LinkedHashMap<>();
        Map<String, Long> byStatus    = grouped(personnelRepository.countByStatusForTenant(tenantId));
        Map<String, Long> byType      = grouped(personnelRepository.countByEmploymentTypeForTenant(tenantId));
        Map<String, Long> byScreening = grouped(personnelRepository.countByScreeningStatusForTenant(tenantId));

        long total  = byStatus.values().stream().mapToLong(Long::longValue).sum();
        long active = byStatus.getOrDefault(Personnel.Status.ACTIVE.name(), 0L)
                + byStatus.getOrDefault(Personnel.Status.ON_LEAVE.name(), 0L)
                + byStatus.getOrDefault(Personnel.Status.NOTICE_PERIOD.name(), 0L);

        // Exclusions are subtracted here rather than in SQL: the expiry rule
        // lives on the entity and duplicating it in a predicate would make the
        // two disagree the first time either changes.
        List<Personnel> unscreened = personnelRepository.findUnscreenedInScope(tenantId);
        Set<Long> screeningExcluded = exclusionRepository.findByTenantIdAndIsActiveTrue(tenantId).stream()
                .filter(PersonnelExclusion::isEffective)
                .filter(e -> REQ_BGC.equals(e.getRequirementKey()))
                .map(PersonnelExclusion::getPersonnelId)
                .collect(Collectors.toSet());
        long unscreenedCount = unscreened.stream()
                .filter(p -> !screeningExcluded.contains(p.getId())).count();

        stats.put("total",            total);
        stats.put("active",           active);
        stats.put("byStatus",         byStatus);
        stats.put("byEmploymentType", byType);
        stats.put("byScreeningStatus", byScreening);
        stats.put("unscreenedInScope", unscreenedCount);
        // The "Former Personnel Offboarding" test: leavers with no evidence
        // that their access was ever disabled.
        stats.put("offboardedWithoutEvidence",
                personnelRepository.findOffboardedWithoutRevocationEvidence(tenantId).size());
        stats.put("assetsOutstanding",
                assetLinkRepository.countByTenantIdAndReturnedAtIsNull(tenantId));
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

    public PersonnelResponse toResponse(Personnel p, Long tenantId) {
        List<PersonnelExclusion> exclusions =
                exclusionRepository.findByPersonnelIdAndTenantId(p.getId(), tenantId);

        List<String> outstanding = new ArrayList<>();
        Personnel.ComplianceStatus compliance = computeCompliance(p, exclusions, outstanding);

        int directReports = personnelRepository
                .findByTenantIdAndManagerPersonnelIdAndIsDeletedFalse(tenantId, p.getId()).size();
        int assetsOut = assetLinkRepository
                .findByPersonnelIdAndTenantIdAndReturnedAtIsNull(p.getId(), tenantId).size();

        return PersonnelResponse.builder()
                .id(p.getId())
                .personRef(p.getPersonRef())
                .firstName(p.getFirstName())
                .lastName(p.getLastName())
                .fullName(p.getFullName())
                .workEmail(p.getWorkEmail())
                .employeeNumber(p.getEmployeeNumber())
                .employmentType(p.getEmploymentType())
                .status(p.getStatus() != null ? p.getStatus().name() : null)
                .department(p.getDepartment())
                .jobTitle(p.getJobTitle())
                .workLocation(p.getWorkLocation())
                .location(p.getLocation())
                .managerPersonnelId(p.getManagerPersonnelId())
                .managerName(resolvePersonName(p.getManagerPersonnelId(), tenantId))
                .parentId(p.getManagerPersonnelId())
                .directReportCount(directReports)
                .userId(p.getUserId())
                .userAccountStatus(resolveUserStatus(p.getUserId()))
                .vendorId(p.getVendorId())
                .vendorName(resolveVendorName(p.getVendorId(), tenantId))
                .sourceSystem(p.getSourceSystem())
                .externalId(p.getExternalId())
                .lastSyncedAt(p.getLastSyncedAt())
                .syncPaused(p.isSyncPaused())
                .isInScope(p.isInScope())
                .outOfScopeReason(p.getOutOfScopeReason())
                .complianceStatus(compliance.name())
                .outstandingRequirements(outstanding)
                .hasPrivilegedAccess(p.isHasPrivilegedAccess())
                .startDate(p.getStartDate())
                .lastWorkingDay(p.getLastWorkingDay())
                .endDate(p.getEndDate())
                .noticeGivenAt(p.getNoticeGivenAt())
                .separationReason(p.getSeparationReason())
                .offboardingStartedAt(p.getOffboardingStartedAt())
                .offboardedAt(p.getOffboardedAt())
                .accessRevokedAt(p.getAccessRevokedAt())
                .credentialsRotatedAt(p.getCredentialsRotatedAt())
                .exitInterviewAt(p.getExitInterviewAt())
                .offboardingEvidenceRef(p.getOffboardingEvidenceRef())
                .offboardingEvidenceMissing(p.getStatus() == Personnel.Status.OFFBOARDED
                        && p.getAccessRevokedAt() == null
                        && !"NEVER_STARTED".equals(p.getSeparationReason()))
                .hoursToRevokeAccess(hoursBetween(p.getOffboardingStartedAt(), p.getAccessRevokedAt()))
                .backgroundCheckStatus(p.getBackgroundCheckStatus())
                .backgroundCheckCompletedAt(p.getBackgroundCheckCompletedAt())
                .backgroundCheckReference(p.getBackgroundCheckReference())
                .ndaSignedAt(p.getNdaSignedAt())
                .acceptableUseAcceptedAt(p.getAcceptableUseAcceptedAt())
                .codeOfConductAcceptedAt(p.getCodeOfConductAcceptedAt())
                .controlTags(p.getControlTags())
                .frameworkRefs(p.getFrameworkRefs())
                .notes(p.getNotes())
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .createdBy(p.getCreatedBy())
                .editable(p.getStatus() != Personnel.Status.OFFBOARDED)
                .exclusions(exclusions.stream().map(this::toExclusion).toList())
                .assetsOutstanding(assetsOut)
                .build();
    }

    private List<PersonnelResponse.Exclusion> buildExclusions(Long personnelId, Long tenantId) {
        return exclusionRepository.findByPersonnelIdAndTenantId(personnelId, tenantId).stream()
                .map(this::toExclusion).toList();
    }

    private PersonnelResponse.Exclusion toExclusion(PersonnelExclusion e) {
        String status;
        String badge;
        if (!e.isActive()) {
            status = "REVOKED";
            badge  = "Revoked";
        } else if (e.getExpiresAt() == null) {
            status = "ACTIVE";
            badge  = "Indefinite";
        } else if (e.getExpiresAt().isBefore(LocalDateTime.now())) {
            status = "EXPIRED";
            badge  = "Expired " + e.getExpiresAt().toLocalDate();
        } else {
            status = "ACTIVE";
            badge  = "Until " + e.getExpiresAt().toLocalDate();
        }
        return PersonnelResponse.Exclusion.builder()
                .id(e.getId())
                .ref(e.getRequirementKey())
                .title(REQUIREMENT_LABELS.getOrDefault(e.getRequirementKey(), e.getRequirementKey()))
                .status(status)
                .badge(badge)
                .linkNote(e.getReason())
                .expiresAt(e.getExpiresAt())
                .excludedAt(e.getExcludedAt())
                .excludedByName(resolveUserName(e.getExcludedBy()))
                .build();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    public Personnel requireOwn(Long id, Long tenantId) {
        return personnelRepository.findByIdAndTenantIdAndIsDeletedFalse(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Personnel", id));
    }

    /**
     * Refuses an OFFBOARDED record. It is evidence now. Employment detail and
     * screening stay editable through their own endpoints, because both are
     * routinely completed after the fact from a ticket.
     */
    private Personnel requireEditable(Long id, Long tenantId) {
        Personnel p = requireOwn(id, tenantId);
        if (p.getStatus() == Personnel.Status.OFFBOARDED) {
            throw new ValidationException(
                    "This person has been offboarded and their record is now evidence. "
                            + "Employment dates and screening can still be corrected on their own tabs.");
        }
        return p;
    }

    private void transition(Personnel p, Personnel.Status target, Long userId) {
        Personnel.Status current = p.getStatus();
        Set<Personnel.Status> legal = ALLOWED.getOrDefault(current, Set.of());
        if (!legal.contains(target)) {
            throw new ValidationException(
                    "Cannot move a person from " + current + " to " + target + ". "
                            + (legal.isEmpty() ? current + " is a terminal state."
                            : "Allowed from " + current + ": " + legal + "."));
        }
        p.setStatus(target);
        p.setUpdatedBy(userId);
    }

    /** Out of scope without a reason is a gap wearing a disguise. */
    private void requireScopeReason(Personnel p) {
        if (!p.isInScope() && isBlank(p.getOutOfScopeReason())) {
            throw new ValidationException(
                    "Record why this person is out of scope for the compliance programme. "
                            + "An unexplained exemption is indistinguishable from an oversight.");
        }
    }

    /**
     * One person per user account. Two roster rows pointing at one login makes
     * every per-person compliance answer ambiguous.
     */
    private void requireUnlinkedUser(Long userId, Long tenantId, Long allowPersonnelId) {
        User u = userRepository.findById(userId)
                .filter(x -> !x.isDeleted())
                .orElseThrow(() -> new ValidationException("userId=" + userId + " does not exist."));
        if (!tenantId.equals(u.getTenantId())) {
            throw new ValidationException("userId=" + userId + " belongs to another organization.");
        }
        personnelRepository.findByTenantIdAndUserIdAndIsDeletedFalse(tenantId, userId)
                .filter(existing -> !existing.getId().equals(allowPersonnelId))
                .ifPresent(existing -> {
                    throw new ValidationException(
                            "That platform account is already linked to " + existing.getFullName()
                                    + " (" + existing.getPersonRef() + ").");
                });
    }

    private void requireTenantVendor(Long vendorId, Long tenantId) {
        vendorRepository.findByIdAndTenantIdAndIsDeletedFalse(vendorId, tenantId)
                .orElseThrow(() -> new ValidationException(
                        "vendorId=" + vendorId + " is not a vendor of this organization."));
    }

    /**
     * Refuses a manager that would create a reporting loop.
     *
     * Without it, A reporting to B reporting to A makes EntityTreeView recurse
     * until the browser tab dies — and unlike asset composition, circular
     * reporting lines occur by accident during reorganisations rather than only
     * through malice. The walk is bounded so an existing loop cannot hang the
     * check either.
     */
    private void requireSafeManager(Personnel p, Long newManagerId, Long tenantId) {
        if (newManagerId.equals(p.getId())) {
            throw new ValidationException("A person cannot report to themselves.");
        }
        Personnel manager = requireOwn(newManagerId, tenantId);

        Long cursor = manager.getManagerPersonnelId();
        int hops = 0;
        while (cursor != null && hops++ < 64) {
            if (cursor.equals(p.getId())) {
                throw new ValidationException(
                        "That would create a reporting loop: " + manager.getFullName()
                                + " already reports up through this person.");
            }
            cursor = personnelRepository.findByIdAndTenantIdAndIsDeletedFalse(cursor, tenantId)
                    .map(Personnel::getManagerPersonnelId).orElse(null);
        }
        if (hops >= 64) {
            throw new ValidationException(
                    "The reporting chain is more than 64 levels deep, or already contains a loop. "
                            + "Fix that before reassigning.");
        }
    }

    private String resolveRef(String supplied, Long tenantId) {
        String trimmed = trimToNull(supplied);
        if (trimmed != null && !personnelRepository.existsByPersonRefAndTenantId(trimmed, tenantId)) {
            return trimmed;
        }
        long seq = personnelRepository.nextPersonRefSequence(tenantId);
        String candidate = String.format("PER-%d-%04d", LocalDate.now().getYear(), seq);
        int guard = 0;
        while (personnelRepository.existsByPersonRefAndTenantId(candidate, tenantId) && guard++ < 1000) {
            candidate = String.format("PER-%d-%04d", LocalDate.now().getYear(), ++seq);
        }
        return candidate;
    }

    private String resolvePersonName(Long personnelId, Long tenantId) {
        if (personnelId == null) return null;
        return personnelRepository.findByIdAndTenantIdAndIsDeletedFalse(personnelId, tenantId)
                .map(Personnel::getFullName).orElse(null);
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

    private String resolveUserStatus(Long userId) {
        if (userId == null) return null;
        return userRepository.findById(userId)
                .map(u -> u.getStatus() != null ? u.getStatus().name() : null)
                .orElse("MISSING");
    }

    private String resolveVendorName(Long vendorId, Long tenantId) {
        if (vendorId == null) return null;
        return vendorRepository.findByIdAndTenantIdAndIsDeletedFalse(vendorId, tenantId)
                .map(v -> v.getName()).orElse(null);
    }

    private Double hoursBetween(LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null || to.isBefore(from)) return null;
        return Math.round(Duration.between(from, to).toMinutes() / 6.0) / 10.0;
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