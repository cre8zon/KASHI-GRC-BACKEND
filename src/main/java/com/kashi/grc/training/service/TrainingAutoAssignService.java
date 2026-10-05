package com.kashi.grc.training.service;

import com.kashi.grc.personnel.domain.Personnel;
import com.kashi.grc.personnel.domain.PersonnelExclusion;
import com.kashi.grc.personnel.repository.PersonnelExclusionRepository;
import com.kashi.grc.personnel.repository.PersonnelRepository;
import com.kashi.grc.training.domain.*;
import com.kashi.grc.training.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The two automations that make training happen without anyone remembering.
 *
 * ── 1. ON JOINING ─────────────────────────────────────────────────────────
 * When a person becomes ACTIVE, every course their tenant has marked
 * required_on_joining is assigned. A joiner nobody assigned anything to is the
 * most common way awareness training quietly stops happening, and it is
 * invisible: the register looks clean because the row was never created.
 *
 * ── 2. RECURRENCE ─────────────────────────────────────────────────────────
 * A daily sweep reassigns a course when its last completion is older than the
 * recurrence window. The machinery already existed and nothing ran it:
 * recurrence_months on the course, a tenant override on the requirement, and
 * content_version keyed into the assignment's unique constraint so a new cycle
 * is a new row rather than an overwrite.
 *
 * ── WHY THIS SWEEP CANNOT GROW WITHOUT BOUND ──────────────────────────────
 * The Issues SLA escalation (GAPS item 4) re-escalates every breached item
 * every 24 hours forever, which is why no sweep was wired into Incidents or
 * Training until now. This one cannot do that, structurally rather than by a
 * cap: it only ever creates an assignment for a (person, course, cycle) triple
 * that has none, and the unique key on
 * (personnel_id, target_type, target_id, target_version) makes a second one
 * impossible. Running it a hundred times a day produces the same rows as
 * running it once.
 *
 * It is also idempotent across restarts for the same reason, so a missed night
 * costs nothing and there is no state to reconcile.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrainingAutoAssignService {

    private final TenantCourseRequirementRepository requirementRepository;
    private final TrainingCourseRepository          courseRepository;
    private final TrainingAssignmentRepository      assignmentRepository;
    private final PersonnelRepository               personnelRepository;
    private final PersonnelExclusionRepository      exclusionRepository;

    private static final String REQ_TRAINING = "TRAINING";

    // ═════════════════════════════════════════════════════════════════════════
    // ON JOINING
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Called from PersonnelService.activate.
     *
     * REQUIRES_NEW and swallows its own failures on purpose: a person becoming
     * ACTIVE is an HR fact, and it must not roll back because a training course
     * was misconfigured. The failure is logged loudly and the daily sweep picks
     * up whatever was missed, so nothing is lost by being lenient here.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int assignOnJoining(Personnel person, Long actorId) {
        try {
            List<TenantCourseRequirement> required = requirementRepository
                    .findByTenantIdAndIsActiveTrue(person.getTenantId()).stream()
                    .filter(TenantCourseRequirement::isRequiredOnJoining)
                    .toList();
            if (required.isEmpty()) return 0;

            if (!person.isInScope()) {
                log.debug("[TRAINING-AUTO] {} is out of scope — nothing assigned", person.getPersonRef());
                return 0;
            }
            if (isExcluded(person.getId(), person.getTenantId())) {
                log.info("[TRAINING-AUTO] {} holds a TRAINING exclusion — nothing assigned on joining",
                        person.getPersonRef());
                return 0;
            }

            int created = 0;
            for (TenantCourseRequirement r : required) {
                if (assign(person, r, actorId, "joining")) created++;
            }
            log.info("[TRAINING-AUTO] {} joined | {} course(s) assigned of {} required",
                    person.getPersonRef(), created, required.size());
            return created;

        } catch (Exception e) {
            // Never let this fail the activation it is attached to.
            log.error("[TRAINING-AUTO] Assignment on joining failed for personnelId={} | {}",
                    person.getId(), e.getMessage(), e);
            return 0;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // RECURRENCE
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Reassigns a course to anyone whose last completion has aged past the
     * recurrence window.
     *
     * Driven from the requirement rows rather than from every course, so a
     * tenant that never marked anything required is never touched.
     *
     * @return how many assignments were created
     */
    @Transactional
    public int runRecurrenceSweep() {
        List<TenantCourseRequirement> all = requirementRepository.findByIsActiveTrue();
        if (all.isEmpty()) return 0;

        int created = 0;
        for (TenantCourseRequirement r : all) {
            TrainingCourse course = courseRepository.findById(r.getCourseId())
                    .filter(c -> !c.isDeleted())
                    .orElse(null);
            if (course == null) continue;
            if (course.getStatus() != TrainingCourse.Status.PUBLISHED) continue;

            // The tenant's override wins; otherwise the course's own cadence.
            // Null or zero means assign-once, and those are simply skipped.
            Integer months = r.getRecurrenceMonths() != null
                    ? r.getRecurrenceMonths() : course.getRecurrenceMonths();
            if (months == null || months <= 0) continue;

            LocalDateTime staleBefore = LocalDateTime.now().minusMonths(months);

            List<TrainingAssignment> existing = assignmentRepository
                    .findByTenantIdAndTargetTypeAndTargetIdAndIsDeletedFalse(
                            r.getTenantId(), TrainingAssignment.TargetType.COURSE, course.getId());

            // Latest completion per person. Someone with an OUTSTANDING
            // assignment is deliberately excluded below: they already owe this
            // course, and stacking a second copy on them helps nobody.
            Map<Long, LocalDateTime> lastCompleted = new HashMap<>();
            Set<Long> hasOutstanding = new HashSet<>();
            for (TrainingAssignment a : existing) {
                if (a.getStatus() == TrainingAssignment.Status.CANCELLED) continue;
                if (a.getCompletedAt() == null) { hasOutstanding.add(a.getPersonnelId()); continue; }
                lastCompleted.merge(a.getPersonnelId(), a.getCompletedAt(),
                        (x, y) -> x.isAfter(y) ? x : y);
            }

            for (Map.Entry<Long, LocalDateTime> e : lastCompleted.entrySet()) {
                Long personnelId = e.getKey();
                if (hasOutstanding.contains(personnelId)) continue;
                if (e.getValue().isAfter(staleBefore)) continue;       // still current

                Personnel p = personnelRepository
                        .findByIdAndTenantIdAndIsDeletedFalse(personnelId, r.getTenantId())
                        .orElse(null);
                if (p == null || !p.isInScope()) continue;
                if (p.getStatus() == Personnel.Status.OFFBOARDED) continue;
                if (isExcluded(personnelId, r.getTenantId())) continue;

                if (assign(p, r, null, "recurrence")) created++;
            }
        }
        if (created > 0) log.info("[TRAINING-AUTO] Recurrence sweep created {} assignment(s)", created);
        return created;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SHARED
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Creates one assignment if that exact cycle does not already exist.
     *
     * ── HOW THE CYCLE NUMBER IS CHOSEN ────────────────────────────────────
     * target_version is the course's content version PLUS how many cycles this
     * person has already been through, so:
     *
     *   first assignment of content v1        -> 1
     *   annual repeat of the same content     -> 2, 3, …
     *   content republished as v2             -> at least 2, and never a number
     *                                            already used for this person
     *
     * The unique key on (personnel_id, target_type, target_id, target_version)
     * then makes duplicate creation impossible, which is what stops this sweep
     * growing without bound the way the Issues escalation does.
     */
    private boolean assign(Personnel person, TenantCourseRequirement r, Long actorId, String reason) {
        TrainingCourse course = courseRepository.findById(r.getCourseId())
                .filter(c -> !c.isDeleted())
                .orElse(null);
        if (course == null || course.getStatus() != TrainingCourse.Status.PUBLISHED) return false;

        int contentVersion = course.getContentVersion() == null ? 1 : course.getContentVersion();
        Integer highest = assignmentRepository.maxVersionFor(
                person.getId(), TrainingAssignment.TargetType.COURSE, course.getId());
        int version = highest == null ? contentVersion : Math.max(contentVersion, highest + 1);

        if (assignmentRepository.findByPersonnelIdAndTargetTypeAndTargetIdAndTargetVersion(
                        person.getId(), TrainingAssignment.TargetType.COURSE, course.getId(), version)
                .isPresent()) {
            return false;
        }

        assignmentRepository.save(TrainingAssignment.builder()
                .tenantId(r.getTenantId())
                .personnelId(person.getId())
                .targetType(TrainingAssignment.TargetType.COURSE)
                .targetId(course.getId())
                .targetVersion(version)
                .targetTitle(course.getTitle())
                .status(TrainingAssignment.Status.ASSIGNED)
                .assignedAt(LocalDateTime.now())
                .dueAt(LocalDateTime.now().plusDays(r.getDueDays() == null ? 30 : r.getDueDays()))
                .assignedBy(actorId)
                .createdBy(actorId)
                .build());

        log.info("[TRAINING-AUTO] Assigned '{}' v{} to {} | {}",
                course.getTitle(), version, person.getPersonRef(), reason);
        return true;
    }

    /** An active, unexpired TRAINING exclusion means this person is carved out. */
    private boolean isExcluded(Long personnelId, Long tenantId) {
        return exclusionRepository.findByPersonnelIdAndTenantId(personnelId, tenantId).stream()
                .filter(PersonnelExclusion::isEffective)
                .anyMatch(e -> REQ_TRAINING.equals(e.getRequirementKey()));
    }

    /**
     * Backfills a requirement across people who are already here.
     *
     * Marking a course required does NOT retrospectively assign it — that would
     * hand an organisation a hundred overdue rows the moment they tick a box.
     * This is the deliberate version, called only when someone asks for it.
     */
    @Transactional
    public int assignToExistingStaff(Long tenantId, Long courseId, Long actorId) {
        TenantCourseRequirement r = requirementRepository
                .findByTenantIdAndCourseId(tenantId, courseId)
                .orElseThrow(() -> new IllegalStateException(
                        "Mark the course required before backfilling it."));

        List<Personnel> people = personnelRepository.findAll().stream()
                .filter(p -> tenantId.equals(p.getTenantId()) && !p.isDeleted())
                .filter(Personnel::isInScope)
                .filter(p -> p.getStatus() != Personnel.Status.OFFBOARDED)
                .filter(p -> !isExcluded(p.getId(), tenantId))
                .toList();

        int created = 0;
        for (Personnel p : people) {
            if (assign(p, r, actorId, "backfill")) created++;
        }
        log.info("[TRAINING-AUTO] Backfill | courseId={} tenantId={} | {} of {} assigned",
                courseId, tenantId, created, people.size());
        return created;
    }

    /** Requirement ids for a tenant, so the course list can show which are required. */
    @Transactional(readOnly = true)
    public Set<Long> requiredCourseIds(Long tenantId) {
        return requirementRepository.findByTenantIdAndIsActiveTrue(tenantId).stream()
                .map(TenantCourseRequirement::getCourseId)
                .collect(Collectors.toSet());
    }
}