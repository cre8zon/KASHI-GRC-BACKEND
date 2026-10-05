package com.kashi.grc.training.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.dto.PaginatedResponse;
import com.kashi.grc.common.repository.DbRepository;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.training.domain.TrainingCourse;
import com.kashi.grc.training.dto.*;
import com.kashi.grc.training.repository.TrainingCourseItemRepository;
import com.kashi.grc.training.repository.TrainingQuizQuestionRepository;
import com.kashi.grc.training.domain.TenantCourseRequirement;
import com.kashi.grc.training.repository.TenantCourseRequirementRepository;
import com.kashi.grc.training.service.TrainingAutoAssignService;
import com.kashi.grc.training.service.TrainingCourseService;
import com.kashi.grc.usermanagement.domain.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Training course authoring — ISO 27001 A.6.3, SOC 2 CC1.4.
 *
 * Paths are named by 16_training_seed.sql:
 *   POST /v1/training/courses                training_course_create_form, CREATE_COURSE
 *   PUT  /v1/training/courses/{id}           _header, _tab_overview
 *   POST /v1/training/courses/{id}/items     training_item_form, COURSE_ADD_ITEM
 *   POST /v1/training/courses/{id}/questions training_question_form, COURSE_ADD_QUESTION
 *   POST /v1/training/courses/{id}/publish   COURSE_PUBLISH
 *   POST /v1/training/courses/{id}/archive   COURSE_ARCHIVE
 *   POST /v1/training/courses/{id}/assign    training_assign_form, COURSE_ASSIGN
 *   GET  /v1/training/courses/{id}/linked-items       tabs_json
 *   GET  /v1/training/courses/{id}/linked-questions   tabs_json
 *   GET  /v1/training/courses/{id}/linked-assignments tabs_json
 */
@Slf4j
@RestController
@RequestMapping("/v1/training/courses")
@Tag(name = "Training Courses", description = "Authoring, publishing and assigning training")
@RequiredArgsConstructor
public class TrainingCourseController {

    private final TrainingCourseService          courseService;
    private final TrainingAutoAssignService      autoAssignService;
    private final TenantCourseRequirementRepository requirementRepository;
    private final TrainingCourseItemRepository   itemRepository;
    private final TrainingQuizQuestionRepository questionRepository;
    private final UtilityService                 utilityService;
    private final DbRepository                   dbRepository;

    // ── Upload ────────────────────────────────────────────────────────────────
    //
    // There is deliberately NO upload endpoint here.
    //
    // An earlier version added POST /upload-url, which duplicated a complete
    // pipeline that already existed: DocumentController's requestUpload ->
    // PUT to S3 -> confirmUpload, wrapped by useDocumentUpload on the frontend
    // and already proven by evidence uploads. TrainingContentTab calls that
    // with documentType TRAINING_VIDEO, which is what widens the MIME list and
    // the size ceiling in StorageService, and posts the resulting document id
    // to /items.
    //
    // Two upload paths would have meant two sets of validation, two places for
    // the size cap to drift, and files that only one module could account for.

    // ── Create / list ─────────────────────────────────────────────────────────

