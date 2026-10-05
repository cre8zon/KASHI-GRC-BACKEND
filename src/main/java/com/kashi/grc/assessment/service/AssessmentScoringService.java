package com.kashi.grc.assessment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashi.grc.assessment.domain.AssessmentOptionInstance;
import com.kashi.grc.assessment.dto.request.AnswerRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Turning an answer into a score.
 *
 * ── NORMALISED WEIGHTED CONTRIBUTION, NOT RAW OPTION SCORE ────────────────
 * scoreEarned stores the normalised weighted contribution, so compliance %
 * stays bounded to 0–100 however option scores are configured in a template.
 * Same formula as ServiceNow GRC / OneTrust / Archer, unchanged from the
 * original:
 *
 *   SINGLE_CHOICE: (selectedScore / maxOptionScore) × weight
 *   MULTI_CHOICE:  (sumSelectedScores / sumAllOptionScores) × weight
 *   TEXT/DATE/NUMERIC: binary — answered = weight, unanswered = 0
 *   FILE_UPLOAD:   not scored here; scored via DocumentLink
 *
 *   totalPossible = SUM(weight)       — weight is the ceiling
 *   totalEarned   = SUM(scoreEarned)  — always ≤ totalPossible
 *
 * Raw option scores stay visible in the option display ("3/5 pts" for the
 * reviewer). They are not used in aggregation.
 *
 * ── WHY THIS IS A CLASS WITH NO REPOSITORIES ──────────────────────────────
 * Every option this needs is passed in. That is not purity for its own sake:
 * it collapses the query count and it makes the formula testable without a
 * database, which matters for the one piece of arithmetic that decides what a
 * customer sees as their compliance percentage.
 *
 * In the original, scoring a 5-option multi-select cost eleven queries — one
 * for the sum (which loads every option), one findById per selected option,
 * then one more findById per option again in the guard block to resolve its
 * text. Every one of those reads the same handful of rows. Here the caller
 * loads them once and passes the list.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssessmentScoringService {

    /**
     * The Spring-managed mapper, not a new one.
     *
     * The original constructed `new ObjectMapper()` at three separate points
     * inside this method. Construction is expensive, it happens on every
     * keystroke-driven save, and a hand-built mapper carries none of the
     * registrations Spring has applied to the shared bean. The output is the
     * same for a Long array either way — this is about not paying for it and
     * not having a second mapper whose configuration can drift.
     */
    private final ObjectMapper objectMapper;

    /**
     * The shaped answer: what to store, and what it scored.
     *
     * Bundled rather than returned piecemeal because the three values are
     * decided together — a multi-choice answer's text, its representative
     * option id and its score all come out of the same branch, and splitting
     * them across three calls is how they drift apart.
     */
    public record ScoredAnswer(String responseText,
                               Long selectedOptionInstanceId,
                               Double scoreEarned) {}

    /**
     * Scores one answer.
     *
     * @param options every option instance for this question, loaded once by
     *                the caller. Empty is valid — a text question has none.
     * @param weight  the question's weight; the ceiling for this answer.
     */
    public ScoredAnswer score(AnswerRequest req,
                              String responseType,
                              List<AssessmentOptionInstance> options,
                              double weight) {

        boolean multi = req.getSelectedOptionInstanceIds() != null
                && !req.getSelectedOptionInstanceIds().isEmpty();

        return multi
                ? scoreMultiChoice(req, options, weight)
                : scoreSingleOrText(req, responseType, options, weight);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // MULTI CHOICE
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * REPLACE semantics, preserved exactly.
     *
     * The frontend sends the COMPLETE set of selected options on every toggle,
     * and the stored set is replaced wholesale rather than toggled per id. That
     * is what removed the read-modify-write race behind "always 2 selected",
     * where rapid clicks produced concurrent requests each reading stale state
     * and overwriting the other. Do not reintroduce per-id toggling.
     */
    private ScoredAnswer scoreMultiChoice(AnswerRequest req,
                                          List<AssessmentOptionInstance> options,
                                          double weight) {
        Set<Long> selected = new LinkedHashSet<>(req.getSelectedOptionInstanceIds());

        String text;
        try {
            text = objectMapper.writeValueAsString(selected.stream().sorted().toList());
        } catch (Exception e) {
            // Preserved fallback. Set.toString() produces "[1, 2, 3]", which is
            // close enough to JSON that the guard's startsWith("[") check still
            // fires — so a serialisation failure degrades to a readable value
            // rather than losing the answer.
            log.warn("[SCORING] Could not serialise selection, falling back | qi={} — {}",
                    req.getQuestionInstanceId(), e.toString());
            text = selected.toString();
        }

        Long representative = req.getSelectedOptionInstanceIds().get(0);

        double sumAll = options.stream()
                .filter(o -> o.getScore() != null)
                .mapToDouble(AssessmentOptionInstance::getScore)
                .sum();

        Double earned;
        if (sumAll > 0) {
            Map<Long, AssessmentOptionInstance> byId = index(options);
            double sumSelected = selected.stream()
                    .map(byId::get)
                    .filter(o -> o != null && o.getScore() != null)
                    .mapToDouble(AssessmentOptionInstance::getScore)
                    .sum();
            earned = (sumSelected / sumAll) * weight;
        } else {
            // No option scores configured — binary: any selection earns full
            // weight. selected is non-empty here by construction.
            earned = weight;
        }

        return new ScoredAnswer(text, representative, earned);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SINGLE CHOICE / TEXT / NUMERIC / DATE / FILE
    // ═════════════════════════════════════════════════════════════════════════

    private ScoredAnswer scoreSingleOrText(AnswerRequest req,
                                           String responseType,
                                           List<AssessmentOptionInstance> options,
                                           double weight) {
        Long selectedId = req.getSelectedOptionInstanceId();
        String text     = req.getResponseText();

        if (selectedId != null) {
            AssessmentOptionInstance opt = index(options).get(selectedId);
            if (opt != null && opt.getScore() != null) {
                double maxScore = options.stream()
                        .filter(o -> o.getScore() != null)
                        .mapToDouble(AssessmentOptionInstance::getScore)
                        .max().orElse(0.0);
                // Options exist but none carry a score — full weight for
                // answering, rather than a divide by zero.
                double earned = (maxScore > 0) ? (opt.getScore() / maxScore) * weight : weight;
                return new ScoredAnswer(text, selectedId, earned);
            }
            // Option has no score — participation credit, preserved.
            return new ScoredAnswer(text, selectedId, weight);
        }

        if (text != null && !text.isBlank()) {
            // TEXT / NUMERIC / DATE — binary: answered earns full weight.
            return new ScoredAnswer(text, null, weight);
        }

        // ── NOTHING ANSWERED ──────────────────────────────────────────────
        //
        // THE ONE SCORE-AFFECTING CHANGE IN THIS EXTRACTION. Flagged rather
        // than made quietly, because it is the only place where v2 can compute
        // a different number from v1 for the same input — watch it during the
        // parallel run.
        //
        // The original never assigned scoreEarned on this path. For a brand new
        // answer that meant null, which aggregates as nothing. But an existing
        // answer is loaded from its stored row first, so clearing an answer
        // left the OLD score on the entity and upserted it straight back: the
        // question then read as unanswered while still earning full weight.
        //
        // So the original was already inconsistent — a never-answered question
        // scored 0, an un-answered one kept its marks. Zero is the answer that
        // is true in both cases.
        //
        // FILE_UPLOAD keeps null deliberately. It is not unscored-at-zero, it
        // is scored elsewhere, via DocumentLink — writing 0 here would look
        // like a real result and would be wrong the moment a file is attached.
        boolean fileUpload = "FILE_UPLOAD".equals(responseType);
        return new ScoredAnswer(text, null, fileUpload ? null : 0.0);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // OPTION TEXT, FOR THE GUARD
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * The human-readable values behind a stored answer.
     *
     * KashiGuard's OPTION_SELECTED rules match on text — "No", "Never" — not on
     * option ids, so the ids have to be resolved before evaluation. Done from
     * the already-loaded option list rather than re-querying each one, which is
     * what the original did after having just queried them for scoring.
     *
     * Returns null, not an empty list, when there is nothing to report: the
     * evaluator's contract distinguishes "no option values apply here" from
     * "the option values are empty", and passing an empty list asserts the
     * second.
     */
    public List<String> selectedOptionValues(String responseType,
                                             Long selectedOptionInstanceId,
                                             String responseText,
                                             List<AssessmentOptionInstance> options) {
        Map<Long, AssessmentOptionInstance> byId = index(options);
        List<String> values = new ArrayList<>();

        if ("SINGLE_CHOICE".equals(responseType) && selectedOptionInstanceId != null) {
            AssessmentOptionInstance o = byId.get(selectedOptionInstanceId);
            if (o != null && o.getOptionValue() != null) values.add(o.getOptionValue());

        } else if ("MULTI_CHOICE".equals(responseType)
                && responseText != null && responseText.startsWith("[")) {
            try {
                Long[] ids = objectMapper.readValue(responseText, Long[].class);
                for (Long id : ids) {
                    AssessmentOptionInstance o = byId.get(id);
                    if (o != null && o.getOptionValue() != null) values.add(o.getOptionValue());
                }
            } catch (Exception e) {
                // Preserved: a stored value that will not parse means the guard
                // evaluates without option text rather than the whole save
                // failing. Logged now, where the original swallowed it silently
                // — a question whose rules quietly stop matching is worth a line.
                log.warn("[SCORING] Could not parse stored selection for guard | text='{}' — {}",
                        responseText, e.toString());
            }
        }
        return values.isEmpty() ? null : values;
    }

    private Map<Long, AssessmentOptionInstance> index(List<AssessmentOptionInstance> options) {
        return options.stream().collect(Collectors.toMap(
                AssessmentOptionInstance::getId, Function.identity(), (a, b) -> a));
    }
}
