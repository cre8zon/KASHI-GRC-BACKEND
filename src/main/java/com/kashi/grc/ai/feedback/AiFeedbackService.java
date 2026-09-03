package com.kashi.grc.ai.feedback;

import com.kashi.grc.ai.domain.AiEnums.FeedbackDecision;
import com.kashi.grc.ai.domain.AiEnums.TaskType;
import com.kashi.grc.ai.domain.AiInteraction;
import com.kashi.grc.ai.domain.AiSuggestionFeedback;
import com.kashi.grc.ai.repository.AiInteractionRepository;
import com.kashi.grc.ai.repository.AiSuggestionFeedbackRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Records what humans did with AI suggestions, and reports on it.
 *
 * ── THE ONE NUMBER THAT MATTERS ──────────────────────────────────────────────
 * Acceptance rate per task type. It is the honest measure of whether the feature
 * works, it is what an investor will ask for, and it is what tells you which
 * prompt to fix next. Everything else in this class exists to make that number
 * trustworthy or to mine the data behind it.
 *
 * ── CAPTURE EVERY DECISION, INCLUDING SILENCE ────────────────────────────────
 * IGNORED is recorded too. A suggestion nobody engaged with failed — quietly,
 * which is worse than being rejected. Excluding ignores from the denominator is
 * the standard way these dashboards end up reporting 90% while the feature goes
 * unused, and it is a lie you tell yourself first and your investors second.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiFeedbackService {

    private final AiSuggestionFeedbackRepository feedbackRepository;
    private final AiInteractionRepository        interactionRepository;

    @Data
    public static class FeedbackRequest {
        private Long   interactionId;
        private String suggestionType;
        private String suggestionKey;
        private FeedbackDecision decision;
        private String originalValue;
        private String finalValue;
        private String reasonCode;
        private String comment;
        private Integer rating;
        private Integer timeToDecideSeconds;
    }

    /**
     * Denormalises prompt version and model from the interaction so quality can
     * be sliced by prompt version without a join on a table that will be one of
     * the largest in the schema.
     */
    @Transactional
    public AiSuggestionFeedback record(FeedbackRequest req, Long tenantId, Long userId) {
        AiInteraction interaction = interactionRepository.findById(req.getInteractionId())
                .orElseThrow(() -> new com.kashi.grc.common.exception.ResourceNotFoundException(
                        "AiInteraction", req.getInteractionId()));

        if (interaction.getTenantId() != null && !interaction.getTenantId().equals(tenantId)) {
            throw new com.kashi.grc.common.exception.ForbiddenException(
                    "That suggestion belongs to another organisation");
        }

        // Idempotent: a double-click on Accept must not double-count.
        if (req.getSuggestionKey() != null
                && feedbackRepository.existsByInteractionIdAndSuggestionKey(
                        req.getInteractionId(), req.getSuggestionKey())) {
            log.debug("[AI-FEEDBACK] duplicate for interaction {} key {} — ignored",
                    req.getInteractionId(), req.getSuggestionKey());
            return null;
        }

        Double editRatio = null;
        FeedbackDecision decision = req.getDecision();

        if (req.getOriginalValue() != null && req.getFinalValue() != null) {
            editRatio = editRatio(req.getOriginalValue(), req.getFinalValue());
            // Reclassify: "accepted" plus an edit is a different signal from clean acceptance.
            if (decision == FeedbackDecision.ACCEPTED && editRatio > 0.01) {
                decision = FeedbackDecision.ACCEPTED_WITH_EDIT;
            }
        }

        AiSuggestionFeedback f = feedbackRepository.save(AiSuggestionFeedback.builder()
                .interactionId(req.getInteractionId())
                .taskType(interaction.getTaskType())
                .promptTemplateKey(interaction.getPromptTemplateKey())
                .promptTemplateVersion(interaction.getPromptTemplateVersion())
                .model(interaction.getModel())
                .suggestionType(req.getSuggestionType())
                .suggestionKey(req.getSuggestionKey())
                .decision(decision)
                .originalValue(req.getOriginalValue())
                .finalValue(req.getFinalValue())
                .editDistanceRatio(editRatio)
                .reasonCode(req.getReasonCode())
                .comment(req.getComment())
                .rating(req.getRating())
                .timeToDecideSeconds(req.getTimeToDecideSeconds())
                .entityType(interaction.getEntityType())
                .entityId(interaction.getEntityId())
                .userId(userId)
                .usableAsExample(false)     // consent is granted explicitly, never inferred
                .tenantId(tenantId)
                .build());

        log.info("[AI-FEEDBACK] {} | task={} decision={} editRatio={}",
                req.getSuggestionKey(), interaction.getTaskType(), decision, editRatio);
        return f;
    }

    /** Bulk variant: a mapping panel yields one verdict per suggested control. */
    @Transactional
    public int recordBatch(List<FeedbackRequest> requests, Long tenantId, Long userId) {
        int n = 0;
        for (FeedbackRequest r : requests) {
            try { if (record(r, tenantId, userId) != null) n++; }
            catch (Exception e) { log.warn("[AI-FEEDBACK] one item failed, continuing: {}", e.getMessage()); }
        }
        return n;
    }

    // ── Analytics ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> acceptanceReport(Long tenantId, int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        List<Object[]> rows = feedbackRepository.acceptanceByTaskType(tenantId, since,
                List.of(FeedbackDecision.ACCEPTED, FeedbackDecision.ACCEPTED_WITH_EDIT));

        List<Map<String, Object>> byTask = new ArrayList<>();
        long totalAll = 0, acceptedAll = 0;

        for (Object[] r : rows) {
            long total    = ((Number) r[1]).longValue();
            long accepted = r[2] == null ? 0 : ((Number) r[2]).longValue();
            totalAll += total; acceptedAll += accepted;

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("taskType",       String.valueOf(r[0]));
            m.put("total",          total);
            m.put("accepted",       accepted);
            m.put("acceptanceRate", total == 0 ? 0.0 : Math.round((double) accepted / total * 1000) / 10.0);
            m.put("avgEditRatio",   r[3] == null ? 0.0 : Math.round(((Number) r[3]).doubleValue() * 1000) / 1000.0);
            byTask.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("periodDays", days);
        out.put("totalSuggestions", totalAll);
        out.put("totalAccepted", acceptedAll);
        out.put("overallAcceptanceRate", totalAll == 0 ? 0.0 : Math.round((double) acceptedAll / totalAll * 1000) / 10.0);
        out.put("byTaskType", byTask);
        return out;
    }

    /** Did the new prompt version beat the old one? Compare before promoting. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> promptVersionComparison(String templateKey) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : feedbackRepository.qualityByPromptVersion(templateKey, FeedbackDecision.ACCEPTED)) {
            long total    = ((Number) r[2]).longValue();
            long accepted = r[3] == null ? 0 : ((Number) r[3]).longValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("templateKey",    r[0]);
            m.put("version",        r[1]);
            m.put("total",          total);
            m.put("accepted",       accepted);
            m.put("acceptanceRate", total == 0 ? 0.0 : Math.round((double) accepted / total * 1000) / 10.0);
            m.put("avgEditRatio",   r[4] == null ? 0.0 : ((Number) r[4]).doubleValue());
            out.add(m);
        }
        return out;
    }

    /**
     * Mine accepted outputs as few-shot examples.
     *
     * The consent gate is the important part. usableAsExample defaults false and
     * is set only by an explicit admin action on a tenant that opted in. "We
     * trained on your policies" is a sentence that ends a GRC deal, and the
     * default has to be the safe one.
     */
    @Transactional(readOnly = true)
    public List<String> mineFewShotExamples(TaskType taskType, Long tenantId, int limit) {
        return feedbackRepository.mineExamples(taskType, tenantId, FeedbackDecision.ACCEPTED).stream()
                .limit(limit)
                .map(f -> f.getFinalValue() != null ? f.getFinalValue() : f.getOriginalValue())
                .filter(s -> s != null && !s.isBlank())
                .toList();
    }

    @Transactional
    public void grantExampleConsent(Long feedbackId, Long tenantId) {
        feedbackRepository.findById(feedbackId).ifPresent(f -> {
            if (f.getTenantId() != null && !f.getTenantId().equals(tenantId)) {
                throw new com.kashi.grc.common.exception.ForbiddenException("Not your organisation's data");
            }
            f.setUsableAsExample(true);
            feedbackRepository.save(f);
        });
    }

    // ── Edit distance ─────────────────────────────────────────────────────────

    /**
     * Normalised Levenshtein, 0.0 (identical) to 1.0 (nothing in common).
     *
     * Two rolling rows rather than a full matrix — a policy section can run to
     * several thousand characters and the square matrix would be tens of
     * megabytes for a number used only in a dashboard. Inputs are capped for the
     * same reason: precision beyond a few thousand characters adds nothing to
     * the signal.
     */
    public static double editRatio(String original, String edited) {
        if (original == null || edited == null) return 0.0;
        if (original.equals(edited)) return 0.0;

        String a = original.length() > 5000 ? original.substring(0, 5000) : original;
        String b = edited.length()   > 5000 ? edited.substring(0, 5000)   : edited;
        if (a.isEmpty() || b.isEmpty()) return 1.0;

        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;

        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] swap = prev; prev = curr; curr = swap;
        }

        double distance = prev[b.length()];
        return Math.min(1.0, distance / Math.max(a.length(), b.length()));
    }
}
