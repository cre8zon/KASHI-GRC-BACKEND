package com.kashi.grc.training.service;

import com.kashi.grc.audit.domain.AuditPolicy;
import com.kashi.grc.audit.repository.AuditPolicyRepository;
import com.kashi.grc.common.exception.ForbiddenException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.exception.ValidationException;
import com.kashi.grc.personnel.domain.Personnel;
import com.kashi.grc.personnel.domain.PersonnelExclusion;
import com.kashi.grc.personnel.repository.PersonnelExclusionRepository;
import com.kashi.grc.personnel.repository.PersonnelRepository;
import com.kashi.grc.training.domain.*;
import com.kashi.grc.training.dto.*;
import com.kashi.grc.training.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * TrainingCourseService — authoring a course, publishing it, and handing it out.
 *
 * ── PUBLISHING IS A GATE, NOT A FLAG ──────────────────────────────────────
 * publish() refuses a course that cannot actually be completed: no content, a
 * video with no duration, a quiz percentage with no questions. Each of those
 * produces a course that looks assignable and then traps whoever is assigned
 * it — the video one is the worst, because without a duration every completion
 * calculation divides by nothing and the item can never be marked done.
 *
 * ── CONTENT IS FROZEN AFTER PUBLISHING ────────────────────────────────────
 * Items and questions can only be added while DRAFT. Changing the questions
 * under somebody who already passed would silently invalidate their record
 * without anybody deciding to; archive-and-republish makes that an explicit
 * act with a new version behind it.
 *
 * ── POLICIES ARE READ, NEVER WRITTEN ──────────────────────────────────────
 * assignPolicy loads an approved audit_policies row, refuses anything not
 * APPROVED, and pins its version onto the assignment. Nothing in the policy
 * module is modified, so its approval workflow, versioning, supersession and
 * control mappings are untouched.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrainingCourseService {

    private final TrainingCourseRepository       courseRepository;
    private final TrainingCourseItemRepository   itemRepository;
    private final TrainingQuizQuestionRepository questionRepository;
    private final TrainingQuizOptionRepository   optionRepository;
    private final TrainingAssignmentRepository   assignmentRepository;
    private final PersonnelRepository            personnelRepository;
    private final PersonnelExclusionRepository   exclusionRepository;
    private final AuditPolicyRepository          policyRepository;

    private static final int DEFAULT_DUE_DAYS = 30;

    /** Matches personnel_exclusion_requirement — a person excluded here is skipped. */
    private static final String REQ_TRAINING = "TRAINING";

    // ═════════════════════════════════════════════════════════════════════════
    // AUTHORING
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * @param platformScope true for a SYSTEM author creating a library course
     *                      (tenant_id NULL). Everyone else authors for their
     *                      own tenant.
     */
    @Transactional
    public TrainingCourseResponse create(TrainingCourseRequest req, boolean platformScope,
                                         Long userId, Long tenantId) {
        Long scope = platformScope ? null : tenantId;

        TrainingCourse c = TrainingCourse.builder()
                .tenantId(scope)
                .courseRef(resolveRef(req.getCourseRef(), scope))
                .title(req.getTitle())
                .description(req.getDescription())
                .category(trimToNull(req.getCategory()))
                .status(TrainingCourse.Status.DRAFT)
                .estimatedMinutes(req.getEstimatedMinutes())
                .requiredWatchPercent(req.getRequiredWatchPercent() != null
                        ? req.getRequiredWatchPercent() : 90)
                .quizPassPercent(req.getQuizPassPercent())
                .requiresAttestation(req.getRequiresAttestation() == null
                        || req.getRequiresAttestation())
                .recurrenceMonths(req.getRecurrenceMonths())
                .controlTags(trimToNull(req.getControlTags()))
                .frameworkRefs(trimToNull(req.getFrameworkRefs()))
                .createdBy(userId)
                .build();

        courseRepository.save(c);
        log.info("[TRAINING] Course created | ref={} | scope={} | by={}",
                c.getCourseRef(), platformScope ? "PLATFORM" : "tenant " + tenantId, userId);
        return toResponse(c, tenantId, platformScope);
    }

    @Transactional
    public TrainingCourseResponse update(Long id, TrainingCourseRequest req, Long userId, Long tenantId, boolean systemUser) {
        TrainingCourse c = requireEditableCourse(id, tenantId, systemUser);

        if (req.getStatus() != null && !req.getStatus().isBlank()
                && !req.getStatus().equalsIgnoreCase(c.getStatus().name())) {
            throw new ValidationException(
                    "Status cannot be changed by editing the course. Use Publish or Archive.");
        }

        if (req.getTitle()        != null) c.setTitle(req.getTitle());
        if (req.getDescription()  != null) c.setDescription(req.getDescription());
        if (req.getCategory()     != null) c.setCategory(trimToNull(req.getCategory()));
        if (req.getEstimatedMinutes() != null) c.setEstimatedMinutes(req.getEstimatedMinutes());
        if (req.getRequiredWatchPercent() != null) c.setRequiredWatchPercent(req.getRequiredWatchPercent());
        if (req.getRecurrenceMonths() != null) c.setRecurrenceMonths(req.getRecurrenceMonths());
        if (req.getRequiresAttestation() != null) c.setRequiresAttestation(req.getRequiresAttestation());
        if (req.getControlTags()   != null) c.setControlTags(trimToNull(req.getControlTags()));
        if (req.getFrameworkRefs() != null) c.setFrameworkRefs(trimToNull(req.getFrameworkRefs()));

        // Removing the pass mark removes the quiz requirement, which would let
        // anyone mid-course skip a gate they were already past. Only allowed
        // while nothing has been assigned.
        if (req.getQuizPassPercent() != null || c.getQuizPassPercent() != null) {
            long assigned = assignmentRepository.countByTenantIdAndTargetTypeAndTargetIdAndIsDeletedFalse(
                    tenantId, TrainingAssignment.TargetType.COURSE, c.getId());
            if (assigned > 0 && !Objects.equals(req.getQuizPassPercent(), c.getQuizPassPercent())) {
                throw new ValidationException(
                        "The quiz pass mark cannot change while " + assigned + " assignment(s) exist. "
                                + "Archive the course and publish a new version instead.");
            }
            c.setQuizPassPercent(req.getQuizPassPercent());
        }

        String newRef = trimToNull(req.getCourseRef());
        if (newRef != null && !newRef.equals(c.getCourseRef())) {
            if (courseRepository.existsByCourseRefAndTenantId(newRef, c.getTenantId())) {
                throw new ValidationException("Reference " + newRef + " is already in use.");
            }
            c.setCourseRef(newRef);
        }

        c.setUpdatedBy(userId);
        courseRepository.save(c);
        return toResponse(c, tenantId, systemUser);
    }

    @Transactional
    public TrainingCourseResponse addItem(Long courseId, TrainingItemRequest req,
                                          Long userId, Long tenantId, boolean systemUser) {
        TrainingCourse c = requireDraftCourse(courseId, tenantId, systemUser);

        TrainingCourseItem.ItemType type;
        try {
            type = TrainingCourseItem.ItemType.valueOf(req.getItemType().trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ValidationException("Unknown content type '" + req.getItemType()
                    + "'. Expected VIDEO, DOCUMENT, LINK or TEXT.");
        }

        // Each type needs its own thing, and a missing one produces content that
        // renders as an empty box rather than failing.
        if (type == TrainingCourseItem.ItemType.VIDEO) {
            if (req.getDocumentId() == null) {
                throw new ValidationException("Upload the video first.");
            }
            if (req.getDurationSeconds() == null || req.getDurationSeconds() <= 0) {
                throw new ValidationException(
                        "A video needs its duration in seconds. Every completion check divides by it, "
                                + "so without it the item can never be marked watched.");
            }
        }
        if (type == TrainingCourseItem.ItemType.DOCUMENT && req.getDocumentId() == null) {
            throw new ValidationException("Upload the document first.");
        }
        if (type == TrainingCourseItem.ItemType.LINK && isBlank(req.getExternalUrl())) {
            throw new ValidationException("A link item needs a URL.");
        }
        if (type == TrainingCourseItem.ItemType.TEXT && isBlank(req.getBody())) {
            throw new ValidationException("A text item needs some text.");
        }

        int nextOrder = req.getSortOrder() != null ? req.getSortOrder()
                : (int) itemRepository.countByCourseIdAndIsDeletedFalse(c.getId()) * 10 + 10;

        itemRepository.save(TrainingCourseItem.builder()
                .tenantId(c.getTenantId())
                .courseId(c.getId())
                .itemType(type)
                .title(req.getTitle())
                .sortOrder(nextOrder)
                .documentId(req.getDocumentId())
                .mimeType(trimToNull(req.getMimeType()))
                .fileSizeBytes(req.getFileSizeBytes())
                .durationSeconds(req.getDurationSeconds())
                .externalUrl(trimToNull(req.getExternalUrl()))
                .body(req.getBody())
                .isRequired(req.getIsRequired() == null || req.getIsRequired())
                .createdBy(userId)
                .build());

        log.info("[TRAINING] Item added | courseId={} | {} | {}", courseId, type, req.getTitle());
        return toResponse(c, tenantId, systemUser);
    }

    /**
     * Expands the flat form into a question and up to four options.
     *
     * correctOption is "1".."4" and is resolved against the options that were
     * actually supplied, so marking option 4 correct when only three were typed
     * is refused rather than producing a question nobody can pass.
     */
    @Transactional
    public TrainingCourseResponse addQuestion(Long courseId, TrainingQuestionRequest req,
                                              Long userId, Long tenantId, boolean systemUser) {
        TrainingCourse c = requireDraftCourse(courseId, tenantId, systemUser);

        List<String> texts = new ArrayList<>();
        for (String t : List.of(nullToEmpty(req.getOption1()), nullToEmpty(req.getOption2()),
                nullToEmpty(req.getOption3()), nullToEmpty(req.getOption4()))) {
            texts.add(t.trim());
        }
        long supplied = texts.stream().filter(t -> !t.isEmpty()).count();
        if (supplied < 2) {
            throw new ValidationException("A question needs at least two options.");
        }

        int correctIndex;
        try {
            correctIndex = Integer.parseInt(req.getCorrectOption().trim());
        } catch (NumberFormatException ex) {
            throw new ValidationException("Mark which option number is correct (1-4).");
        }
        if (correctIndex < 1 || correctIndex > 4 || texts.get(correctIndex - 1).isEmpty()) {
            throw new ValidationException(
                    "Option " + correctIndex + " is marked correct but has no text.");
        }

        TrainingQuizQuestion.QuestionType qType = TrainingQuizQuestion.QuestionType.SINGLE_CHOICE;
        if (!isBlank(req.getQuestionType())) {
            try {
                qType = TrainingQuizQuestion.QuestionType.valueOf(
                        req.getQuestionType().trim().toUpperCase());
            } catch (IllegalArgumentException ignored) { /* fall back to single choice */ }
        }

        int nextOrder = req.getSortOrder() != null ? req.getSortOrder()
                : (int) questionRepository.countByCourseIdAndIsActiveTrue(c.getId()) * 10 + 10;

        TrainingQuizQuestion q = questionRepository.save(TrainingQuizQuestion.builder()
                .tenantId(c.getTenantId())
                .courseId(c.getId())
                .questionText(req.getQuestionText())
                .questionType(qType)
                .explanation(req.getExplanation())
                .sortOrder(nextOrder)
                .createdBy(userId)
                .build());

        for (int i = 0; i < texts.size(); i++) {
            if (texts.get(i).isEmpty()) continue;
            optionRepository.save(TrainingQuizOption.builder()
                    .tenantId(c.getTenantId())
                    .questionId(q.getId())
                    .optionText(texts.get(i))
                    .isCorrect(i == correctIndex - 1)
                    .sortOrder((i + 1) * 10)
                    .build());
        }

        log.info("[TRAINING] Question added | courseId={} | questionId={} | {} options",
                courseId, q.getId(), supplied);
        return toResponse(c, tenantId, systemUser);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PUBLISH / ARCHIVE
    // ═════════════════════════════════════════════════════════════════════════

    @Transactional
    public TrainingCourseResponse publish(Long id, Long userId, Long tenantId, boolean systemUser) {
        TrainingCourse c = requireEditableCourse(id, tenantId, systemUser);
        if (c.getStatus() == TrainingCourse.Status.PUBLISHED) {
            throw new ValidationException("This course is already published.");
        }

        List<TrainingCourseItem> items =
                itemRepository.findByCourseIdAndIsDeletedFalseOrderBySortOrderAsc(c.getId());
        if (items.isEmpty()) {
            throw new ValidationException("Add some content before publishing.");
        }

        // The trap this exists to prevent: a video with no duration can never be
        // marked watched, so the course would be assignable and uncompletable.
        List<String> brokenVideos = items.stream()
                .filter(TrainingCourseItem::isWatchTracked)
                .filter(i -> i.getDurationSeconds() == null || i.getDurationSeconds() <= 0)
                .map(TrainingCourseItem::getTitle).toList();
        if (!brokenVideos.isEmpty()) {
            throw new ValidationException(
                    "These videos have no duration recorded and could never be completed: "
                            + String.join(", ", brokenVideos) + ".");
        }

        if (c.hasQuiz() && questionRepository.countByCourseIdAndIsActiveTrue(c.getId()) == 0) {
            throw new ValidationException(
                    "This course has a pass mark of " + c.getQuizPassPercent()
                            + "% but no quiz questions. Add questions, or clear the pass mark.");
        }

        c.setStatus(TrainingCourse.Status.PUBLISHED);
        c.setPublishedAt(LocalDateTime.now());
        c.setUpdatedBy(userId);
        courseRepository.save(c);
        log.info("[TRAINING] Published | courseId={} | {} item(s) | by={}", id, items.size(), userId);
        return toResponse(c, tenantId, systemUser);
    }

    @Transactional
    public TrainingCourseResponse archive(Long id, String remarks, Long userId, Long tenantId, boolean systemUser) {
        TrainingCourse c = requireEditableCourse(id, tenantId, systemUser);
        c.setStatus(TrainingCourse.Status.ARCHIVED);
        c.setUpdatedBy(userId);
        courseRepository.save(c);
        // Existing assignments are untouched on purpose: their completion
        // records are evidence, and withdrawing a course must not erase proof
        // that people did it.
        log.info("[TRAINING] Archived | courseId={} | existing assignments retained | by={}", id, userId);
        return toResponse(c, tenantId, systemUser);
    }

    /**
     * Reopens a published course for editing, bumping the content version.
     *
     * Content is frozen at publish so that editing questions cannot silently
     * invalidate a completion somebody already earned. This is the explicit way
     * to reopen it, and contentVersion is what preserves the guarantee: existing
     * assignments keep pointing at the version they were earned against, and
     * republished content assigns as a new version rather than re-scoping the
     * old records underneath people.
     *
     * Remarks are required by the action. Reopening published content is a
     * decision worth a sentence, and the sentence is what the next person reads
     * when they find the course back in draft.
     */
    @Transactional
    public TrainingCourseResponse unpublish(Long id, String remarks, Long userId,
                                            Long tenantId, boolean systemUser) {
        TrainingCourse c = requireEditableCourse(id, tenantId, systemUser);
        if (c.getStatus() == TrainingCourse.Status.DRAFT) {
            throw new ValidationException("This course is already a draft.");
        }

        long assigned = assignmentRepository.countByTenantIdAndTargetTypeAndTargetIdAndIsDeletedFalse(
                tenantId, TrainingAssignment.TargetType.COURSE, c.getId());

        c.setStatus(TrainingCourse.Status.DRAFT);
        c.setUnpublishedAt(LocalDateTime.now());
        c.setContentVersion(c.getContentVersion() == null ? 2 : c.getContentVersion() + 1);
        c.setUpdatedBy(userId);
        courseRepository.save(c);

        log.info("[TRAINING] Unpublished | courseId={} | now content v{} | {} existing assignment(s) "
                        + "retained against their own version | by={} | {}",
                id, c.getContentVersion(), assigned, userId, remarks);
        return toResponse(c, tenantId, systemUser);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // ASSIGNMENT
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Assigns a published course to named people, a department, or both.
     *
     * Skips, rather than fails, for: people already holding the current cycle,
     * people out of scope, people offboarded, and people with an active
     * TRAINING exclusion. Failing the whole run because one of forty is a
     * leaver would make the button unusable.
     */
    @Transactional
    public Map<String, Object> assignCourse(Long courseId, TrainingAssignRequest req,
                                            Long userId, Long tenantId) {
        TrainingCourse c = courseRepository.findById(courseId)
                .filter(x -> !x.isDeleted())
                .filter(x -> x.getTenantId() == null || tenantId.equals(x.getTenantId()))
                .orElseThrow(() -> new ResourceNotFoundException("TrainingCourse", courseId));

        if (c.getStatus() != TrainingCourse.Status.PUBLISHED) {
            throw new ValidationException("Only a published course can be assigned.");
        }

        List<Long> ids = new ArrayList<>();
        if (req.getPersonnelIds() != null) ids.addAll(req.getPersonnelIds());
        if (req.getPersonnelId() != null)  ids.add(req.getPersonnelId());

        List<Personnel> targets = Boolean.TRUE.equals(req.getAssignAllInScope())
                ? resolveEveryoneInScope(tenantId)
                : resolveTargets(ids, req.getDepartment(), tenantId);

        LocalDateTime due = LocalDateTime.now().plusDays(
                req.getDueInDays() != null && req.getDueInDays() > 0
                        ? req.getDueInDays() : DEFAULT_DUE_DAYS);

        // Keyed on the CONTENT version, so republished content assigns afresh
        // instead of being skipped as already-held.
        return createAssignments(targets, TrainingAssignment.TargetType.COURSE,
                c.getId(), c.getTitle(), due, userId, tenantId,
                c.getContentVersion() == null ? 1 : c.getContentVersion());
    }

    /**
     * Assigns an APPROVED policy for reading and acceptance.
     *
     * The version is pinned onto the assignment, which is the entire point:
     * accepting v2 says nothing about v3, so publishing a new version produces
     * fresh assignments rather than reopening old acceptances.
     */
    @Transactional
    public Map<String, Object> assignPolicy(TrainingPolicyAssignRequest req, Long userId, Long tenantId) {
        AuditPolicy policy = policyRepository.findById(req.getPolicyId())
                .filter(p -> p.getTenantId() == null || tenantId.equals(p.getTenantId()))
                .orElseThrow(() -> new ResourceNotFoundException("AuditPolicy", req.getPolicyId()));

        if (policy.getStatus() != AuditPolicy.PolicyStatus.APPROVED) {
            throw new ValidationException(
                    "Only an approved policy can be assigned for acceptance. '" + policy.getTitle()
                            + "' is " + policy.getStatus() + ".");
        }

        List<Personnel> targets = resolveTargets(req.getPersonnelIds(), req.getDepartment(), tenantId);
        LocalDateTime due = LocalDateTime.now().plusDays(
                req.getDueInDays() != null && req.getDueInDays() > 0
                        ? req.getDueInDays() : DEFAULT_DUE_DAYS);

        Map<String, Object> result = createAssignments(targets, TrainingAssignment.TargetType.POLICY,
                policy.getId(), policy.getTitle(), due, userId, tenantId, policy.getVersion());
        log.info("[TRAINING] Policy assigned | policyId={} v{} | {} people",
                policy.getId(), policy.getVersion(), targets.size());
        return result;
    }

    private Map<String, Object> createAssignments(List<Personnel> targets,
                                                  TrainingAssignment.TargetType type,
                                                  Long targetId, String title, LocalDateTime due,
                                                  Long userId, Long tenantId) {
        return createAssignments(targets, type, targetId, title, due, userId, tenantId, null);
    }

    private Map<String, Object> createAssignments(List<Personnel> targets,
                                                  TrainingAssignment.TargetType type,
                                                  Long targetId, String title, LocalDateTime due,
                                                  Long userId, Long tenantId, Integer fixedVersion) {
        Set<Long> excluded = exclusionRepository.findByTenantIdAndIsActiveTrue(tenantId).stream()
                .filter(PersonnelExclusion::isEffective)
                .filter(e -> REQ_TRAINING.equals(e.getRequirementKey()))
                .map(PersonnelExclusion::getPersonnelId)
                .collect(Collectors.toSet());

        int created = 0, skipped = 0;
        List<String> notes = new ArrayList<>();

        for (Personnel p : targets) {
            if (excluded.contains(p.getId())) {
                skipped++;
                notes.add(p.getFullName() + ": excluded from training requirements");
                continue;
            }

            // For a COURSE the version is the recurrence cycle, so next year's
            // assignment is a new row with its own evidence rather than an
            // overwrite of this year's.
            // Always supplied now: contentVersion for a course, the policy's
            // own version for a policy. The old fallback read back the highest
            // existing assignment, which meant republished content was silently
            // treated as already held.
            Integer version = fixedVersion != null ? fixedVersion : 1;

            Optional<TrainingAssignment> existing = assignmentRepository
                    .findByPersonnelIdAndTargetTypeAndTargetIdAndTargetVersion(
                            p.getId(), type, targetId, version);
            if (existing.isPresent()) {
                skipped++;
                continue;
            }

            assignmentRepository.save(TrainingAssignment.builder()
                    .tenantId(tenantId)
                    .personnelId(p.getId())
                    .targetType(type)
                    .targetId(targetId)
                    .targetVersion(version)
                    .targetTitle(title)
                    .status(TrainingAssignment.Status.ASSIGNED)
                    .assignedAt(LocalDateTime.now())
                    .dueAt(due)
                    .assignedBy(userId)
                    .createdBy(userId)
                    .build());
            created++;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("created", created);
        out.put("skipped", skipped);
        out.put("notes", notes);
        out.put("message", created + " assigned, " + skipped + " skipped");
        log.info("[TRAINING] Assigned | {} {} | created={} skipped={}", type, targetId, created, skipped);
        return out;
    }

    /**
     * Every in-scope, non-offboarded person in the tenant.
     *
     * Resolved at the moment it runs, so somebody joining next month is not
     * retrospectively assigned — they get it through onboarding instead. People
     * with an active TRAINING exclusion are dropped later, in createAssignments,
     * so the exclusion reason stays the single place that decision lives.
     */
    private List<Personnel> resolveEveryoneInScope(Long tenantId) {
        List<Personnel> all = personnelRepository.findAll().stream()
                .filter(p -> tenantId.equals(p.getTenantId()) && !p.isDeleted())
                .filter(Personnel::isInScope)
                .filter(p -> p.getStatus() != Personnel.Status.OFFBOARDED)
                .toList();
        if (all.isEmpty()) {
            throw new ValidationException(
                    "There is nobody in scope on the roster to assign this to.");
        }
        return all;
    }

    /**
     * Named people plus everyone in a department, de-duplicated.
     *
     * Out-of-scope and offboarded people are dropped here rather than being
     * assigned and then excluded: the compliance programme does not cover them,
     * so an assignment would show as an outstanding gap forever.
     */
    private List<Personnel> resolveTargets(List<Long> ids, String department, Long tenantId) {
        Map<Long, Personnel> byId = new LinkedHashMap<>();

        if (ids != null) {
            for (Long id : ids) {
                personnelRepository.findByIdAndTenantIdAndIsDeletedFalse(id, tenantId)
                        .ifPresent(p -> byId.put(p.getId(), p));
            }
        }
        if (department != null && !department.isBlank()) {
            personnelRepository.findAll().stream()
                    .filter(p -> tenantId.equals(p.getTenantId()) && !p.isDeleted())
                    .filter(p -> department.trim().equalsIgnoreCase(nullToEmpty(p.getDepartment())))
                    .forEach(p -> byId.put(p.getId(), p));
        }

        List<Personnel> usable = byId.values().stream()
                .filter(Personnel::isInScope)
                .filter(p -> p.getStatus() != Personnel.Status.OFFBOARDED)
                .toList();

        if (usable.isEmpty()) {
            throw new ValidationException(
                    "Nobody to assign to. Select people, or a department with in-scope members.");
        }
        return usable;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // MAPPING AND HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    public TrainingCourseResponse toResponse(TrainingCourse c, Long tenantId, boolean systemUser) {
        List<TrainingCourseItem> items =
                itemRepository.findByCourseIdAndIsDeletedFalseOrderBySortOrderAsc(c.getId());
        boolean library = c.getTenantId() == null;

        int totalDuration = items.stream()
                .filter(TrainingCourseItem::isWatchTracked)
                .filter(TrainingCourseItem::isRequired)
                .mapToInt(i -> i.getDurationSeconds() == null ? 0 : i.getDurationSeconds())
                .sum();

        return TrainingCourseResponse.builder()
                .id(c.getId())
                .courseRef(c.getCourseRef())
                .title(c.getTitle())
                .description(c.getDescription())
                .category(c.getCategory())
                .status(c.getStatus().name())
                .estimatedMinutes(c.getEstimatedMinutes())
                .requiredWatchPercent(c.getRequiredWatchPercent())
                .quizPassPercent(c.getQuizPassPercent())
                .requiresAttestation(c.isRequiresAttestation())
                .recurrenceMonths(c.getRecurrenceMonths())
                .controlTags(c.getControlTags())
                .frameworkRefs(c.getFrameworkRefs())
                .publishedAt(c.getPublishedAt())
                .origin(library ? "GLOBAL" : "ORG")
                // OWNERSHIP ONLY \u2014 deliberately not status.
                //
                // The full-page detail hides every mutating action when editable
                // is false. Including "&& status == DRAFT" therefore hid
                // Unpublish, Archive and Assign on a PUBLISHED course, which are
                // exactly the actions that only exist once it is published.
                //
                // Content editing is gated twice already, in the right places:
                // allowed_statuses_json ["DRAFT"] on COURSE_ADD_ITEM and
                // COURSE_ADD_QUESTION, and requireDraftCourse server-side. This
                // flag answers a different question: is this record mine at all.
                .editable(c.isOwnedBy(tenantId, systemUser))
                .itemCount(items.size())
                .questionCount((int) questionRepository.countByCourseIdAndIsActiveTrue(c.getId()))
                .assignedCount((int) assignmentRepository
                        .countByTenantIdAndTargetTypeAndTargetIdAndIsDeletedFalse(
                                tenantId, TrainingAssignment.TargetType.COURSE, c.getId()))
                .totalDurationSeconds(totalDuration)
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .items(items.stream().map(this::toItem).toList())
                .build();
    }

    private TrainingCourseResponse.Item toItem(TrainingCourseItem i) {
        boolean ready = switch (i.getItemType()) {
            case VIDEO    -> i.getDocumentId() != null && i.getDurationSeconds() != null;
            case DOCUMENT -> i.getDocumentId() != null;
            case LINK     -> i.getExternalUrl() != null;
            case TEXT     -> i.getBody() != null;
        };
        String badge = i.getDurationSeconds() != null
                ? (i.getDurationSeconds() / 60) + "m " + (i.getDurationSeconds() % 60) + "s"
                : i.getItemType().name();

        return TrainingCourseResponse.Item.builder()
                .id(i.getId())
                .ref(i.getItemType().name())
                .title(i.getTitle())
                .status(ready ? "READY" : "INCOMPLETE")
                .badge(badge)
                .linkNote(i.isRequired() ? null : "Optional")
                .itemType(i.getItemType().name())
                .durationSeconds(i.getDurationSeconds())
                .sortOrder(i.getSortOrder())
                .isRequired(i.isRequired())
                .build();
    }

    /**
     * Readable by this tenant: their own course, or any platform library one.
     * Read is wider than write on purpose — a tenant must be able to inspect a
     * library course before assigning it.
     */
    @Transactional(readOnly = true)
    public TrainingCourse loadReadable(Long id, Long tenantId) {
        return courseRepository.findById(id)
                .filter(c -> !c.isDeleted())
                .filter(c -> c.getTenantId() == null || tenantId.equals(c.getTenantId()))
                .orElseThrow(() -> new ResourceNotFoundException("TrainingCourse", id));
    }

    /**
     * Questions as an AUTHOR sees them, for the generic linked tab.
     *
     * Still no isCorrect. An author who needs to check an answer opens the
     * question; shipping correctness to any browser is how it reaches a learner,
     * and the tab is rendered by the same generic component the learner uses
     * elsewhere.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listQuestionsForAuthor(Long courseId) {
        List<TrainingQuizQuestion> qs =
                questionRepository.findByCourseIdAndIsActiveTrueOrderBySortOrderAsc(courseId);
        if (qs.isEmpty()) return List.of();

        Map<Long, Long> optionCounts = optionRepository
                .findByQuestionIdIn(qs.stream().map(TrainingQuizQuestion::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(TrainingQuizOption::getQuestionId, Collectors.counting()));

        List<Map<String, Object>> out = new ArrayList<>(qs.size());
        for (TrainingQuizQuestion q : qs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", q.getId());
            m.put("ref", "Q" + (out.size() + 1));
            m.put("title", q.getQuestionText());
            m.put("status", q.getQuestionType().name());
            m.put("badge", optionCounts.getOrDefault(q.getId(), 0L) + " options");
            m.put("linkNote", q.getExplanation());
            out.add(m);
        }
        return out;
    }

    /** Who this course is assigned to, for the generic linked tab. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listAssignmentsForCourse(Long courseId, Long tenantId) {
        List<TrainingAssignment> assignments = assignmentRepository
                .findByTenantIdAndTargetTypeAndTargetIdAndIsDeletedFalse(
                        tenantId, TrainingAssignment.TargetType.COURSE, courseId);
        if (assignments.isEmpty()) return List.of();

        Map<Long, Personnel> byId = new HashMap<>();
        personnelRepository.findAllById(assignments.stream()
                        .map(TrainingAssignment::getPersonnelId).toList())
                .forEach(p -> byId.put(p.getId(), p));

        List<Map<String, Object>> out = new ArrayList<>(assignments.size());
        for (TrainingAssignment a : assignments) {
            Personnel p = byId.get(a.getPersonnelId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("ref", p != null ? p.getPersonRef() : null);
            m.put("title", p != null ? p.getFullName() : "(removed)");
            m.put("status", a.isOverdue() ? "OVERDUE" : a.getStatus().name());
            m.put("badge", a.getCompletedAt() != null
                    ? "Completed " + a.getCompletedAt().toLocalDate()
                    : (a.getDueAt() != null ? "Due " + a.getDueAt().toLocalDate() : null));
            m.put("navEntityType", "training_assignment");
            out.add(m);
        }
        return out;
    }

    /**
     * A course this caller may modify.
     *
     * Ownership is caller-aware, not a property of the row: SYSTEM owns the
     * platform library, a tenant owns its own. The previous version refused
     * every global course outright, which locked the SYSTEM author out of the
     * library they had just written into.
     */
    private TrainingCourse requireEditableCourse(Long id, Long tenantId, boolean systemUser) {
        TrainingCourse c = courseRepository.findById(id)
                .filter(x -> !x.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("TrainingCourse", id));

        if (c.isLibraryCourse() && !systemUser) {
            throw new ForbiddenException(
                    "This is a platform library course. Assign it as it is, or author your own.");
        }
        if (!c.isLibraryCourse() && !tenantId.equals(c.getTenantId())) {
            throw new ResourceNotFoundException("TrainingCourse", id);
        }
        return c;
    }

    private TrainingCourse requireDraftCourse(Long id, Long tenantId, boolean systemUser) {
        TrainingCourse c = requireEditableCourse(id, tenantId, systemUser);
        if (c.getStatus() != TrainingCourse.Status.DRAFT) {
            throw new ValidationException(
                    "Content and questions can only change while the course is a draft. Changing them "
                            + "under people who have already passed would silently invalidate their "
                            + "records — archive it and publish a new version instead.");
        }
        return c;
    }

    private String resolveRef(String supplied, Long scope) {
        String trimmed = trimToNull(supplied);
        if (trimmed != null && !courseRepository.existsByCourseRefAndTenantId(trimmed, scope)) {
            return trimmed;
        }
        long seq = courseRepository.nextCourseRefSequence(scope);
        String candidate = String.format("TRN-%d-%03d", LocalDate.now().getYear(), seq);
        int guard = 0;
        while (courseRepository.existsByCourseRefAndTenantId(candidate, scope) && guard++ < 1000) {
            candidate = String.format("TRN-%d-%03d", LocalDate.now().getYear(), ++seq);
        }
        return candidate;
    }

    private static boolean isBlank(String s)    { return s == null || s.isBlank(); }
    private static String  nullToEmpty(String s){ return s == null ? "" : s; }
    private static String  trimToNull(String s) { return isBlank(s) ? null : s.trim(); }
}