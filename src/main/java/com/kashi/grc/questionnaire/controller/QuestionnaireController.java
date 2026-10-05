package com.kashi.grc.questionnaire.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.repository.DbRepository;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.questionnaire.domain.AnswerLibraryEntry;
import com.kashi.grc.questionnaire.domain.InboundQuestion;
import com.kashi.grc.questionnaire.domain.InboundQuestionnaire;
import com.kashi.grc.questionnaire.repository.AnswerLibraryRepository;
import com.kashi.grc.questionnaire.repository.InboundQuestionRepository;
import com.kashi.grc.questionnaire.service.QuestionnaireService;
import com.kashi.grc.usermanagement.domain.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Slf4j
@RestController
@RequestMapping("/v1/questionnaires")
@RequiredArgsConstructor
@Tag(name = "Inbound questionnaires", description = "Security questionnaires sent to us")
public class QuestionnaireController {

    private final QuestionnaireService     service;
    private final InboundQuestionRepository questionRepository;
    private final AnswerLibraryRepository   libraryRepository;
    private final UtilityService            utilityService;
    private final DbRepository              dbRepository;

    // ── Questionnaires ────────────────────────────────────────────────────────

    @GetMapping
    @Operation(summary = "Questionnaires received")
    public ResponseEntity<ApiResponse<Object>> list(@RequestParam Map<String, String> allParams) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();

