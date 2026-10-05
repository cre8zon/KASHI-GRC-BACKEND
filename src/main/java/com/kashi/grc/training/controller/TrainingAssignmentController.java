package com.kashi.grc.training.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.dto.PaginatedResponse;
import com.kashi.grc.common.repository.DbRepository;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.personnel.domain.Personnel;
import com.kashi.grc.personnel.repository.PersonnelRepository;
import com.kashi.grc.training.domain.TrainingAssignment;
import com.kashi.grc.training.dto.*;
import com.kashi.grc.training.service.TrainingAssignmentService;
import com.kashi.grc.training.service.TrainingCourseService;
import com.kashi.grc.usermanagement.domain.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * My Training — the platform's first employee-facing surface.
 *
 * ── THE LIST SCOPES ITSELF, AND THAT IS THE ONE IMPLICIT BEHAVIOUR HERE ───
 * A caller holding training:report sees every assignment in the organisation.
 * Everyone else sees only their own, resolved through personnel.user_id. This
 * is deliberate: making it a request parameter would mean an ordinary employee
 * could read the whole roster's training status by editing a URL, and making it
 * two endpoints would mean two nav entries and two blueprints for one screen.
 *
 * A login with no personnel row sees an empty list rather than an error. That
 * is the honest answer — training belongs to a person on the roster, and an
 * account nobody has added has nothing assigned.
 *
 * Paths are named by 16_training_seed.sql:
 *   GET  /v1/training/assignments              module_blueprints.api_base_path
 *   GET  /v1/training/assignments/{id}         blueprint detail
 *   POST /v1/training/assignments/{id}/progress   player heartbeat
 *   POST /v1/training/assignments/{id}/quiz       quiz submission
 *   POST /v1/training/assignments/{id}/attest     attestation / policy acceptance
 *   POST /v1/training/assignments/{id}/cancel     ASSIGNMENT_CANCEL
 *   POST /v1/training/assignments/policy          assign a policy for acceptance
 */
@Slf4j
@RestController
@RequestMapping("/v1/training/assignments")
@Tag(name = "My Training", description = "Completing assigned training and accepting policies")
@RequiredArgsConstructor
public class TrainingAssignmentController {

    private final TrainingAssignmentService assignmentService;
    private final TrainingCourseService     courseService;
    private final PersonnelRepository       personnelRepository;
    private final UtilityService            utilityService;
    private final DbRepository              dbRepository;

    // ── List ──────────────────────────────────────────────────────────────────

