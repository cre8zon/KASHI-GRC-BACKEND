package com.kashi.grc.questionnaire.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.kashi.grc.ai.chat.AiChatService;
import com.kashi.grc.ai.domain.AiEnums.ChunkSourceType;
import com.kashi.grc.ai.domain.AiEnums.TaskType;
import com.kashi.grc.ai.rag.RetrievalService;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.exception.ValidationException;
import com.kashi.grc.questionnaire.domain.AnswerLibraryEntry;
import com.kashi.grc.questionnaire.domain.InboundQuestion;
import com.kashi.grc.questionnaire.domain.InboundQuestionnaire;
import com.kashi.grc.questionnaire.repository.AnswerLibraryRepository;
import com.kashi.grc.questionnaire.repository.InboundQuestionRepository;
import com.kashi.grc.questionnaire.repository.InboundQuestionnaireRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Inbound questionnaires: parse, draft, review, export.
 *
 * ── THE DRAFTER GOES THROUGH AiChatService, NOT THE PROVIDER ──────────────
 * AiChatService is, in its own words, "the single door to every language model
 * in the platform" — because budget limits, personal-data redaction, prompt
 * injection scanning, usage accounting and the interaction audit row are only
 * guarantees if they cannot be bypassed. Calling LlmProviderRegistry directly
 * from here would skip all six and turn them back into conventions.
 *
 * ── AND THE LIBRARY COMES FIRST, ALWAYS ───────────────────────────────────
 * An exact-enough library match is returned WITHOUT calling a model at all:
 * cheaper, instant, and — the real point — already approved by a human. The
 * model is only asked when nothing approved fits, and even then it is given the
 * near-misses and the policy corpus and told to work from them.
 *
 * The answer to a security questionnaire is a contractual representation to a
 * customer. Fluent invention is the failure mode this module exists to prevent.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuestionnaireService {

    private final InboundQuestionnaireRepository questionnaireRepository;
    private final InboundQuestionRepository      questionRepository;
    private final AnswerLibraryRepository        libraryRepository;
    private final RetrievalService               retrievalService;
    private final AiChatService                  aiChatService;

    /** Below this, a library hit is offered as context rather than used as the answer. */
    private static final double DIRECT_REUSE_THRESHOLD = 0.82;

    /** How many library near-misses to hand the model. */
    private static final int LIBRARY_CANDIDATES = 5;

    /** A year. Long enough not to be busywork, short enough that "we encrypt at
     *  rest with AES-256" gets re-read before it is sent for the fiftieth time. */
    private static final int LIBRARY_REVIEW_MONTHS = 12;

    // ═════════════════════════════════════════════════════════════════════════
    // QUESTIONNAIRES
    // ═════════════════════════════════════════════════════════════════════════

    @Transactional
    public InboundQuestionnaire create(Map<String, Object> req, Long userId, Long tenantId) {
        String title = str(req.get("title"));
        if (title == null) throw new ValidationException("The questionnaire needs a title.");

        return questionnaireRepository.save(InboundQuestionnaire.builder()
                .tenantId(tenantId)
                .questionnaireRef(nextRef(tenantId))
                .title(title)
                .requesterOrg(str(req.get("requesterOrg")))
                .requesterContact(str(req.get("requesterContact")))
                .format(orDefault(str(req.get("format")), "CUSTOM"))
                .sourceDocumentId(asLong(req.get("sourceDocumentId")))
                .dueDate(asDate(req.get("dueDate")))
                .status(InboundQuestionnaire.Status.RECEIVED)
                .assignedTo(asLong(req.get("assignedTo")))
                .createdBy(userId)
                .build());
    }

    /**
     * Loads questions, from a parsed list.
     *
     * Takes the rows rather than the file: extracting questions from a
     * spreadsheet or PDF is the document module's job, and duplicating that
     * here would give two parsers to keep in step.
     *
     * sourceRef is carried through from the original. Losing it means the
     * export is a new document rather than theirs filled in.
     */
    @Transactional
    public int loadQuestions(Long questionnaireId, List<Map<String, Object>> rows, Long tenantId) {
        InboundQuestionnaire q = require(questionnaireId, tenantId);
        if (questionRepository.countByQuestionnaireId(questionnaireId) > 0) {
            throw new ValidationException(
                    "This questionnaire already has questions loaded. Create a new one rather "
                            + "than mixing two versions of the same request.");
        }

        int i = 0, created = 0;
        for (Map<String, Object> row : rows) {
            String text = str(row.get("questionText"));
            if (text == null) continue;   // blank spreadsheet rows are normal
            questionRepository.save(InboundQuestion.builder()
                    .tenantId(tenantId)
                    .questionnaireId(questionnaireId)
                    .sourceRef(str(row.get("sourceRef")))
                    .section(str(row.get("section")))
                    .sortOrder((++i) * 10)
                    .questionText(text)
                    .reviewStatus(InboundQuestion.ReviewStatus.PENDING)
                    .answerSource("MANUAL")
                    .build());
            created++;
        }

        q.setStatus(InboundQuestionnaire.Status.IN_REVIEW);
        questionnaireRepository.save(q);
        log.info("[QUESTIONNAIRE] Loaded | ref={} | {} question(s)", q.getQuestionnaireRef(), created);
        return created;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // DRAFTING
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Drafts every unanswered question.
     *
     * Sequential, not parallel: every call costs tenant budget, and a fan-out
     * that exhausts it halfway leaves a half-drafted pack and no clear record of
     * where it stopped. A 200-row questionnaire is slow here, which is the
     * honest cost of the guarantees AiChatService provides.
     */
    @Transactional
    public Map<String, Object> draftAll(Long questionnaireId, Long userId, Long tenantId) {
        InboundQuestionnaire q = require(questionnaireId, tenantId);
        List<InboundQuestion> questions =
                questionRepository.findByQuestionnaireIdOrderBySortOrderAsc(questionnaireId);

        int fromLibrary = 0, fromAi = 0, failed = 0, skipped = 0;
        for (InboundQuestion question : questions) {
            // Never overwrite a human's work, or a decision already taken.
            if (question.getReviewStatus() == InboundQuestion.ReviewStatus.APPROVED
                    || question.getAnswerText() != null) { skipped++; continue; }
            try {
                String source = draftOne(question, tenantId, userId);
                if ("LIBRARY".equals(source)) fromLibrary++; else fromAi++;
            } catch (Exception e) {
                // One bad question must not abandon the other 199.
                log.warn("[QUESTIONNAIRE] Draft failed for question {} | {}",
                        question.getId(), e.getMessage());
                failed++;
            }
        }

        q.setStatus(InboundQuestionnaire.Status.IN_REVIEW);
        questionnaireRepository.save(q);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fromLibrary", fromLibrary);
        out.put("fromAi",      fromAi);
        out.put("skipped",     skipped);
        out.put("failed",      failed);
        log.info("[QUESTIONNAIRE] Drafted | ref={} | library={} ai={} skipped={} failed={}",
                q.getQuestionnaireRef(), fromLibrary, fromAi, skipped, failed);
        return out;
    }

    /** Drafts one. Returns the source used. */
    @Transactional
    public String draftOne(InboundQuestion question, Long tenantId, Long userId) {
        List<Scored> candidates = matchLibrary(question.getQuestionText(), tenantId);

        // ── 1. A strong library match answers it outright ─────────────────
        //
        // No model call: cheaper, instant, and already approved by a human.
        // This is the path that should handle most of a repeat questionnaire.
        if (!candidates.isEmpty() && candidates.get(0).score >= DIRECT_REUSE_THRESHOLD) {
            AnswerLibraryEntry hit = candidates.get(0).entry;
            question.setAnswerText(hit.getAnswer());
            question.setShortAnswer(hit.getShortAnswer());
            question.setAnswerSource("LIBRARY");
            question.setLibraryAnswerId(hit.getId());
            question.setConfidence((int) Math.round(candidates.get(0).score * 100));
            question.setCitationsJson(jsonArray(List.of("Answer library #" + hit.getId())));
            question.setReviewStatus(InboundQuestion.ReviewStatus.DRAFTED);
            questionRepository.save(question);
            recordUsage(hit);
            return "LIBRARY";
        }

        // ── 2. Otherwise: near-misses plus the policy corpus, then the model ──
        RetrievalService.RetrievalResult rag = retrievalService.retrieve(
                question.getQuestionText(), tenantId,
                List.of(ChunkSourceType.POLICY, ChunkSourceType.CONTROL,
                        ChunkSourceType.FRAMEWORK_TEXT, ChunkSourceType.KNOWLEDGE_NOTE),
                6);

        String libraryBlock = candidates.isEmpty() ? "(no approved answers matched)"
                : candidates.stream()
                  .map(c -> "Q: " + c.entry.getQuestion() + "\nA: " + c.entry.getAnswer())
                  .reduce((a, b) -> a + "\n\n---\n\n").orElse("");

        AiChatService.AiResult result = aiChatService.completeJson(
                AiChatService.AiCall.of("questionnaire.answer", TaskType.VENDOR_QUESTIONNAIRE_ANSWER)
                        .var("question",       question.getQuestionText())
                        .var("libraryMatches", libraryBlock)
                        .var("contextBlock",   rag.isEmpty() ? "(no policy text retrieved)" : rag.contextBlock())
                        .var("orgName",        "the organisation")
                        .tenant(tenantId)
                        .user(userId));

        JsonNode json = result.json();
        boolean needsInfo = json.path("needsInfo").asBoolean(false);

        question.setAnswerText(json.path("answer").asText(null));
        question.setShortAnswer(nullIfBlank(json.path("shortAnswer").asText(null)));
        // AI_FROM_LIBRARY when approved material backed it, AI_DRAFTED when
        // nothing did. The badge for the second is amber for a reason: it is
        // the one a reviewer has to read word for word.
        question.setAnswerSource(candidates.isEmpty() ? "AI_DRAFTED" : "AI_FROM_LIBRARY");
        question.setConfidence(json.path("confidence").isInt() ? json.path("confidence").asInt() : null);

        List<String> citations = new ArrayList<>();
        rag.chunks().forEach(c -> citations.add(c.citation()));
        candidates.forEach(c -> citations.add("Answer library #" + c.entry.getId()));
        question.setCitationsJson(jsonArray(citations));

        // The model saying it cannot answer is a RESULT, not a failure. Marking
        // it NEEDS_INFO puts it in front of someone who knows, instead of
        // leaving a confident guess to be approved by somebody who does not.
        question.setReviewStatus(needsInfo
                ? InboundQuestion.ReviewStatus.NEEDS_INFO
                : InboundQuestion.ReviewStatus.DRAFTED);

        questionRepository.save(question);
        return "AI";
    }

    /**
     * Ranks approved library entries against a question.
     *
     * Token overlap, deliberately simple. The semantically correct approach is
     * to embed library questions into the vector store alongside policies — and
     * that is the right eventual answer — but it needs an ingestion hook per
     * write and a backfill, and shipping this without it means the library
     * works from day one rather than after that lands. The threshold is set
     * high enough that a weak overlap match falls through to the model rather
     * than being reused outright.
     */
    private List<Scored> matchLibrary(String question, Long tenantId) {
        Set<String> qTokens = tokens(question);
        if (qTokens.isEmpty()) return List.of();

        List<Scored> scored = new ArrayList<>();
        for (AnswerLibraryEntry e : libraryRepository
                .findByTenantIdAndStatusAndIsDeletedFalse(tenantId, AnswerLibraryEntry.Status.APPROVED)) {
            Set<String> eTokens = tokens(e.getQuestion());
            if (eTokens.isEmpty()) continue;
            Set<String> shared = new HashSet<>(qTokens);
            shared.retainAll(eTokens);
            if (shared.isEmpty()) continue;
            // Jaccard, which penalises a long library question matching a short
            // one on a couple of common words.
            Set<String> union = new HashSet<>(qTokens);
            union.addAll(eTokens);
            scored.add(new Scored(e, (double) shared.size() / union.size()));
        }
        scored.sort((a, b) -> Double.compare(b.score, a.score));
        return scored.size() > LIBRARY_CANDIDATES ? scored.subList(0, LIBRARY_CANDIDATES) : scored;
    }

    private record Scored(AnswerLibraryEntry entry, double score) {}

    /** Words worth matching on. Stopwords out, short tokens out. */
    private Set<String> tokens(String s) {
        if (s == null) return Set.of();
        Set<String> out = new HashSet<>();
        for (String w : s.toLowerCase().split("[^a-z0-9]+")) {
            if (w.length() > 3 && !STOPWORDS.contains(w)) out.add(w);
        }
        return out;
    }

    private static final Set<String> STOPWORDS = Set.of(
            "does", "your", "have", "with", "that", "this", "from", "what", "which",
            "organisation", "organization", "company", "provide", "please", "describe",
            "will", "been", "they", "there", "their", "when", "where", "would", "about");

    // ═════════════════════════════════════════════════════════════════════════
    // REVIEW
    // ═════════════════════════════════════════════════════════════════════════

    @Transactional
    public InboundQuestion review(Long questionId, String decision, String answerText,
                                  String note, boolean addToLibrary,
                                  Long userId, Long tenantId) {
        InboundQuestion q = requireQuestion(questionId, tenantId);

        InboundQuestion.ReviewStatus status;
        try {
            status = InboundQuestion.ReviewStatus.valueOf(String.valueOf(decision).toUpperCase());
        } catch (Exception e) {
            throw new ValidationException("Decision must be APPROVED, NEEDS_INFO or DRAFTED.");
        }

        if (answerText != null) {
            // An edited answer is no longer the model's. Marking it MANUAL
            // matters: the next reviewer should not see "AI draft" on prose a
            // person wrote, and the provenance badge is only useful if it is true.
            q.setAnswerText(answerText);
            if (q.isAiWritten()) q.setAnswerSource("MANUAL");
        }
        if (status == InboundQuestion.ReviewStatus.APPROVED && str(q.getAnswerText()) == null) {
            throw new ValidationException("There is no answer to approve.");
        }

        q.setReviewStatus(status);
        q.setReviewerNote(str(note));
        q.setReviewedBy(userId);
        q.setReviewedAt(LocalDateTime.now());
        questionRepository.save(q);

        // Promoting an approved answer into the library is how the library
        // grows by being used, rather than by somebody sitting down to write it.
        if (addToLibrary && status == InboundQuestion.ReviewStatus.APPROVED) {
            promoteToLibrary(q, userId, tenantId);
        }
        return q;
    }

    private void promoteToLibrary(InboundQuestion q, Long userId, Long tenantId) {
        libraryRepository.save(AnswerLibraryEntry.builder()
                .tenantId(tenantId)
                .question(q.getQuestionText())
                .answer(q.getAnswerText())
                .shortAnswer(q.getShortAnswer())
                // APPROVED immediately: a human just approved this exact text
                // on a real questionnaire, which is a stronger signal than
                // anything a separate library review would add.
                .status(AnswerLibraryEntry.Status.APPROVED)
                .approvedBy(userId)
                .approvedAt(LocalDateTime.now())
                .reviewDueAt(LocalDateTime.now().plusMonths(LIBRARY_REVIEW_MONTHS))
                .createdBy(userId)
                .build());
    }

    private void recordUsage(AnswerLibraryEntry e) {
        e.setUsageCount((e.getUsageCount() == null ? 0 : e.getUsageCount()) + 1);
        e.setLastUsedAt(LocalDateTime.now());
        libraryRepository.save(e);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // COMPLETION
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Marks the pack ready to send, or refuses and says what is outstanding.
     *
     * Same shape as the access-review gate: a questionnaire declared complete
     * with eleven unreviewed AI drafts in it is a set of representations nobody
     * has read, going to a customer under somebody's name.
     */
    @Transactional
    public InboundQuestionnaire complete(Long questionnaireId, Long userId, Long tenantId) {
        InboundQuestionnaire q = require(questionnaireId, tenantId);
        List<InboundQuestion> questions =
                questionRepository.findByQuestionnaireIdOrderBySortOrderAsc(questionnaireId);

        if (questions.isEmpty()) {
            throw new ValidationException("There are no questions loaded yet.");
        }

        long unreviewed = questions.stream()
                .filter(x -> x.getReviewStatus() != InboundQuestion.ReviewStatus.APPROVED).count();
        if (unreviewed > 0) {
            long needsInfo = questions.stream()
                    .filter(x -> x.getReviewStatus() == InboundQuestion.ReviewStatus.NEEDS_INFO).count();
            throw new ValidationException(
                    unreviewed + " answer(s) have not been approved"
                            + (needsInfo > 0 ? ", including " + needsInfo + " still waiting on input from "
                                               + "somebody who knows" : "")
                            + ". Every answer here is a representation to a customer.");
        }

        q.setStatus(InboundQuestionnaire.Status.COMPLETED);
        questionnaireRepository.save(q);
        return q;
    }

    @Transactional
    public InboundQuestionnaire markSubmitted(Long questionnaireId, Long userId, Long tenantId) {
        InboundQuestionnaire q = require(questionnaireId, tenantId);
        if (q.getStatus() != InboundQuestionnaire.Status.COMPLETED) {
            throw new ValidationException("Complete the pack before marking it sent.");
        }
        q.setStatus(InboundQuestionnaire.Status.SUBMITTED);
        q.setSubmittedAt(LocalDateTime.now());
        q.setSubmittedBy(userId);
        return questionnaireRepository.save(q);
    }

    /**
     * The answers in original order, for export.
     *
     * sourceRef is included on every row: the export writes back into the
     * customer's own file, which is the difference between a filled-in
     * questionnaire and a document they have to transcribe.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> exportRows(Long questionnaireId, Long tenantId) {
        require(questionnaireId, tenantId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (InboundQuestion q : questionRepository
                .findByQuestionnaireIdOrderBySortOrderAsc(questionnaireId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sourceRef",    q.getSourceRef());
            m.put("section",      q.getSection());
            m.put("question",     q.getQuestionText());
            m.put("answer",       q.getAnswerText());
            m.put("shortAnswer",  q.getShortAnswer());
            out.add(m);
        }
        return out;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // LIBRARY AND STATS
    // ═════════════════════════════════════════════════════════════════════════

    @Transactional
    public AnswerLibraryEntry saveLibraryEntry(Map<String, Object> req, Long userId, Long tenantId) {
        Long id = asLong(req.get("id"));
        AnswerLibraryEntry e = id == null
                ? AnswerLibraryEntry.builder().tenantId(tenantId).createdBy(userId).build()
                : libraryRepository.findById(id)
                  .filter(x -> tenantId.equals(x.getTenantId()))
                  .orElseThrow(() -> new ResourceNotFoundException("AnswerLibraryEntry", id));

        if (req.containsKey("question")) e.setQuestion(str(req.get("question")));
        if (req.containsKey("answer"))   e.setAnswer(str(req.get("answer")));
        if (e.getQuestion() == null || e.getAnswer() == null) {
            throw new ValidationException("A library entry needs both a question and an answer.");
        }
        if (req.containsKey("shortAnswer"))   e.setShortAnswer(str(req.get("shortAnswer")));
        if (req.containsKey("category"))      e.setCategory(str(req.get("category")));
        if (req.containsKey("controlTags"))   e.setControlTags(str(req.get("controlTags")));
        if (req.containsKey("frameworkRefs")) e.setFrameworkRefs(str(req.get("frameworkRefs")));
        if (req.containsKey("evidenceDocumentId")) e.setEvidenceDocumentId(asLong(req.get("evidenceDocumentId")));

        if (Boolean.TRUE.equals(req.get("approve"))) {
            e.setStatus(AnswerLibraryEntry.Status.APPROVED);
            e.setApprovedBy(userId);
            e.setApprovedAt(LocalDateTime.now());
            if (e.getReviewDueAt() == null) {
                e.setReviewDueAt(LocalDateTime.now().plusMonths(LIBRARY_REVIEW_MONTHS));
            }
        }
        return libraryRepository.save(e);
    }

    /** Re-approving pushes the review date out and records who confirmed it. */
    @Transactional
    public AnswerLibraryEntry confirmStillAccurate(Long id, Long userId, Long tenantId) {
        AnswerLibraryEntry e = libraryRepository.findById(id)
                .filter(x -> tenantId.equals(x.getTenantId()))
                .orElseThrow(() -> new ResourceNotFoundException("AnswerLibraryEntry", id));
        e.setApprovedBy(userId);
        e.setApprovedAt(LocalDateTime.now());
        e.setReviewDueAt(LocalDateTime.now().plusMonths(LIBRARY_REVIEW_MONTHS));
        return libraryRepository.save(e);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getStats(Long tenantId) {
        List<InboundQuestionnaire> packs = questionnaireRepository.findByTenantIdAndIsDeletedFalse(tenantId);
        List<InboundQuestion> questions = questionRepository.findByTenantId(tenantId);
        List<AnswerLibraryEntry> library = libraryRepository.findByTenantIdAndIsDeletedFalse(tenantId);

        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (InboundQuestionnaire p : packs) byStatus.merge(p.getStatus().name(), 1L, Long::sum);

        Map<String, Long> bySource = new LinkedHashMap<>();
        long unreviewed = 0, aiUnreviewed = 0, needsInfo = 0;
        for (InboundQuestion q : questions) {
            bySource.merge(q.getAnswerSource(), 1L, Long::sum);
            if (q.getReviewStatus() != InboundQuestion.ReviewStatus.APPROVED) unreviewed++;
            if (q.isAiWritten() && q.getReviewStatus() == InboundQuestion.ReviewStatus.DRAFTED) aiUnreviewed++;
            if (q.getReviewStatus() == InboundQuestion.ReviewStatus.NEEDS_INFO) needsInfo++;
        }

        long stale = library.stream().filter(AnswerLibraryEntry::isStale).count();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalQuestionnaires", packs.size());
        out.put("inReview", byStatus.getOrDefault("IN_REVIEW", 0L));
        out.put("unreviewedAnswers", unreviewed);
        // The number that matters: a model wrote it, nobody has read it, and it
        // is one approval away from going to a customer.
        out.put("aiDraftsAwaitingReview", aiUnreviewed);
        out.put("needsInfo", needsInfo);
        out.put("libraryEntries", library.size());
        out.put("libraryApproved", library.stream().filter(AnswerLibraryEntry::isSuggestable).count());
        // A stale library answer is worse than no answer — confidently wrong,
        // carrying the authority of having been approved once.
        out.put("libraryStale", stale);
        out.put("byQuestionnaireStatus", series(byStatus));
        out.put("byAnswerSource", series(bySource));
        return out;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    private String nextRef(Long tenantId) {
        long seq = questionnaireRepository.nextRefSequence(tenantId);
        String candidate = String.format("QNR-%d-%03d", LocalDate.now().getYear(), seq);
        int guard = 0;
        while (questionnaireRepository.existsByQuestionnaireRefAndTenantId(candidate, tenantId)
                && guard++ < 1000) {
            candidate = String.format("QNR-%d-%03d", LocalDate.now().getYear(), ++seq);
        }
        return candidate;
    }

    private InboundQuestionnaire require(Long id, Long tenantId) {
        InboundQuestionnaire q = questionnaireRepository.findById(id)
                .filter(x -> !x.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("Questionnaire", id));
        if (!tenantId.equals(q.getTenantId())) throw new ResourceNotFoundException("Questionnaire", id);
        return q;
    }

    private InboundQuestion requireQuestion(Long id, Long tenantId) {
        InboundQuestion q = questionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Question", id));
        if (!tenantId.equals(q.getTenantId())) throw new ResourceNotFoundException("Question", id);
        return q;
    }

    private List<Map<String, Object>> series(Map<String, Long> counts) {
        List<Map<String, Object>> out = new ArrayList<>(counts.size());
        counts.forEach((k, v) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", k);
            row.put("value", v);
            out.add(row);
        });
        return out;
    }

    private String jsonArray(List<String> values) {
        if (values == null || values.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(values.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append(']').toString();
    }

    private String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private String nullIfBlank(String s) {
        return (s == null || s.isBlank() || "null".equals(s)) ? null : s;
    }

    private String orDefault(String s, String fallback) { return s == null ? fallback : s; }

    private Long asLong(Object o) {
        try { return o == null ? null : Long.parseLong(String.valueOf(o)); }
        catch (Exception e) { return null; }
    }

    private LocalDate asDate(Object o) {
        try { return o == null ? null : LocalDate.parse(String.valueOf(o).substring(0, 10)); }
        catch (Exception e) { return null; }
    }
}