package com.kashi.grc.ai.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashi.grc.ai.chat.AiChatService;
import com.kashi.grc.ai.chat.AiChatService.AiCall;
import com.kashi.grc.ai.domain.AiEnums.TaskType;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Golden-set regression testing for prompts.
 *
 * ── WHY THIS IS THE LAYER NOBODY BUILDS AND EVERYONE NEEDS ───────────────────
 * Prompt changes are invisible regressions. You improve the draft prompt to make
 * section headings crisper, and three weeks later somebody notices that control
 * mappings stopped appearing. Nothing failed. No test went red. The output was
 * fluent the entire time.
 *
 * The reason mature AI products feel more reliable is not better prompts — it is
 * that they can TELL when a prompt got worse. That requires a fixed set of
 * inputs with known-good properties, run on every change, scored automatically.
 * It is unglamorous and it is the difference between shipping confidently and
 * shipping hopefully.
 *
 * ── SCORING STRATEGIES ───────────────────────────────────────────────────────
 *   CONTAINS      output mentions required strings — cheap, brittle, useful
 *   NOT_CONTAINS  output avoids forbidden strings — catches placeholder leakage
 *   JSON_PATH     a JSON field exists and matches — the workhorse for structured tasks
 *   JSON_MIN_SIZE an array has at least N items — "did it produce any mappings"
 *   NO_FABRICATION every cited code is in the allowed set — the safety regression
 *   LLM_JUDGE     a model scores the output against a rubric — for qualities no
 *                 assertion captures, like tone. Use sparingly: it costs a call
 *                 per case and it is the least reliable of the six.
 *
 * ── RUNNING ──────────────────────────────────────────────────────────────────
 * Cases live in src/test/resources/ai-golden/*.json. Every run is flagged
 * evalRun=true in ai_interactions so eval traffic never pollutes customer
 * billing or the acceptance dashboards.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiEvalHarness {

    private final AiChatService chatService;
    private final ObjectMapper  mapper;

    public enum Strategy { CONTAINS, NOT_CONTAINS, JSON_PATH, JSON_MIN_SIZE, NO_FABRICATION, LLM_JUDGE }

    @Data
    public static class EvalCase {
        private String  id;
        private String  description;
        private String  templateKey;
        private TaskType taskType;
        private Map<String, Object> variables;
        private List<Assertion> assertions;
        /** Allowed reference set for NO_FABRICATION. */
        private List<String> allowedReferences;
    }

    @Data
    public static class Assertion {
        private Strategy strategy;
        private String   path;      // JSON pointer-ish: "sections", "title"
        private String   expected;  // substring, or comma-separated list
        private Integer  minSize;
        private String   rubric;    // LLM_JUDGE only
        private Double   minScore;  // LLM_JUDGE only
    }

    public record AssertionResult(Strategy strategy, boolean passed, String detail) {}

    public record CaseResult(String caseId, boolean passed, List<AssertionResult> assertions,
                             long latencyMs, int tokens, String output) {}

    public record RunResult(int total, int passed, int failed, List<CaseResult> cases,
                            long totalTokens, long durationMs) {
        public double passRate() { return total == 0 ? 0 : (double) passed / total; }
    }

    // ── Loading ───────────────────────────────────────────────────────────────

    public List<EvalCase> loadCases(String globPattern) {
        List<EvalCase> cases = new ArrayList<>();
        try {
            Resource[] files = new PathMatchingResourcePatternResolver().getResources(globPattern);
            for (Resource f : files) {
                try (var in = f.getInputStream()) {
                    String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    JsonNode root = mapper.readTree(json);
                    if (root.isArray()) {
                        for (JsonNode n : root) cases.add(mapper.treeToValue(n, EvalCase.class));
                    } else {
                        cases.add(mapper.treeToValue(root, EvalCase.class));
                    }
                }
            }
        } catch (Exception e) {
            log.error("[AI-EVAL] could not load cases from {}: {}", globPattern, e.getMessage());
        }
        log.info("[AI-EVAL] loaded {} case(s)", cases.size());
        return cases;
    }

    // ── Running ───────────────────────────────────────────────────────────────

    public RunResult run(List<EvalCase> cases, Long tenantId) {
        long started = System.currentTimeMillis();
        List<CaseResult> results = new ArrayList<>();
        long tokens = 0;

        for (EvalCase c : cases) {
            CaseResult r = runOne(c, tenantId);
            results.add(r);
            tokens += r.tokens();
            log.info("[AI-EVAL] {} — {}", c.getId(), r.passed() ? "PASS" : "FAIL");
        }

        int passed = (int) results.stream().filter(CaseResult::passed).count();
        RunResult run = new RunResult(cases.size(), passed, cases.size() - passed,
                results, tokens, System.currentTimeMillis() - started);

        log.info("[AI-EVAL] complete | {}/{} passed ({}%) | {} tokens | {}ms",
                passed, cases.size(), Math.round(run.passRate() * 100), tokens, run.durationMs());
        return run;
    }

    private CaseResult runOne(EvalCase c, Long tenantId) {
        long started = System.currentTimeMillis();
        try {
            AiCall call = AiCall.of(c.getTemplateKey(), c.getTaskType())
                    .tenant(tenantId)
                    .eval(true)                     // never counted as customer usage
                    .cacheable(false);              // a cached hit would test nothing
            if (c.getVariables() != null) call.vars(c.getVariables());

            AiChatService.AiResult result = chatService.complete(call);

            List<AssertionResult> checks = new ArrayList<>();
            for (Assertion a : c.getAssertions()) checks.add(evaluate(a, result, c, tenantId));

            boolean allPassed = checks.stream().allMatch(AssertionResult::passed);
            return new CaseResult(c.getId(), allPassed, checks,
                    System.currentTimeMillis() - started, result.totalTokens(), result.content());

        } catch (Exception e) {
            return new CaseResult(c.getId(), false,
                    List.of(new AssertionResult(null, false, "case threw: " + e.getMessage())),
                    System.currentTimeMillis() - started, 0, null);
        }
    }

    private AssertionResult evaluate(Assertion a, AiChatService.AiResult result, EvalCase c, Long tenantId) {
        String content = result.content() == null ? "" : result.content();
        JsonNode json  = result.json();

        return switch (a.getStrategy()) {

            case CONTAINS -> {
                boolean ok = true;
                StringBuilder missing = new StringBuilder();
                for (String needle : a.getExpected().split(",")) {
                    if (!content.toLowerCase().contains(needle.trim().toLowerCase())) {
                        ok = false; missing.append(needle.trim()).append(' ');
                    }
                }
                yield new AssertionResult(a.getStrategy(), ok, ok ? "all present" : "missing: " + missing);
            }

            case NOT_CONTAINS -> {
                boolean ok = true;
                StringBuilder found = new StringBuilder();
                for (String needle : a.getExpected().split(",")) {
                    if (content.toLowerCase().contains(needle.trim().toLowerCase())) {
                        ok = false; found.append(needle.trim()).append(' ');
                    }
                }
                yield new AssertionResult(a.getStrategy(), ok, ok ? "clean" : "forbidden text present: " + found);
            }

            case JSON_PATH -> {
                if (json == null) yield new AssertionResult(a.getStrategy(), false, "no JSON parsed");
                JsonNode node = walk(json, a.getPath());
                boolean ok = !node.isMissingNode() && !node.isNull()
                        && (a.getExpected() == null || node.asText().contains(a.getExpected()));
                yield new AssertionResult(a.getStrategy(), ok,
                        ok ? a.getPath() + " ok" : a.getPath() + " missing or mismatched");
            }

            case JSON_MIN_SIZE -> {
                if (json == null) yield new AssertionResult(a.getStrategy(), false, "no JSON parsed");
                JsonNode node = walk(json, a.getPath());
                int size = node.isArray() ? node.size() : 0;
                boolean ok = size >= (a.getMinSize() == null ? 1 : a.getMinSize());
                yield new AssertionResult(a.getStrategy(), ok, a.getPath() + " size=" + size);
            }

            /*
             * The safety regression. If a prompt change ever reintroduces
             * fabricated control codes, this is the assertion that catches it —
             * and it is the one worth running on every single change.
             */
            case NO_FABRICATION -> {
                if (json == null) yield new AssertionResult(a.getStrategy(), false, "no JSON parsed");
                List<String> bad = new ArrayList<>();
                for (JsonNode n : walk(json, a.getPath())) {
                    String code = n.isTextual() ? n.asText() : n.path("controlCode").asText(null);
                    if (code != null && c.getAllowedReferences() != null
                            && c.getAllowedReferences().stream().noneMatch(x -> x.equalsIgnoreCase(code))) {
                        bad.add(code);
                    }
                }
                yield new AssertionResult(a.getStrategy(), bad.isEmpty(),
                        bad.isEmpty() ? "no fabricated references" : "FABRICATED: " + bad);
            }

            case LLM_JUDGE -> {
                try {
                    AiChatService.AiResult judged = chatService.completeJson(
                            AiCall.of("eval.judge", TaskType.EVAL_JUDGE)
                                    .tenant(tenantId).eval(true)
                                    .var("output", content)
                                    .var("rubric", a.getRubric()));
                    double score = judged.json().path("score").asDouble(0);
                    double min   = a.getMinScore() == null ? 0.7 : a.getMinScore();
                    yield new AssertionResult(a.getStrategy(), score >= min,
                            "judge scored " + score + " (min " + min + "): "
                                    + judged.json().path("reasoning").asText(""));
                } catch (Exception e) {
                    yield new AssertionResult(a.getStrategy(), false, "judge failed: " + e.getMessage());
                }
            }
        };
    }

    /** Dotted path walk: "result.sections" or just "sections". */
    private JsonNode walk(JsonNode root, String path) {
        if (path == null || path.isBlank()) return root;
        JsonNode cur = root;
        for (String part : path.split("\\.")) cur = cur.path(part);
        return cur;
    }

    /** Console-friendly report for CI output. */
    public String formatReport(RunResult run) {
        StringBuilder sb = new StringBuilder();
        sb.append("AI EVAL — ").append(run.passed()).append('/').append(run.total())
          .append(" passed (").append(Math.round(run.passRate() * 100)).append("%), ")
          .append(run.totalTokens()).append(" tokens, ").append(run.durationMs()).append("ms\n\n");
        for (CaseResult c : run.cases()) {
            sb.append(c.passed() ? "  PASS  " : "  FAIL  ").append(c.caseId()).append('\n');
            if (!c.passed()) {
                for (AssertionResult a : c.assertions()) {
                    if (!a.passed()) sb.append("          ").append(a.strategy()).append(": ").append(a.detail()).append('\n');
                }
            }
        }
        return sb.toString();
    }

    /** Convenience for a scheduled or CI invocation. */
    public Map<String, Object> runGoldenSet(Long tenantId) {
        RunResult run = run(loadCases("classpath*:ai-golden/*.json"), tenantId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", run.total());
        out.put("passed", run.passed());
        out.put("failed", run.failed());
        out.put("passRate", Math.round(run.passRate() * 1000) / 10.0);
        out.put("totalTokens", run.totalTokens());
        out.put("durationMs", run.durationMs());
        out.put("report", formatReport(run));
        return out;
    }
}