    @GetMapping
    @Operation(summary = "Assignments — your own, or the whole organisation with training:report")
    public ResponseEntity<ApiResponse<PaginatedResponse<Map<String, Object>>>> list(
            @RequestParam Map<String, String> allParams) {

        User ctx = utilityService.getLoggedInDataContext();
        Long tenantId = ctx.getTenantId();

        // ── ALWAYS THE CALLER'S OWN. NO EXCEPTION FOR training:report. ────────
        //
        // This previously widened to the whole organisation for anyone holding
        // training:report, so a CISO opened "My Training" and saw everybody
        // else's rows. That is not a permission question — the screen is called
        // My Training, and a screen that sometimes means "everyone's training"
        // depending on who is looking is one nobody can trust at a glance.
        //
        // Reporting across the organisation is a different screen and will get
        // its own endpoint. Conflating the two here also made the PERSON column
        // meaningful, which it should never be: on your own list it can only
        // ever say you.
        //
        // -1 rather than skipping the predicate when there is no roster row:
        // omitting it would silently widen the query to the whole tenant, which
        // is the exact failure this comment exists to prevent.
        Long myPersonnelId = personnelRepository
                .findByTenantIdAndUserIdAndIsDeletedFalse(tenantId, ctx.getId())
                .map(Personnel::getId).orElse(null);

        final Long scopeTo = myPersonnelId != null ? myPersonnelId : -1L;

        Map<Long, String> nameCache = new HashMap<>();

        // Assigned to a variable rather than returned inline, so attachProgress
        // can fill the page before it is serialised.
        PaginatedResponse<Map<String, Object>> page = dbRepository.findAll(
                TrainingAssignment.class,
                utilityService.getpageDetails(allParams),
                (cb, root) -> {
                    List<jakarta.persistence.criteria.Predicate> preds = new ArrayList<>();
                    preds.add(cb.equal(root.get("tenantId"), tenantId));
                    preds.add(cb.isFalse(root.get("isDeleted")));
                    preds.add(cb.equal(root.get("personnelId"), scopeTo));

                    if (allParams.containsKey("status")) {
                        preds.add(cb.equal(root.get("status"),
                                TrainingAssignment.Status.valueOf(allParams.get("status").toUpperCase())));
                    }
                    if (allParams.containsKey("targetType")) {
                        preds.add(cb.equal(root.get("targetType"),
                                TrainingAssignment.TargetType.valueOf(
                                        allParams.get("targetType").toUpperCase())));
                    }
                    if ("true".equalsIgnoreCase(allParams.get("outstandingOnly"))) {
                        preds.add(root.get("status").in(TrainingAssignment.Status.ASSIGNED,
                                TrainingAssignment.Status.IN_PROGRESS));
                    }
                    return preds;
                },
                (cb, root) -> {
                    Map<String, jakarta.persistence.criteria.Path<?>> f = new HashMap<>();
                    f.put("target_title", root.get("targetTitle"));
                    f.put("targettitle",  root.get("targetTitle"));
                    f.put("status",       root.get("status"));
                    f.put("targettype",   root.get("targetType"));
                    f.put("dueat",        root.get("dueAt"));
                    f.put("created_at",   root.get("createdAt"));
                    return f;
                },
                a -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",            a.getId());
                    m.put("targetTitle",   a.getTargetTitle());
                    m.put("targetType",    a.getTargetType());
                    m.put("targetVersion", a.getTargetVersion());
                    m.put("status",        a.getStatus());
                    m.put("dueAt",         a.getDueAt());
                    m.put("completedAt",   a.getCompletedAt());
                    // Derived, never stored — a record saying ASSIGNED on a
                    // deadline that passed last night would otherwise be wrong
                    // until some sweep ran.
                    m.put("overdue",       a.isOverdue());
                    // Filled by attachProgress below, in one batch — computing
                    // it per row would fire two queries per assignment.
                    m.put("progressPercent", null);
                    m.put("progressPercentLabel", null);
                    m.put("personnelId",   a.getPersonnelId());
                    m.put("personName",    resolveName(a.getPersonnelId(), tenantId, nameCache));
                    // FALSE, always.
                    //
                    // ModuleListView adds its Actions column as soon as ANY row
                    // reports an `editable` field, and its fallback button is a
                    // plain Edit that navigates to the detail page. Emitting
                    // "still actionable" here was read as "this row is
                    // editable", so every incomplete assignment grew an Edit
                    // button that vanished on completion — exactly backwards.
                    //
                    // A learner never edits an assignment; they complete it in
                    // the player. With no adopt action on this blueprint either,
                    // false removes the Actions column entirely rather than
                    // leaving a row of empty cells.
                    m.put("editable",      false);
                    m.put("createdAt",     a.getCreatedAt());
                    return m;
                });