    /**
     * platformScope is driven by the caller's side, not by a request field: a
     * SYSTEM user authors the platform library, everyone else authors for their
     * own tenant. Taking it from the payload would let any tenant write into
     * the shared library.
     */
    @PostMapping
    @Operation(summary = "Create a course")
    public ResponseEntity<ApiResponse<TrainingCourseResponse>> create(
            @Valid @RequestBody TrainingCourseRequest req) {
        User ctx = utilityService.getLoggedInDataContext();
        boolean platformScope = isPlatformSide();
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                courseService.create(req, platformScope, ctx.getId(), ctx.getTenantId())));
    }

    /** origin=GLOBAL returns the platform library; otherwise the tenant's own. */
    @GetMapping
    @Operation(summary = "List courses — origin=GLOBAL platform only, ORG tenant only, omitted for both")
    public ResponseEntity<ApiResponse<PaginatedResponse<Map<String, Object>>>> list(
            @RequestParam Map<String, String> allParams) {

        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();

        // One query for the page, so the list can show which courses this
        // tenant requires without opening each one.
        final java.util.Set<Long> requiredIds = autoAssignService.requiredCourseIds(tenantId);

        // ── origin HAS THREE STATES, NOT TWO ──────────────────────────────────
        // GLOBAL  -> the platform library only   (tenant_id IS NULL)
        // ORG     -> this tenant's own only      (tenant_id = me)
        // absent  -> BOTH
        //
        // The first version implemented only two, treating "absent" as ORG. A
        // SYSTEM user then authored into the platform library — isPlatformSide()
        // is true for them, so the row is written with tenant_id NULL — and the
        // default list showed tenant_id = 1, so the course they had just created
        // was invisible with no way to reach it: TRAINING_COURSE has no adopt
        // action, so the All/Platform/Custom toggle does not render either.
        //
        // "Both" is what the segmented control calls All, and it is the right
        // default here: a tenant browsing the library wants to see what they can
        // assign alongside what they wrote.
        String origin = allParams.get("origin");
        boolean libraryOnly = "GLOBAL".equalsIgnoreCase(origin);
        boolean tenantOnly  = "ORG".equalsIgnoreCase(origin);

        return ResponseEntity.ok(ApiResponse.success(dbRepository.findAll(
                TrainingCourse.class,
                utilityService.getpageDetails(allParams),
                (cb, root) -> {
                    List<jakarta.persistence.criteria.Predicate> preds = new ArrayList<>();
                    if (libraryOnly)      preds.add(cb.isNull(root.get("tenantId")));
                    else if (tenantOnly)  preds.add(cb.equal(root.get("tenantId"), tenantId));
                    else                  preds.add(cb.or(cb.isNull(root.get("tenantId")),
                                cb.equal(root.get("tenantId"), tenantId)));
                    preds.add(cb.isFalse(root.get("isDeleted")));
                    if (allParams.containsKey("status")) {
                        preds.add(cb.equal(root.get("status"),
                                TrainingCourse.Status.valueOf(allParams.get("status").toUpperCase())));
                    }
                    if (allParams.containsKey("category")) {
                        preds.add(cb.equal(root.get("category"), allParams.get("category").toUpperCase()));
                    }
                    return preds;
                },
                (cb, root) -> {
                    Map<String, jakarta.persistence.criteria.Path<?>> f = new HashMap<>();
                    f.put("title",      root.get("title"));
                    f.put("course_ref", root.get("courseRef"));
                    f.put("courseref",  root.get("courseRef"));
                    f.put("category",   root.get("category"));
                    f.put("status",     root.get("status"));
                    f.put("created_at", root.get("createdAt"));
                    return f;
                },
                c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",               c.getId());
                    m.put("courseRef",        c.getCourseRef());
                    m.put("title",            c.getTitle());
                    m.put("category",         c.getCategory() != null ? c.getCategory() : "");
                    m.put("estimatedMinutes", c.getEstimatedMinutes());
                    m.put("status",           c.getStatus());
                    m.put("itemCount",        itemRepository.countByCourseIdAndIsDeletedFalse(c.getId()));
                    m.put("questionCount",    questionRepository.countByCourseIdAndIsActiveTrue(c.getId()));
                    m.put("origin",           c.getTenantId() == null ? "GLOBAL" : "ORG");
                    // Caller-aware, matching toResponse: SYSTEM owns the
                    // library, a tenant owns its own. Getting this wrong on the
                    // LIST hides the row actions as well as the detail buttons.
                    m.put("editable",         c.isOwnedBy(tenantId, isPlatformSide())
                            && c.getStatus() == TrainingCourse.Status.DRAFT);
                    m.put("contentVersion",   c.getContentVersion());
                    m.put("requiredOnJoining", requiredIds.contains(c.getId()));
                    m.put("createdAt",        c.getCreatedAt());
                    return m;
                })));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one course with its content")
    public ResponseEntity<ApiResponse<TrainingCourseResponse>> getById(@PathVariable Long id) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        TrainingCourse c = courseService.loadReadable(id, tenantId);
        return ResponseEntity.ok(ApiResponse.success(courseService.toResponse(c, tenantId, isPlatformSide())));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a course")
    public ResponseEntity<ApiResponse<TrainingCourseResponse>> update(
            @PathVariable Long id, @Valid @RequestBody TrainingCourseRequest req) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                courseService.update(id, req, ctx.getId(), ctx.getTenantId(), isPlatformSide())));
    }

    // ── Content and quiz ──────────────────────────────────────────────────────

    @PostMapping("/{id}/items")
    @Operation(summary = "Add content — draft courses only")
    public ResponseEntity<ApiResponse<TrainingCourseResponse>> addItem(
            @PathVariable Long id, @Valid @RequestBody TrainingItemRequest req) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                courseService.addItem(id, req, ctx.getId(), ctx.getTenantId(), isPlatformSide())));
    }

    @PostMapping("/{id}/questions")
    @Operation(summary = "Add a quiz question — draft courses only")
    public ResponseEntity<ApiResponse<TrainingCourseResponse>> addQuestion(
            @PathVariable Long id, @Valid @RequestBody TrainingQuestionRequest req) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                courseService.addQuestion(id, req, ctx.getId(), ctx.getTenantId(), isPlatformSide())));
    }

    @GetMapping("/{id}/linked-items")
    @Operation(summary = "Course content, for the generic linked tab")
    public ResponseEntity<ApiResponse<List<TrainingCourseResponse.Item>>> linkedItems(
            @PathVariable Long id) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(
                courseService.toResponse(courseService.loadReadable(id, tenantId), tenantId,
                        isPlatformSide()).getItems()));
    }

    /**
     * Quiz questions for the AUTHOR. Correct answers are still not included —
     * an author who needs to check one opens the question, and shipping
     * isCorrect to any browser is how it leaks to a learner.
     */
    @GetMapping("/{id}/linked-questions")
    @Operation(summary = "Quiz questions, for the generic linked tab")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> linkedQuestions(
            @PathVariable Long id) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        courseService.loadReadable(id, tenantId);
        return ResponseEntity.ok(ApiResponse.success(courseService.listQuestionsForAuthor(id)));
    }

    @GetMapping("/{id}/linked-assignments")
    @Operation(summary = "Who this course is assigned to, for the generic linked tab")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> linkedAssignments(
            @PathVariable Long id) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(
                courseService.listAssignmentsForCourse(id, tenantId)));
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @PostMapping("/{id}/publish")
    @Operation(summary = "Publish — refuses a course that could not be completed")
    public ResponseEntity<ApiResponse<TrainingCourseResponse>> publish(@PathVariable Long id) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                courseService.publish(id, ctx.getId(), ctx.getTenantId(), isPlatformSide())));
    }

    @PostMapping("/{id}/archive")
    @Operation(summary = "Archive — existing assignments and their records are kept")
    public ResponseEntity<ApiResponse<TrainingCourseResponse>> archive(
            @PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        User ctx = utilityService.getLoggedInDataContext();
        Object r = body == null ? null : body.get("remarks");
        return ResponseEntity.ok(ApiResponse.success(courseService.archive(
                id, r == null ? null : String.valueOf(r), ctx.getId(), ctx.getTenantId(),
                isPlatformSide())));
    }

    @PostMapping("/{id}/unpublish")
    @Operation(summary = "Reopen for editing — bumps the content version, keeps existing records")
    public ResponseEntity<ApiResponse<TrainingCourseResponse>> unpublish(
            @PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        User ctx = utilityService.getLoggedInDataContext();
        Object r = body == null ? null : body.get("remarks");
        return ResponseEntity.ok(ApiResponse.success(courseService.unpublish(
                id, r == null ? null : String.valueOf(r), ctx.getId(), ctx.getTenantId(),
                isPlatformSide())));
    }

    /**
     * Mark this course required for everyone who joins from now on.
     *
     * Deliberately NOT retrospective. Ticking a box should not hand an
     * organisation a hundred overdue rows for people who have been here for
     * years — backfill=true is the explicit way to do that, and it is a
     * separate decision made with the number in front of you.
     *
     * The requirement is recorded against the TENANT, never the course, so a
     * platform library course can be mandatory at one organisation and optional
     * at another. That is the whole distinction between the platform offering
     * training and an organisation requiring it.
     */
    @PostMapping("/{id}/require")
    @Operation(summary = "Require for joiners — not retrospective unless backfill=true")
    public ResponseEntity<ApiResponse<Map<String, Object>>> require(
            @PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        User ctx = utilityService.getLoggedInDataContext();
        Long tenantId = ctx.getTenantId();

        // Readable, so a tenant can require a platform library course.
        courseService.loadReadable(id, tenantId);

        int dueDays = body != null && body.get("dueDays") != null
                ? Integer.parseInt(String.valueOf(body.get("dueDays"))) : 30;
        boolean backfill = body != null && Boolean.parseBoolean(String.valueOf(body.get("backfill")));

        TenantCourseRequirement r = requirementRepository
                .findByTenantIdAndCourseId(tenantId, id)
                .orElseGet(() -> TenantCourseRequirement.builder()
                        .tenantId(tenantId).courseId(id).createdBy(ctx.getId()).build());
        r.setRequiredOnJoining(true);
        r.setDueDays(dueDays);
        r.setActive(true);
        if (body != null && body.get("recurrenceMonths") != null) {
            r.setRecurrenceMonths(Integer.parseInt(String.valueOf(body.get("recurrenceMonths"))));
        }
        requirementRepository.save(r);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("required", true);
        out.put("dueDays", dueDays);
        out.put("backfilled", backfill
                ? autoAssignService.assignToExistingStaff(tenantId, id, ctx.getId()) : 0);
        out.put("message", backfill
                ? "Required for joiners, and assigned to existing staff."
                : "Required for everyone who joins from now on. Existing staff are unaffected — "
                  + "use Assign, or send backfill=true.");
        return ResponseEntity.ok(ApiResponse.success(out));
    }

    /**
     * Stop requiring it.
     *
     * Assignments already made are untouched: their completion records are
     * evidence, and withdrawing a requirement does not un-do the fact that
     * people did the training. Outstanding ones also stay — somebody halfway
     * through a course should be allowed to finish it.
     */
    @PostMapping("/{id}/unrequire")
    @Operation(summary = "Stop assigning to new joiners — existing assignments are untouched")
    public ResponseEntity<ApiResponse<Void>> unrequire(@PathVariable Long id) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        requirementRepository.findByTenantIdAndCourseId(tenantId, id)
                .ifPresent(r -> { r.setActive(false); requirementRepository.save(r); });
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PostMapping("/{id}/assign")
    @Operation(summary = "Assign to people or a department")
    public ResponseEntity<ApiResponse<Map<String, Object>>> assign(
            @PathVariable Long id, @RequestBody(required = false) TrainingAssignRequest req) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(courseService.assignCourse(
                id, req != null ? req : new TrainingAssignRequest(), ctx.getId(), ctx.getTenantId())));
    }

    /**
     * Platform scope comes from the caller's ROLE SIDE, never from the payload.
     *
     * utilityService.isSystemUser() is the existing check for this — the UCF
     * controller uses the same one. Taking it from a request field would let any
     * tenant write into the shared library, and hardcoding a tenant id would
     * break in every environment but the one it was written in.
     */
    private boolean isPlatformSide() {
        return utilityService.isSystemUser();
    }
}