        return ResponseEntity.ok(ApiResponse.success(dbRepository.findAll(
                InboundQuestionnaire.class,
                utilityService.getpageDetails(allParams),
                (cb, root) -> {
                    List<jakarta.persistence.criteria.Predicate> preds = new ArrayList<>();
                    preds.add(cb.equal(root.get("tenantId"), tenantId));
                    preds.add(cb.isFalse(root.get("isDeleted")));
                    String status = allParams.get("status");
                    if (status != null && !status.isBlank()) {
                        preds.add(cb.equal(root.get("status"),
                                InboundQuestionnaire.Status.valueOf(status)));
                    }
                    return preds;
                },
                // Five arguments. The sortable-field map sits between the
                // predicate and the row mapper; omitting it binds the mapper
                // here and every getter resolves against CriteriaBuilder.
                (cb, root) -> {
                    Map<String, jakarta.persistence.criteria.Path<?>> f = new HashMap<>();
                    f.put("title",             root.get("title"));
                    f.put("questionnaire_ref", root.get("questionnaireRef"));
                    f.put("questionnaireref",  root.get("questionnaireRef"));
                    f.put("requester_org",     root.get("requesterOrg"));
                    f.put("status",            root.get("status"));
                    f.put("due_date",          root.get("dueDate"));
                    f.put("created_at",        root.get("createdAt"));
                    return f;
                },
                q -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",               q.getId());
                    m.put("questionnaireRef", q.getQuestionnaireRef());
                    m.put("title",            q.getTitle());
                    m.put("requesterOrg",     q.getRequesterOrg());
                    m.put("format",           q.getFormat());
                    m.put("status",           q.getStatus());
                    m.put("dueDate",          q.getDueDate());
                    m.put("submittedAt",      q.getSubmittedAt());
                    m.put("editable",         q.getStatus() != InboundQuestionnaire.Status.SUBMITTED);
                    return m;
                })));
    }

    @GetMapping("/{id}")
    @Operation(summary = "One questionnaire, with its progress")
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(@PathVariable Long id) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        List<InboundQuestion> questions = questionRepository.findByQuestionnaireIdOrderBySortOrderAsc(id)
                .stream().filter(q -> tenantId.equals(q.getTenantId())).toList();

        long approved = questions.stream()
                .filter(q -> q.getReviewStatus() == InboundQuestion.ReviewStatus.APPROVED).count();
        long aiUnread = questions.stream()
                .filter(q -> q.isAiWritten()
                        && q.getReviewStatus() == InboundQuestion.ReviewStatus.DRAFTED).count();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("totalQuestions", questions.size());
        m.put("approved", approved);
        m.put("progressPercent", questions.isEmpty() ? 0
                : (int) Math.round(approved * 100.0 / questions.size()));
        // Surfaced on the header so the reviewer sees the real workload before
        // pressing Complete and being refused.
        m.put("aiDraftsAwaitingReview", aiUnread);
        m.put("needsInfo", questions.stream()
                .filter(q -> q.getReviewStatus() == InboundQuestion.ReviewStatus.NEEDS_INFO).count());
        return ResponseEntity.ok(ApiResponse.success(m));
    }

    @PostMapping
    @Operation(summary = "Record a questionnaire that arrived")
    public ResponseEntity<ApiResponse<InboundQuestionnaire>> create(@RequestBody Map<String, Object> body) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                service.create(body, ctx.getId(), ctx.getTenantId())));
    }

    /** Questions, already extracted from the source file by the document module. */
    @PostMapping("/{id}/questions")
    @Operation(summary = "Load parsed questions")
    public ResponseEntity<ApiResponse<Map<String, Object>>> loadQuestions(
            @PathVariable Long id, @RequestBody List<Map<String, Object>> rows) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("loaded", service.loadQuestions(id, rows, tenantId))));
    }

    @PostMapping("/{id}/draft")
    @Operation(summary = "Draft every unanswered question — library first, model second")
    public ResponseEntity<ApiResponse<Map<String, Object>>> draft(@PathVariable Long id) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                service.draftAll(id, ctx.getId(), ctx.getTenantId())));
    }

    @PostMapping("/{id}/complete")
    @Operation(summary = "Mark ready to send — refuses while any answer is unapproved")
    public ResponseEntity<ApiResponse<InboundQuestionnaire>> complete(@PathVariable Long id) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                service.complete(id, ctx.getId(), ctx.getTenantId())));
    }

    @PostMapping("/{id}/submitted")
    @Operation(summary = "Record that the pack was sent")
    public ResponseEntity<ApiResponse<InboundQuestionnaire>> submitted(@PathVariable Long id) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                service.markSubmitted(id, ctx.getId(), ctx.getTenantId())));
    }

    /** Rows in the customer's own order, each carrying its sourceRef. */
    @GetMapping("/{id}/export")
    @Operation(summary = "Answers in original order, for writing back into their file")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> export(@PathVariable Long id) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(service.exportRows(id, tenantId)));
    }

    // ── Questions ─────────────────────────────────────────────────────────────

    @GetMapping("/{id}/linked-questions")
    @Operation(summary = "The questions and their answers")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> questions(@PathVariable Long id) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(
                questionRepository.findByQuestionnaireIdOrderBySortOrderAsc(id).stream()
                        .filter(q -> tenantId.equals(q.getTenantId()))
                        .map(this::toRow).toList()));
    }

    @PostMapping("/questions/{questionId}/review")
    @Operation(summary = "Approve, edit, or send back for input")
    public ResponseEntity<ApiResponse<Map<String, Object>>> review(
            @PathVariable Long questionId, @RequestBody Map<String, Object> body) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(toRow(service.review(
                questionId,
                String.valueOf(body.get("decision")),
                body.get("answerText") == null ? null : String.valueOf(body.get("answerText")),
                body.get("remarks") == null ? null : String.valueOf(body.get("remarks")),
                Boolean.TRUE.equals(body.get("addToLibrary")),
                ctx.getId(), ctx.getTenantId()))));
    }

    // ── Answer library ────────────────────────────────────────────────────────

    @GetMapping("/library")
    @Operation(summary = "The answer library")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> library() {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(
                libraryRepository.findByTenantIdAndIsDeletedFalse(tenantId).stream()
                        .map(e -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("id",          e.getId());
                            m.put("title",       e.getQuestion());
                            m.put("question",    e.getQuestion());
                            m.put("answer",      e.getAnswer());
                            m.put("shortAnswer", e.getShortAnswer());
                            m.put("category",    e.getCategory());
                            m.put("status",      e.getStatus());
                            // Both surfaced: an answer reused forty times
                            // deserves more scrutiny, and one past its review
                            // date is confidently wrong rather than merely old.
                            m.put("usageCount",  e.getUsageCount());
                            m.put("reviewDueAt", e.getReviewDueAt());
                            m.put("stale",       e.isStale());
                            m.put("editable",    true);
                            return m;
                        }).toList()));
    }

    @PutMapping("/library")
    @Operation(summary = "Add or edit a library answer")
    public ResponseEntity<ApiResponse<AnswerLibraryEntry>> saveLibrary(@RequestBody Map<String, Object> body) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                service.saveLibraryEntry(body, ctx.getId(), ctx.getTenantId())));
    }

    @PostMapping("/library/{id}/confirm")
    @Operation(summary = "Confirm this is still accurate, pushing its review date out")
    public ResponseEntity<ApiResponse<AnswerLibraryEntry>> confirm(@PathVariable Long id) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                service.confirmStillAccurate(id, ctx.getId(), ctx.getTenantId())));
    }

    @GetMapping("/stats")
    @Operation(summary = "Counts for the dashboard")
    public ResponseEntity<ApiResponse<Map<String, Object>>> stats() {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(service.getStats(tenantId)));
    }

    private Map<String, Object> toRow(InboundQuestion q) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",            q.getId());
        m.put("sourceRef",     q.getSourceRef());
        m.put("section",       q.getSection());
        m.put("title",         q.getQuestionText());
        m.put("questionText",  q.getQuestionText());
        m.put("answerText",    q.getAnswerText());
        m.put("shortAnswer",   q.getShortAnswer());
        m.put("answerSource",  q.getAnswerSource());
        m.put("confidence",    q.getConfidence());
        m.put("citations",     q.getCitationsJson());
        m.put("reviewStatus",  q.getReviewStatus());
        m.put("reviewerNote",  q.getReviewerNote());
        // Computed: a reviewer scanning the list needs to know which rows a
        // model wrote without opening each one.
        m.put("aiWritten",     q.isAiWritten());
        m.put("editable",      q.getReviewStatus() != InboundQuestion.ReviewStatus.APPROVED);
        return m;
    }
}