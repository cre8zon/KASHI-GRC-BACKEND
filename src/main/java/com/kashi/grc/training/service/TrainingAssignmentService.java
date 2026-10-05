package com.kashi.grc.training.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashi.grc.common.exception.ForbiddenException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.exception.ValidationException;
import com.kashi.grc.document.domain.Document;
import com.kashi.grc.document.repository.DocumentRepository;
import com.kashi.grc.document.service.StorageService;
import com.kashi.grc.personnel.domain.Personnel;
import com.kashi.grc.personnel.repository.PersonnelRepository;
import com.kashi.grc.training.domain.*;
import com.kashi.grc.training.dto.*;
import com.kashi.grc.training.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * TrainingAssignmentService — what a learner does, and whether it counts.
 *
 * ── THE COMPLETION BAR HAS THREE PARTS, AND ALL THREE ARE NEEDED ──────────
 * A video item counts as watched only when all of these hold:
 *
 *   1. CONTIGUOUS COVERAGE — maxPositionSeconds reached at least
 *      requiredWatchPercent of the duration. Catches abandoning halfway.
 *   2. ACTUAL PLAYBACK — watchedSeconds within tolerance of that same bar.
 *      Catches dragging the scrubber to the end, which moves the playhead
 *      without playing anything.
 *   3. WALL-CLOCK ELAPSED — at least WALL_CLOCK_FLOOR of the duration between
 *      the first and last heartbeat. Catches 2x playback, and catches a
 *      client that simply lies about playedSeconds.
 *
 * Any one of the three alone is trivially defeated, which is why the pair of
 * counters exists on TrainingProgress rather than a single percentage.
 *
 * The honest limitation: a video left playing in a background tab satisfies all
 * three. Detecting that needs focus and visibility events, which a determined
 * user can also forge. It is recorded in GAPS rather than pretended away.
 *
 * ── GRADING IS SERVER-SIDE, ALWAYS ────────────────────────────────────────
 * Options are loaded fresh from the database inside gradeQuiz. Nothing the
 * browser sends carries correctness, and nothing returned to it does either.
 *
 * ── SCOPING ───────────────────────────────────────────────────────────────
 * Every learner path goes through requireOwnAssignment, which resolves the
 * caller to a personnel row via personnel.user_id and refuses anything that is
 * not theirs. A training administrator reads through the reporting path
 * instead. Nobody completes somebody else's training.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrainingAssignmentService {

    private final TrainingAssignmentRepository   assignmentRepository;
    private final TrainingCourseRepository       courseRepository;
    private final TrainingCourseItemRepository   itemRepository;
    private final TrainingQuizQuestionRepository questionRepository;
    private final TrainingQuizOptionRepository   optionRepository;
    private final TrainingProgressRepository     progressRepository;
    private final TrainingQuizAttemptRepository  attemptRepository;
    private final PersonnelRepository            personnelRepository;
    private final DocumentRepository             documentRepository;
    private final StorageService                 storageService;
    private final ObjectMapper                   objectMapper;

    /**
     * Wall-clock elapsed must be at least this fraction of the item duration.
     * Below 1.0 because pausing, buffering and rewatching a section are all
     * normal; 0.8 leaves room for those while still catching 2x playback.
     */
    private static final double WALL_CLOCK_FLOOR = 0.8;

    /**
     * Fraction of the item duration that must be credited as actually played.
     *
     * Deliberately BELOW requiredWatchPercent. See isItemComplete for the
     * measurements that set it: accumulated playback runs a few percent under
     * real playback for legitimate reasons, so holding this at the same bar as
     * contiguous coverage failed people who had watched the whole video.
     */
    private static final double WATCHED_SECONDS_FLOOR = 0.75;

    /**
     * A heartbeat may claim at most this multiple of the wall-clock gap since
     * the previous one. Slightly above 1.0 so a slow request or a GC pause does
     * not lose a learner a few honest seconds.
     */
    private static final double HEARTBEAT_TOLERANCE = 1.25;

    /** Playback URLs are minted per request and expire. Never stored. */
    private static final boolean PLAYBACK_URL_SHORT_TTL = true;

    // ═════════════════════════════════════════════════════════════════════════
    // READ
    // ═════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public TrainingAssignmentResponse getForLearner(Long id, Long userId, Long tenantId) {
        TrainingAssignment a = requireOwnAssignment(id, userId, tenantId);
        return toLearnerResponse(a, tenantId);
    }

    /** Reporting view — no playback URLs, no quiz. For training:report holders. */
    @Transactional(readOnly = true)
    public TrainingAssignmentResponse getForReport(Long id, Long tenantId) {
        TrainingAssignment a = assignmentRepository
                .findByIdAndTenantIdAndIsDeletedFalse(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("TrainingAssignment", id));
        return toSummaryResponse(a, tenantId);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PROGRESS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * The player heartbeat.
     *
     * playedSeconds from the client is CLAMPED to the wall-clock gap since the
     * previous heartbeat. A client claiming ten minutes of playback ten seconds
     * after its last call gets credited ten seconds, because that is all the
     * time there was.
     *
     * maxPositionSeconds only ever increases, so rewinding to rewatch a section
     * does not undo progress — but it also means seeking forward records that
     * the playhead got there, which is exactly why watchedSeconds is the second
     * gate rather than the only one.
     */
    @Transactional
    public TrainingAssignmentResponse recordProgress(Long id, TrainingProgressRequest req,
                                                     Long userId, Long tenantId) {
        TrainingAssignment a = requireOwnAssignment(id, userId, tenantId);
        requireActive(a);

        TrainingCourseItem item = itemRepository.findById(req.getItemId())
                .filter(i -> !i.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("TrainingCourseItem", req.getItemId()));
        if (!item.getCourseId().equals(a.getTargetId())
                || a.getTargetType() != TrainingAssignment.TargetType.COURSE) {
            throw new ValidationException("That content item does not belong to this assignment.");
        }

        LocalDateTime now = LocalDateTime.now();
        TrainingProgress p = progressRepository
                .findByAssignmentIdAndItemId(a.getId(), item.getId())
                .orElseGet(() -> TrainingProgress.builder()
                        .tenantId(tenantId).assignmentId(a.getId()).itemId(item.getId())
                        .firstPlayedAt(now).build());

        int claimed = req.getPlayedSeconds() != null ? Math.max(0, req.getPlayedSeconds()) : 0;
        int allowed = claimed;
        if (p.getLastPlayedAt() != null) {
            long gapSeconds = Duration.between(p.getLastPlayedAt(), now).toSeconds();
            allowed = (int) Math.min(claimed, Math.max(0, gapSeconds * HEARTBEAT_TOLERANCE));
            if (allowed < claimed) {
                log.debug("[TRAINING] Heartbeat clamped | assignmentId={} itemId={} claimed={}s allowed={}s",
                        id, item.getId(), claimed, allowed);
            }
        }

        p.setWatchedSeconds(p.getWatchedSeconds() + allowed);
        p.setMaxPositionSeconds(Math.max(p.getMaxPositionSeconds(),
                req.getPositionSeconds() != null ? req.getPositionSeconds() : 0));
        if (p.getFirstPlayedAt() == null) p.setFirstPlayedAt(now);
        p.setLastPlayedAt(now);

        TrainingCourse course = loadCourse(a);
        if (p.getCompletedAt() == null && isItemComplete(item, p, course)) {
            p.setCompletedAt(now);
            log.info("[TRAINING] Item complete | assignmentId={} itemId={} | watched={}s of {}s",
                    id, item.getId(), p.getWatchedSeconds(), item.getDurationSeconds());
        }
        progressRepository.save(p);

        if (a.getStatus() == TrainingAssignment.Status.ASSIGNED) {
            a.setStatus(TrainingAssignment.Status.IN_PROGRESS);
            a.setStartedAt(a.getStartedAt() != null ? a.getStartedAt() : now);
            assignmentRepository.save(a);
        }
        return toLearnerResponse(a, tenantId);
    }

    /**
     * The three-part bar. See the class javadoc for why each part is needed.
     *
     * A non-video item has nothing to measure, so reaching it is completion —
     * documents, links and text blocks are acknowledged rather than watched.
     */
    private boolean isItemComplete(TrainingCourseItem item, TrainingProgress p, TrainingCourse course) {
        if (!item.isWatchTracked()) return true;

        Integer duration = item.getDurationSeconds();
        if (duration == null || duration <= 0) {
            // Cannot be measured, so cannot be claimed. publish() refuses to let
            // a course reach a learner in this state; this is the second line.
            log.warn("[TRAINING] Item {} has no duration — completion cannot be evaluated", item.getId());
            return false;
        }

        int bar = (int) Math.floor(duration * (course.getRequiredWatchPercent() / 100.0));

        boolean reachedEnd = p.getMaxPositionSeconds() >= bar;

        // ── GATE 2 NEEDS A TOLERANCE, AND HERE IS THE EVIDENCE ────────────────
        // Held at the same bar as gate 1, this failed three consecutive HONEST
        // viewings: 50 of 52 required, 40 of 45, 38 of 45 — each with the
        // playhead at the end of the video.
        //
        // Accumulated playback is always slightly less than real playback, and
        // every one of the losses is legitimate: the player ignores any
        // timeupdate jump of two seconds or more as a seek, so buffering stalls
        // contribute nothing; each heartbeat is a whole number of seconds; and
        // the server clamps a claim to the wall-clock gap. Together they shave
        // a few percent off every genuine view.
        //
        // So gate 2 measures against 75% of duration rather than the 90% bar.
        // It still does its job — scrubbing straight to the end yields a
        // watched count near ZERO, nowhere near 75% — while no longer failing
        // people who sat through the whole thing. Gates 1 and 3 are untouched.
        int playedBar = (int) Math.floor(duration * WATCHED_SECONDS_FLOOR);
        boolean actuallyPlayed = p.getWatchedSeconds() >= playedBar;

        boolean enoughWallClock = true;
        if (p.getFirstPlayedAt() != null && p.getLastPlayedAt() != null) {
            long elapsed = Duration.between(p.getFirstPlayedAt(), p.getLastPlayedAt()).toSeconds();
            enoughWallClock = elapsed >= (long) (duration * WALL_CLOCK_FLOOR);
        }
        return reachedEnd && actuallyPlayed && enoughWallClock;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // QUIZ
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Grades an attempt against options read fresh from the database.
     *
     * A question is right only when the chosen set EQUALS the correct set —
     * picking one of two correct answers is wrong, not half right. Partial
     * credit on a compliance quiz would let someone pass by guessing broadly.
     *
     * Every attempt is stored, pass or fail. "Passed on the fourth try" is a
     * different fact from "passed".
     */
    @Transactional
    public TrainingAssignmentResponse submitQuiz(Long id, TrainingQuizSubmissionRequest req,
                                                 Long userId, Long tenantId) {
        TrainingAssignment a = requireOwnAssignment(id, userId, tenantId);
        requireActive(a);

        TrainingCourse course = loadCourse(a);
        if (!course.hasQuiz()) {
            throw new ValidationException("This course has no quiz.");
        }
        if (!allRequiredContentComplete(a, course)) {
            throw new ValidationException(
                    "Finish the course content before taking the quiz.");
        }

        List<TrainingQuizQuestion> questions =
                questionRepository.findByCourseIdAndIsActiveTrueOrderBySortOrderAsc(course.getId());
        if (questions.isEmpty()) {
            throw new ValidationException(
                    "This course is marked as having a quiz but no questions are configured.");
        }

        Map<Long, Set<Long>> correctByQuestion = optionRepository
                .findByQuestionIdIn(questions.stream().map(TrainingQuizQuestion::getId).toList())
                .stream()
                .filter(TrainingQuizOption::isCorrect)
                .collect(Collectors.groupingBy(TrainingQuizOption::getQuestionId,
                        Collectors.mapping(TrainingQuizOption::getId, Collectors.toSet())));

        Map<Long, List<Long>> submitted = req.getAnswers() != null ? req.getAnswers() : Map.of();
        int right = 0;
        for (TrainingQuizQuestion q : questions) {
            Set<Long> correct = correctByQuestion.getOrDefault(q.getId(), Set.of());
            Set<Long> chosen  = new HashSet<>(submitted.getOrDefault(q.getId(), List.of()));
            if (!correct.isEmpty() && correct.equals(chosen)) right++;
        }
        int scorePercent = (int) Math.round(right * 100.0 / questions.size());
        boolean passed   = scorePercent >= course.getQuizPassPercent();

        int attemptNumber = (int) attemptRepository.countByAssignmentId(a.getId()) + 1;
        String answersJson;
        try {
            answersJson = objectMapper.writeValueAsString(submitted);
        } catch (Exception e) {
            // The attempt matters more than the audit of its inputs; never lose
            // a pass because the answer map would not serialise.
            log.warn("[TRAINING] Could not serialise quiz answers | assignmentId={} | {}", id, e.getMessage());
            answersJson = null;
        }

        attemptRepository.save(TrainingQuizAttempt.builder()
                .tenantId(tenantId).assignmentId(a.getId())
                .attemptNumber(attemptNumber).scorePercent(scorePercent)
                .passed(passed).answersJson(answersJson).attemptedAt(LocalDateTime.now())
                .build());

        a.setQuizAttempts(attemptNumber);
        // Keep the BEST score, not the latest: a later worse attempt should not
        // revoke a pass already earned.
        if (a.getQuizScorePercent() == null || scorePercent > a.getQuizScorePercent()) {
            a.setQuizScorePercent(scorePercent);
        }
        assignmentRepository.save(a);

        log.info("[TRAINING] Quiz attempt {} | assignmentId={} | {}% | {}",
                attemptNumber, id, scorePercent, passed ? "PASS" : "FAIL");

        maybeComplete(a, course, tenantId);
        return toLearnerResponse(a, tenantId);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // ATTESTATION / ACCEPTANCE
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * The final act, and the same one for a course attestation and a policy
     * acceptance. The IP is captured because an attestation is a statement by a
     * person, and who said it, when and from where is the whole of its
     * evidential weight.
     */
    @Transactional
    public TrainingAssignmentResponse attest(Long id, TrainingAttestRequest req,
                                             String remoteIp, Long userId, Long tenantId) {
        TrainingAssignment a = requireOwnAssignment(id, userId, tenantId);
        requireActive(a);

        if (!Boolean.TRUE.equals(req.getConfirmed())) {
            throw new ValidationException(
                    "Confirm that you have read and understood the material.");
        }

        if (a.getTargetType() == TrainingAssignment.TargetType.COURSE) {
            TrainingCourse course = loadCourse(a);
            if (!allRequiredContentComplete(a, course)) {
                throw new ValidationException(
                        "Finish the course content before confirming.");
            }
            if (course.hasQuiz() && !hasPassedQuiz(a, course)) {
                throw new ValidationException(
                        "Pass the quiz before confirming. You can retake it as many times as you need.");
            }
        }

        a.setAttestedAt(LocalDateTime.now());
        a.setAttestationIp(remoteIp);
        assignmentRepository.save(a);
        log.info("[TRAINING] Attested | assignmentId={} | {} | ip={}",
                id, a.getTargetType(), remoteIp);

        maybeComplete(a, a.getTargetType() == TrainingAssignment.TargetType.COURSE
                ? loadCourse(a) : null, tenantId);
        return toLearnerResponse(a, tenantId);
    }

    /**
     * Completion is EARNED, never set by a button. There is deliberately no
     * "mark complete" endpoint: a record that says someone completed training
     * they did not do is worse than no record, because it will be produced in
     * an audit as though it meant something.
     */
    private void maybeComplete(TrainingAssignment a, TrainingCourse course, Long tenantId) {
        if (a.getCompletedAt() != null) return;

        if (a.getTargetType() == TrainingAssignment.TargetType.POLICY) {
            if (a.getAttestedAt() == null) return;
        } else {
            if (course == null) return;
            if (!allRequiredContentComplete(a, course)) return;
            if (course.hasQuiz() && !hasPassedQuiz(a, course)) return;
            if (course.isRequiresAttestation() && a.getAttestedAt() == null) return;
        }

        a.setStatus(TrainingAssignment.Status.COMPLETED);
        a.setCompletedAt(LocalDateTime.now());
        assignmentRepository.save(a);
        log.info("[TRAINING] COMPLETED | assignmentId={} | {} '{}' | personnelId={}",
                a.getId(), a.getTargetType(), a.getTargetTitle(), a.getPersonnelId());
    }

    private boolean allRequiredContentComplete(TrainingAssignment a, TrainingCourse course) {
        List<TrainingCourseItem> required = itemRepository
                .findByCourseIdAndIsDeletedFalseOrderBySortOrderAsc(course.getId()).stream()
                .filter(TrainingCourseItem::isRequired).toList();
        if (required.isEmpty()) return true;

        Map<Long, TrainingProgress> byItem = progressRepository.findByAssignmentId(a.getId())
                .stream().collect(Collectors.toMap(TrainingProgress::getItemId, p -> p, (x, y) -> x));

        for (TrainingCourseItem item : required) {
            TrainingProgress p = byItem.get(item.getId());
            if (p == null || p.getCompletedAt() == null) return false;
        }
        return true;
    }

    private boolean hasPassedQuiz(TrainingAssignment a, TrainingCourse course) {
        return a.getQuizScorePercent() != null
                && course.getQuizPassPercent() != null
                && a.getQuizScorePercent() >= course.getQuizPassPercent();
    }

    /**
     * Completed and total required items per assignment, for a whole page.
     *
     * Two queries regardless of page size: one for every progress row, one for
     * the items of the courses involved. The list needs this for its progress
     * bar, and doing it per row would be two queries per assignment.
     *
     * Returns [done, total] keyed by assignment id. An assignment whose target
     * is a POLICY has no items and is simply absent from the map — the caller
     * renders that as no bar rather than as 0%.
     */
    @Transactional(readOnly = true)
    public Map<Long, int[]> itemProgressFor(Collection<Long> assignmentIds, Long tenantId) {
        if (assignmentIds == null || assignmentIds.isEmpty()) return Map.of();

        List<TrainingAssignment> assignments = assignmentRepository.findAllById(assignmentIds).stream()
                .filter(a -> tenantId.equals(a.getTenantId()) && !a.isDeleted())
                .filter(a -> a.getTargetType() == TrainingAssignment.TargetType.COURSE)
                .toList();
        if (assignments.isEmpty()) return Map.of();

        Set<Long> courseIds = assignments.stream()
                .map(TrainingAssignment::getTargetId).collect(Collectors.toSet());

        // Required items only: an optional item cannot hold up completion, so
        // counting it would show 3/9 for somebody who is actually finished.
        Map<Long, Long> requiredByCourse = itemRepository
                .findByCourseIdInAndIsDeletedFalse(courseIds).stream()
                .filter(TrainingCourseItem::isRequired)
                .collect(Collectors.groupingBy(TrainingCourseItem::getCourseId, Collectors.counting()));

        Map<Long, Long> doneByAssignment = new HashMap<>();
        for (Long aid : assignments.stream().map(TrainingAssignment::getId).toList()) {
            long done = progressRepository.findByAssignmentId(aid).stream()
                    .filter(pr -> pr.getCompletedAt() != null).count();
            doneByAssignment.put(aid, done);
        }

        Map<Long, int[]> out = new HashMap<>();
        for (TrainingAssignment a : assignments) {
            long total = requiredByCourse.getOrDefault(a.getTargetId(), 0L);
            long done  = doneByAssignment.getOrDefault(a.getId(), 0L);
            out.put(a.getId(), new int[]{(int) Math.min(done, total), (int) total});
        }
        return out;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // ADMINISTRATION
    // ═════════════════════════════════════════════════════════════════════════

    /** Cheap ownership probe for the controller, without loading the full view. */
    @Transactional(readOnly = true)
    public boolean isOwnedBy(Long assignmentId, Long personnelId, Long tenantId) {
        return assignmentRepository.findByIdAndTenantIdAndIsDeletedFalse(assignmentId, tenantId)
                .map(a -> a.getPersonnelId().equals(personnelId))
                .orElse(false);
    }

    /**
     * For an assignment made in error.
     *
     * A COMPLETED assignment is never cancelled: it is the evidence that someone
     * did the training, and withdrawing the requirement afterwards does not
     * un-do the fact. When the training genuinely does not apply to a person, a
     * TRAINING exclusion on their personnel record is the right tool — it says
     * so, with a reason and an owner.
     */
    @Transactional
    public void cancel(Long id, String remarks, Long userId, Long tenantId) {
        TrainingAssignment a = assignmentRepository
                .findByIdAndTenantIdAndIsDeletedFalse(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("TrainingAssignment", id));

        if (a.getCompletedAt() != null) {
            throw new ValidationException(
                    "This has already been completed and its record is evidence. If the requirement no "
                            + "longer applies, record a TRAINING exclusion on the person instead.");
        }

        a.setStatus(TrainingAssignment.Status.CANCELLED);
        a.setUpdatedBy(userId);
        assignmentRepository.save(a);
        log.info("[TRAINING] Assignment cancelled | id={} | by={} | {}", id, userId, remarks);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getStats(Long tenantId) {
        Map<String, Object> stats = new LinkedHashMap<>();
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (Object[] row : assignmentRepository.countByStatusForTenant(tenantId)) {
            byStatus.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        long completed = byStatus.getOrDefault(TrainingAssignment.Status.COMPLETED.name(), 0L);

        stats.put("total", total);
        stats.put("completed", completed);
        stats.put("byStatus", byStatus);
        // The headline: mandatory training that is late right now. Derived from
        // due_at, so it cannot drift out of step with reality.
        stats.put("overdue", assignmentRepository.findOverdue(tenantId).size());
        stats.put("completionPercent", total == 0 ? null
                : (int) Math.round(completed * 100.0 / total));
        return stats;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // MAPPING
    // ═════════════════════════════════════════════════════════════════════════

    private TrainingAssignmentResponse toLearnerResponse(TrainingAssignment a, Long tenantId) {
        TrainingCourse course = a.getTargetType() == TrainingAssignment.TargetType.COURSE
                ? loadCourseOrNull(a) : null;

        List<TrainingAssignmentResponse.PlayerItem> items = List.of();
        List<TrainingAssignmentResponse.QuizQuestion> quiz = List.of();
        int progressPercent = 0;
        boolean contentComplete = true;

        if (course != null) {
            List<TrainingCourseItem> courseItems =
                    itemRepository.findByCourseIdAndIsDeletedFalseOrderBySortOrderAsc(course.getId());
            Map<Long, TrainingProgress> byItem = progressRepository.findByAssignmentId(a.getId())
                    .stream().collect(Collectors.toMap(TrainingProgress::getItemId, p -> p, (x, y) -> x));

            items = courseItems.stream().map(i -> {
                TrainingProgress p = byItem.get(i.getId());
                return TrainingAssignmentResponse.PlayerItem.builder()
                        .id(i.getId())
                        .itemType(i.getItemType().name())
                        .title(i.getTitle())
                        .sortOrder(i.getSortOrder())
                        .isRequired(i.isRequired())
                        .durationSeconds(i.getDurationSeconds())
                        // Minted per request and short-lived. Never persisted,
                        // so a URL copied out of devtools stops working.
                        //
                        // Resolved through the document rather than a stored S3
                        // key, so a file soft-deleted in the document module
                        // stops playing here too instead of remaining reachable.
                        .playbackUrl(playbackUrlFor(i, tenantId))
                        .externalUrl(i.getExternalUrl())
                        .body(i.getBody())
                        .maxPositionSeconds(p != null ? p.getMaxPositionSeconds() : 0)
                        .watchedSeconds(p != null ? p.getWatchedSeconds() : 0)
                        .completed(p != null && p.getCompletedAt() != null)
                        .build();
            }).toList();

            long done = items.stream().filter(i -> Boolean.TRUE.equals(i.getCompleted())).count();
            progressPercent = items.isEmpty() ? 0 : (int) Math.round(done * 100.0 / items.size());
            contentComplete = allRequiredContentComplete(a, course);

            if (course.hasQuiz()) {
                List<TrainingQuizQuestion> qs =
                        questionRepository.findByCourseIdAndIsActiveTrueOrderBySortOrderAsc(course.getId());
                Map<Long, List<TrainingQuizOption>> optionsByQ = optionRepository
                        .findByQuestionIdIn(qs.stream().map(TrainingQuizQuestion::getId).toList())
                        .stream().collect(Collectors.groupingBy(TrainingQuizOption::getQuestionId));

                quiz = qs.stream().map(q -> TrainingAssignmentResponse.QuizQuestion.builder()
                        .id(q.getId())
                        .questionText(q.getQuestionText())
                        .questionType(q.getQuestionType().name())
                        // id and text ONLY. isCorrect never crosses this line.
                        .options(optionsByQ.getOrDefault(q.getId(), List.of()).stream()
                                .sorted(Comparator.comparing(TrainingQuizOption::getSortOrder))
                                .map(o -> TrainingAssignmentResponse.QuizOption.builder()
                                        .id(o.getId()).optionText(o.getOptionText()).build())
                                .toList())
                        .build()).toList();
            }
        } else {
            progressPercent = a.getAttestedAt() != null ? 100 : 0;
        }

        return baseResponse(a, tenantId)
                .progressPercent(a.getCompletedAt() != null ? 100 : progressPercent)
                .allContentComplete(contentComplete)
                .quizRequired(course != null && course.hasQuiz())
                .quizPassed(course != null && course.hasQuiz() && hasPassedQuiz(a, course))
                .attestationRequired(course == null || course.isRequiresAttestation())
                .requiredWatchPercent(course != null ? course.getRequiredWatchPercent() : null)
                .quizPassPercent(course != null ? course.getQuizPassPercent() : null)
                .courseDescription(course != null ? course.getDescription() : null)
                .items(items)
                .quiz(quiz)
                .build();
    }

    /** No playback URLs, no quiz — for the reporting surface. */
    private TrainingAssignmentResponse toSummaryResponse(TrainingAssignment a, Long tenantId) {
        return baseResponse(a, tenantId)
                .progressPercent(a.getCompletedAt() != null ? 100 : 0)
                .build();
    }

    private TrainingAssignmentResponse.TrainingAssignmentResponseBuilder baseResponse(
            TrainingAssignment a, Long tenantId) {
        return TrainingAssignmentResponse.builder()
                .id(a.getId())
                .personnelId(a.getPersonnelId())
                .personName(resolvePersonName(a.getPersonnelId(), tenantId))
                .targetType(a.getTargetType().name())
                .targetId(a.getTargetId())
                .targetVersion(a.getTargetVersion())
                .targetTitle(a.getTargetTitle())
                .status(a.getStatus().name())
                .assignedAt(a.getAssignedAt())
                .dueAt(a.getDueAt())
                .startedAt(a.getStartedAt())
                .completedAt(a.getCompletedAt())
                .attestedAt(a.getAttestedAt())
                .overdue(a.isOverdue())
                .quizScorePercent(a.getQuizScorePercent())
                .quizAttempts(a.getQuizAttempts());
    }

    /**
     * Short-lived presigned GET for a content item, via its document.
     *
     * Returns null rather than throwing when the document is missing or
     * soft-deleted: the player then shows "not available" for that item, which
     * is a far better failure than a 500 on the whole assignment because one
     * video was removed.
     */
    private String playbackUrlFor(TrainingCourseItem item, Long tenantId) {
        if (item.getDocumentId() == null) return null;

        // ── A LIBRARY COURSE'S FILES BELONG TO THE PLATFORM, NOT THE LEARNER ──
        //
        // item.getTenantId() is NULL for a platform library course. Its videos
        // were uploaded by the SYSTEM user, so the document row carries the
        // PLATFORM tenant's id, not the learner's. Looking it up with the
        // learner's tenant found nothing, and the player reported "This video is
        // not available. Its file may still be uploading." for a file that had
        // uploaded perfectly and simply belonged to someone else.
        //
        // Global content is readable by every tenant BY DESIGN — that is the
        // whole purpose of the library — so a library item resolves its document
        // by id alone. A tenant's own course stays tenant-scoped, so this widens
        // nothing that was not already deliberately shared.
        //
        // This replaces an earlier two-step version that tried the learner's
        // tenant first and then fell back through
        // courseRepository.findById(...).map(TrainingCourse::getCreatedBy)
        // .flatMap(ignored -> ...). That fallback discarded the value it mapped,
        // and — worse — produced an EMPTY Optional whenever createdBy was null,
        // so the retry silently never ran for any course created without an
        // author recorded. One condition, no retry, no silent hole.
        //
        // Access is already decided upstream: requireOwnAssignment has
        // established that this learner owns this assignment. The lookup is
        // resolving a reference, not making an access decision.
        Optional<Document> doc = item.getTenantId() == null
                ? documentRepository.findById(item.getDocumentId())
                : documentRepository.findByIdAndTenantId(item.getDocumentId(), tenantId);

        return doc.filter(d -> d.getS3Key() != null)
                .map(d -> storageService.generateDownloadUrl(
                        d.getS3Key(), PLAYBACK_URL_SHORT_TTL, item.getTitle()))
                .orElseGet(() -> {
                    log.warn("[TRAINING] Item {} references document {} which could not be resolved "
                                    + "| itemTenant={} callerTenant={}",
                            item.getId(), item.getDocumentId(), item.getTenantId(), tenantId);
                    return null;
                });
    }

    // ═════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Resolves the caller to their personnel row and refuses anything that is
     * not theirs.
     *
     * A login with no roster row cannot hold training, which is deliberate: the
     * assignment belongs to a person, not an account, so an account nobody has
     * put on the roster has nothing to complete.
     */
    private TrainingAssignment requireOwnAssignment(Long id, Long userId, Long tenantId) {
        TrainingAssignment a = assignmentRepository
                .findByIdAndTenantIdAndIsDeletedFalse(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("TrainingAssignment", id));

        Personnel me = personnelRepository.findByTenantIdAndUserIdAndIsDeletedFalse(tenantId, userId)
                .orElseThrow(() -> new ForbiddenException(
                        "Your account is not linked to a personnel record, so no training is assigned "
                                + "to you. Ask an administrator to add you to the roster."));

        if (!me.getId().equals(a.getPersonnelId())) {
            throw new ForbiddenException("That training is assigned to somebody else.");
        }
        return a;
    }

    private void requireActive(TrainingAssignment a) {
        if (a.getStatus() == TrainingAssignment.Status.CANCELLED) {
            throw new ValidationException("This assignment has been cancelled.");
        }
        if (a.getCompletedAt() != null) {
            throw new ValidationException(
                    "You have already completed this. It stays on your record as evidence.");
        }
    }

    private TrainingCourse loadCourse(TrainingAssignment a) {
        TrainingCourse c = loadCourseOrNull(a);
        if (c == null) throw new ResourceNotFoundException("TrainingCourse", a.getTargetId());
        return c;
    }

    /** Platform and tenant courses alike — a library course is assignable by all. */
    private TrainingCourse loadCourseOrNull(TrainingAssignment a) {
        if (a.getTargetType() != TrainingAssignment.TargetType.COURSE) return null;
        return courseRepository.findById(a.getTargetId())
                .filter(c -> !c.isDeleted())
                .orElse(null);
    }

    private String resolvePersonName(Long personnelId, Long tenantId) {
        if (personnelId == null) return null;
        return personnelRepository.findByIdAndTenantIdAndIsDeletedFalse(personnelId, tenantId)
                .map(Personnel::getFullName).orElse(null);
    }
}