        attachProgress(page, tenantId);
        return ResponseEntity.ok(ApiResponse.success(page));
    }

    /**
     * Fills progressPercent and progressPercentLabel across the whole page.
     *
     * The previous version reported 100 when complete and null otherwise, so an
     * IN-PROGRESS assignment showed a blank cell — the one case where progress
     * is worth showing. It was left that way because computing it per row is an
     * N+1; this uses the same batch shape as attachReportingDeadlines on
     * Incidents.
     *
     * The label reads "3/8" rather than a percent, because a learner wants to
     * know how many videos are left, not an abstraction of it.
     */
    private void attachProgress(PaginatedResponse<Map<String, Object>> page, Long tenantId) {
        List<Map<String, Object>> rows = page.getItems();
        if (rows == null || rows.isEmpty()) return;

        List<Long> ids = rows.stream().map(m -> (Long) m.get("id"))
                .filter(Objects::nonNull).toList();
        if (ids.isEmpty()) return;

        Map<Long, int[]> counts = assignmentService.itemProgressFor(ids, tenantId);

        for (Map<String, Object> m : rows) {
            Long id = (Long) m.get("id");
            boolean complete = m.get("completedAt") != null;
            int[] c = counts.get(id);                      // [done, total]

            if (complete) {
                m.put("progressPercent", 100);
                m.put("progressPercentLabel",
                        c != null && c[1] > 0 ? c[1] + "/" + c[1] : "Done");
            } else if (c != null && c[1] > 0) {
                m.put("progressPercent", (int) Math.round(c[0] * 100.0 / c[1]));
                m.put("progressPercentLabel", c[0] + "/" + c[1]);
            } else {
                // Nothing to measure — a policy acceptance, or a course whose
                // content was removed. Null renders an em dash; an empty 0% bar
                // would state "started and got nowhere", which is not the same
                // fact as "nothing to do yet".
                m.put("progressPercent", null);
                m.put("progressPercentLabel", null);
            }
        }
    }

    private String resolveName(Long personnelId, Long tenantId, Map<Long, String> cache) {
        if (personnelId == null) return "";
        return cache.computeIfAbsent(personnelId, pid ->
                personnelRepository.findByIdAndTenantIdAndIsDeletedFalse(pid, tenantId)
                        .map(Personnel::getFullName).orElse(""));
    }


    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * The learner view, with presigned playback URLs and the quiz.
     *
     * A training:report holder reading somebody else's assignment gets the
     * SUMMARY instead — no playback URL, no questions. Reporting on training is
     * not the same as being able to sit it for somebody.
     */
    @GetMapping("/{id}")
    @Operation(summary = "One assignment — full player view for your own, summary for others")
    public ResponseEntity<ApiResponse<TrainingAssignmentResponse>> getById(@PathVariable Long id) {
        User ctx = utilityService.getLoggedInDataContext();
        Long tenantId = ctx.getTenantId();

        boolean mine = personnelRepository
                .findByTenantIdAndUserIdAndIsDeletedFalse(tenantId, ctx.getId())
                .map(p -> assignmentService.isOwnedBy(id, p.getId(), tenantId))
                .orElse(false);

        return ResponseEntity.ok(ApiResponse.success(mine
                ? assignmentService.getForLearner(id, ctx.getId(), tenantId)
                : assignmentService.getForReport(id, tenantId)));
    }

    // ── Completing it ─────────────────────────────────────────────────────────

    @PostMapping("/{id}/progress")
    @Operation(summary = "Player heartbeat — claimed playback is clamped to wall-clock elapsed")
    public ResponseEntity<ApiResponse<TrainingAssignmentResponse>> progress(
            @PathVariable Long id, @Valid @RequestBody TrainingProgressRequest req) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                assignmentService.recordProgress(id, req, ctx.getId(), ctx.getTenantId())));
    }

    @PostMapping("/{id}/quiz")
    @Operation(summary = "Submit a quiz attempt — graded server-side, every attempt kept")
    public ResponseEntity<ApiResponse<TrainingAssignmentResponse>> submitQuiz(
            @PathVariable Long id, @Valid @RequestBody TrainingQuizSubmissionRequest req) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                assignmentService.submitQuiz(id, req, ctx.getId(), ctx.getTenantId())));
    }

    /**
     * Attestation, or policy acceptance — the same act.
     *
     * The caller's IP is captured from the request because an attestation is a
     * statement by a person, and who said it, when and from where is the whole
     * of its evidential weight.
     */
    @PostMapping("/{id}/attest")
    @Operation(summary = "Confirm you have read and understood — completes the assignment")
    public ResponseEntity<ApiResponse<TrainingAssignmentResponse>> attest(
            @PathVariable Long id, @Valid @RequestBody TrainingAttestRequest req,
            HttpServletRequest http) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                assignmentService.attest(id, req, clientIp(http), ctx.getId(), ctx.getTenantId())));
    }

    /** X-Forwarded-For first: behind a proxy, getRemoteAddr is the proxy. */
    private String clientIp(HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return http.getRemoteAddr();
    }

    // ── Administration ────────────────────────────────────────────────────────

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel an assignment made in error")
    public ResponseEntity<ApiResponse<Void>> cancel(
            @PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        User ctx = utilityService.getLoggedInDataContext();
        Object r = body == null ? null : body.get("remarks");
        assignmentService.cancel(id, r == null ? null : String.valueOf(r),
                ctx.getId(), ctx.getTenantId());
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PostMapping("/policy")
    @Operation(summary = "Assign an approved policy for reading and acceptance")
    public ResponseEntity<ApiResponse<Map<String, Object>>> assignPolicy(
            @Valid @RequestBody TrainingPolicyAssignRequest req) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                courseService.assignPolicy(req, ctx.getId(), ctx.getTenantId())));
    }

    @GetMapping("/stats")
    @Operation(summary = "Completion across the organisation — counts by status and the overdue total")
    public ResponseEntity<ApiResponse<Map<String, Object>>> stats() {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(assignmentService.getStats(tenantId)));
    }
}