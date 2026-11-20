package com.kashi.grc.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.kashi.grc.ai.chat.AiChatService;
import com.kashi.grc.ai.domain.AiEnums.ChunkSourceType;
import com.kashi.grc.ai.domain.AiEnums.TaskType;
import com.kashi.grc.ai.guardrail.PromptInjectionScanner;
import com.kashi.grc.ai.policy.BritishSpelling;
import com.kashi.grc.ai.rag.RetrievalService;
import com.kashi.grc.ai.repository.AiOrgProfileRepository;
import com.kashi.grc.tenant.repository.TenantRepository;
import com.kashi.grc.uiconfig.dto.response.UiNavigationItemResponse;
import com.kashi.grc.uiconfig.service.UiConfigService;
import com.kashi.grc.usermanagement.domain.User;
import com.kashi.grc.usermanagement.repository.UserRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * KashiGuard AI — answers questions about the tenant's own GRC data, with a
 * citation for every fact. Read-only: nothing here writes to any register.
 *
 * ── WHAT IT READS, AND FOR WHOM ──────────────────────────────────────────────
 *   1. Registers (AgentSource) the asking user's sidebar shows — counts by
 *      status/severity, plus up to RECORDS_PER_SOURCE matching records.
 *   2. The document corpus (RetrievalService): the tenant's policies (only if
 *      the user can see Policies), control requirement text, framework text
 *      and knowledge notes. Tenant isolation is enforced inside retrieval.
 *   3. The record the user is looking at, when asked from a record's side panel.
 *
 * Everything the model sees is labelled [R1], [S1]… and the model may only
 * state what those labels support. The response carries the labels it cited
 * with a link to each record or policy, so every sentence can be checked.
 *
 * ── WHY NOT TOOL CALLING ────────────────────────────────────────────────────
 * The provider layer is template-in, text-out across vendors, and a model that
 * chooses its own queries is harder to bound. Here the reads are decided in
 * code from the question and the user's access, then handed over as data —
 * the model cannot reach anything the code did not fetch for this user.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private static final int RECORDS_PER_SOURCE = 8;
    private static final int RAG_TOP_K          = 6;
    private static final int HISTORY_TURNS      = 6;
    private static final int HISTORY_CHARS      = 1500;

    private final AgentRecordFinder      finder;
    private final UiConfigService        uiConfigService;
    private final RetrievalService       retrievalService;
    private final PromptInjectionScanner injectionScanner;
    private final AiChatService          chatService;
    private final UserRepository         userRepository;
    private final TenantRepository       tenantRepository;
    private final AiOrgProfileRepository orgProfileRepository;
    private final com.kashi.grc.common.security.PermissionGate permissionGate;

    // ── DTOs ──────────────────────────────────────────────────────────────────

    @Data
    public static class AskRequest {
        private String question;
        private List<Turn> history;
        /** The page the question was asked from, e.g. /module/risk/12. Optional. */
        private String contextRoute;
    }

    @Data
    public static class Turn {
        private String role;     // user | assistant
        private String content;
    }

    public record Citation(String label, String kind, String type, String ref, String title, String url) {}

    /**
     * Something the user can choose to do next, offered beside the answer.
     * The agent never performs it: the frontend opens the normal screen for it,
     * pre-filled, and the person completes it there.
     */
    public record AgentAction(String type, String label, String title, String intent, List<String> frameworks) {}

    public record AskResponse(String answer, List<Citation> citations, List<String> followUps,
                              List<String> consulted, Long interactionId, List<AgentAction> actions) {}

    public record SourceInfo(String key, String label, String route) {}

    // ── Context: what this user can ask about ───────────────────────────────

    public Map<String, Object> context(User user) {
        Map<String, String> granted = grantedSources();
        List<SourceInfo> sources = new ArrayList<>();
        AgentSource.ALL.forEach(s -> { if (granted.containsKey(s.key())) sources.add(new SourceInfo(s.key(), s.labelPlural(), granted.get(s.key()))); });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sources", sources);
        out.put("suggestions", suggestions(granted.keySet()));
        out.put("actions", availableActions(granted));
        return out;
    }

    /** Actions this user could complete: register in their navigation AND the
     *  permission held. The frontend offers exactly these (chips, "/" menu). */
    private List<AgentCapability> availableActions(Map<String, String> granted) {
        List<AgentCapability> out = new ArrayList<>();
        for (AgentCapability a : AgentCapability.ALL) {
            if (granted.containsKey(a.source()) && permissionGate.has(a.permission())) out.add(a);
        }
        return out;
    }

    private boolean canDo(String type, Map<String, String> granted) {
        return availableActions(granted).stream().anyMatch(a -> a.type().equals(type));
    }

    private List<String> suggestions(Set<String> keys) {
        List<String> s = new ArrayList<>();
        if (keys.contains("ISSUE"))     s.add("Which high or critical issues are still open, and who owns them?");
        if (keys.contains("RISK"))      s.add("Summarise our top risks by residual score and their treatment.");
        if (keys.contains("POLICY"))    s.add("Which policies are still in draft or due for review?");
        if (keys.contains("INCIDENT"))  s.add("What incidents have we had, and what was the root cause?");
        if (keys.contains("VENDOR"))    s.add("Which vendors are critical, and what is their risk rating?");
        if (keys.contains("EXCEPTION")) s.add("Which policy exceptions expire soon?");
        if (keys.contains("ASSET"))     s.add("List our critical production assets and their owners.");
        s.add("What does ISO 27001 expect for access reviews, and what do our policies say about it?");
        return s.size() > 6 ? s.subList(0, 6) : s;
    }

    // ── Ask ───────────────────────────────────────────────────────────────────

    public AskResponse ask(AskRequest req, User user) {
        String question = req.getQuestion() == null ? "" : req.getQuestion().trim();
        if (question.isEmpty()) throw new com.kashi.grc.common.exception.BusinessException(
                "AGENT_EMPTY_QUESTION", "Ask a question", org.springframework.http.HttpStatus.BAD_REQUEST);
        if (question.length() > 2000) question = question.substring(0, 2000);
        String qLower = question.toLowerCase(Locale.ROOT);
        Long tenantId = user.getTenantId();

        Map<String, String> granted = grantedSources();     // key -> route the user has
        List<String> words = keywords(qLower);
        List<String> consulted = new ArrayList<>();

        Map<String, Citation> labels = new LinkedHashMap<>();
        StringBuilder records = new StringBuilder();
        StringBuilder stats   = new StringBuilder();

        // 1 ── the record on screen, if any
        AgentSource ctxSource = null;
        Long ctxId = null;
        if (req.getContextRoute() != null) {
            ctxSource = AgentSource.byRoute(req.getContextRoute());
            ctxId = lastNumber(req.getContextRoute());
            if (ctxSource != null && ctxId != null && granted.containsKey(ctxSource.key())) {
                AgentSource cs = ctxSource;
                Long cid = ctxId;
                finder.byId(cs, tenantId, cid).ifPresent(f -> {
                    records.append("CURRENT RECORD (the user is looking at this)\n");
                    appendRecord(records, labels, cs, granted.get(cs.key()), f, true);
                    records.append('\n');
                });
            }
        }

        // 2 ── registers the question points at; all granted ones when it names none
        List<AgentSource> targets = new ArrayList<>();
        for (AgentSource s : AgentSource.ALL) {
            // Asked from a register's list page ("your incidents"), that
            // register is searched even when the question does not name it.
            boolean onItsList = s == ctxSource && ctxId == null;
            if (granted.containsKey(s.key()) && (s.mentionedIn(qLower) || onItsList)) targets.add(s);
        }
        boolean broad = targets.isEmpty();
        if (broad) AgentSource.ALL.forEach(s -> { if (granted.containsKey(s.key())) targets.add(s); });

        for (AgentSource s : targets) {
            consulted.add(s.labelPlural());
            Map<String, Long> byStatus = finder.countByStatus(s, tenantId);
            long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
            stats.append("- ").append(s.labelPlural()).append(": ").append(total).append(" in total");
            if (!byStatus.isEmpty()) stats.append("; by status ").append(fmtCounts(byStatus));
            Map<String, Long> bySev = finder.countBySeverity(s, tenantId);
            if (!bySev.isEmpty()) stats.append("; by severity ").append(fmtCounts(bySev));
            stats.append('\n');

            // A question that names no register gets counts only — listing a
            // sample of every register would drown the answer in noise.
            if (broad && words.isEmpty()) continue;
            List<AgentRecordFinder.Found> found = finder.search(s, tenantId, stripSourceWords(words, s), RECORDS_PER_SOURCE);
            if (found.isEmpty() && !words.isEmpty() && !broad) {
                found = finder.search(s, tenantId, List.of(), RECORDS_PER_SOURCE);   // named the register, no word matched
            }
            if (found.isEmpty()) continue;
            records.append(s.labelPlural().toUpperCase(Locale.ROOT))
                    .append(found.size() == RECORDS_PER_SOURCE ? " (most recent matches, not all)" : "").append('\n');
            for (AgentRecordFinder.Found f : found) {
                if (ctxSource == s && Objects.equals(String.valueOf(f.id()), String.valueOf(ctxId))) continue;
                appendRecord(records, labels, s, granted.get(s.key()), f, false);
            }
            records.append('\n');
        }

        // 3 ── documents
        List<ChunkSourceType> types = new ArrayList<>(List.of(
                ChunkSourceType.CONTROL, ChunkSourceType.FRAMEWORK_TEXT, ChunkSourceType.KNOWLEDGE_NOTE));
        if (granted.containsKey("POLICY")) types.add(0, ChunkSourceType.POLICY);
        RetrievalService.RetrievalResult rag = retrievalService.retrieve(question, tenantId, types, RAG_TOP_K);
        StringBuilder docs = new StringBuilder();
        int sN = 0;
        for (RetrievalService.RetrievedChunk c : rag.chunks()) {
            String label = "S" + (++sN);
            String url = c.sourceType() == ChunkSourceType.POLICY && !c.isGlobal() && c.sourceId() != null
                    && granted.containsKey("POLICY") ? granted.get("POLICY") + "/" + c.sourceId() : null;
            labels.put(label, new Citation(label, "DOCUMENT", prettyType(c.sourceType()),
                    c.sourceRef(), c.citation(), url));
            docs.append('[').append(label).append("] ").append(c.citation())
                    .append(c.isGlobal() ? " (platform library)" : " (your organisation)").append('\n')
                    .append(injectionScanner.scan(trim(c.content(), 1800)).sanitised()).append("\n\n");
        }
        if (sN > 0) consulted.add("Documents");

        String recordsBlock = records.length() == 0 ? "(no matching records)"
                : injectionScanner.wrapUntrusted("organisation-records",
                injectionScanner.scan(resolvePeople(records.toString())).sanitised());
        String docsBlock = docs.length() == 0 ? "(no documents retrieved)"
                : injectionScanner.wrapUntrusted("retrieved-documents", docs.toString().trim());

        // A request to draft a policy gets a "start a draft" card, for users who
        // may create policies. The model is told so it points at existing
        // policies that already cover the topic rather than explaining how to
        // create one.
        List<AgentAction> actions = new ArrayList<>();
        AgentAction draft = draftPolicyAction(question, qLower, granted);
        String questionForModel = question;
        if (draft != null) {
            actions.add(draft);
            questionForModel = question + "\n\n(KashiGuard is offering the user a button to start this draft in the "
                    + "policy editor. Do not explain how to create a policy. If an existing policy in RECORDS or "
                    + "DOCUMENTS already covers this topic, say so with its label so they can update it instead.)";
        }

        AiChatService.AiResult r = chatService.completeJson(
                AiChatService.AiCall.of("agent.answer", TaskType.AGENT_ANSWER)
                        .tenant(tenantId).user(user.getId())
                        .entity(ctxSource != null ? ctxSource.key() : "AGENT", ctxId)
                        .chunks(rag.chunkIds())
                        .var("question", questionForModel)
                        .var("history", history(req.getHistory()))
                        .var("orgName", orgName(tenantId))
                        .var("today", LocalDate.now().toString())
                        .var("accessible", accessibleLine(granted))
                        .var("statistics", stats.length() == 0 ? "(no registers available to this user)" : stats.toString().trim())
                        .var("records", recordsBlock)
                        .var("documents", docsBlock));

        JsonNode json = r.json();
        String answer = json == null ? r.content() : json.path("answer").asText(r.content());
        if (british(tenantId)) answer = BritishSpelling.text(answer);

        // Cited labels, in order of first use in the answer; unknown labels dropped.
        LinkedHashSet<String> used = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\\[([RS]\\d+)]").matcher(answer == null ? "" : answer);
        while (m.find()) if (labels.containsKey(m.group(1))) used.add(m.group(1));
        List<Citation> citations = used.stream().map(labels::get).toList();

        List<String> followUps = new ArrayList<>();
        if (json != null) json.path("followUps").forEach(n -> { if (followUps.size() < 3 && !n.asText("").isBlank()) followUps.add(n.asText()); });

        log.info("[AGENT] tenant={} user={} sources={} records={} docs={} cited={}",
                tenantId, user.getId(), consulted, labels.size() - sN, sN, used.size());
        return new AskResponse(answer, citations, followUps, consulted, r.interactionId(), actions);
    }

    // ── Proposed actions ──────────────────────────────────────────────────────

    private static final Pattern DRAFT_VERB = Pattern.compile(
            "\\b(draft|create|write|generate|prepare|make|build|need|want)\\b[^.?!]{0,60}?\\bpolic(y|ies)\\b");
    private static final Pattern DRAFT_TITLE = Pattern.compile(
            "\\b(?:draft|create|write|generate|prepare|make|build)\\s+(?:me\\s+|us\\s+)?(?:an?\\s+|the\\s+|our\\s+)?(?:new\\s+)?([a-z0-9&/,' \\-]{3,60}?\\bpolicy)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LOOKUP_OPENER = Pattern.compile(
            "^\\s*(which|what|how many|how much|list|show|who|when|where|is|are|has|have|does|did)\\b");
    private static final Map<String, Pattern> FRAMEWORK_WORDS = new LinkedHashMap<>();
    static {
        FRAMEWORK_WORDS.put("ISO27001", Pattern.compile("iso\\s*/?\\s*(iec\\s*)?27001"));
        FRAMEWORK_WORDS.put("SOC2",     Pattern.compile("\\bsoc\\s*-?\\s*2\\b|\\bsoc\\s*ii\\b"));
        FRAMEWORK_WORDS.put("DPDPA",    Pattern.compile("\\bdpdp"));
        FRAMEWORK_WORDS.put("CERTIN",   Pattern.compile("cert\\s*-?\\s*in\\b"));
        FRAMEWORK_WORDS.put("RBI",      Pattern.compile("\\brbi\\b"));
        FRAMEWORK_WORDS.put("PSS",      Pattern.compile("\\bpss\\b|payment and settlement"));
    }

    /**
     * "Draft an access control policy for ISO 27001" -> a CREATE_POLICY card.
     * Offered only to users who can see Policies and hold AUDIT_CREATE — the
     * permission the policy create screen itself requires. Lookups that merely
     * mention policies ("which policies are in draft?") are not requests.
     */
    private AgentAction draftPolicyAction(String question, String qLower, Map<String, String> granted) {
        // Same gate as the chips and the "/" menu (AgentCapability), so the
        // card can never appear for someone the menu would not offer it to.
        if (!canDo("CREATE_POLICY", granted)) return null;
        if (LOOKUP_OPENER.matcher(qLower).find() || !DRAFT_VERB.matcher(qLower).find()) return null;

        String title = null;
        Matcher t = DRAFT_TITLE.matcher(question);
        if (t.find()) {
            String raw = t.group(1).trim().replaceAll("\\s+", " ")
                    .replaceFirst("(?i)^((me|us|a|an|the|our|new)\\s+)+", "");
            if (!raw.equalsIgnoreCase("policy")) title = titleCase(raw);
        }
        List<String> fws = new ArrayList<>();
        FRAMEWORK_WORDS.forEach((code, p) -> { if (p.matcher(qLower).find()) fws.add(code); });
        return new AgentAction("CREATE_POLICY",
                title != null ? "Start a draft: " + title : "Start a policy draft",
                title, question, fws);
    }

    private static String titleCase(String s) {
        Set<String> small = Set.of("and", "or", "of", "for", "the", "a", "an", "to", "in", "on");
        StringBuilder out = new StringBuilder();
        String[] w = s.split(" ");
        for (int i = 0; i < w.length; i++) {
            String x = w[i];
            if (x.isEmpty()) continue;
            boolean keep = x.length() > 1 && x.equals(x.toUpperCase(Locale.ROOT));      // acronyms: HR, BCP
            String y = keep ? x : (i > 0 && small.contains(x.toLowerCase(Locale.ROOT)) ? x.toLowerCase(Locale.ROOT)
                                   : Character.toUpperCase(x.charAt(0)) + x.substring(1).toLowerCase(Locale.ROOT));
            if (out.length() > 0) out.append(' ');
            out.append(y);
        }
        return out.toString();
    }

    // ── Access ────────────────────────────────────────────────────────────────

    /** Source key -> the route the user's own navigation grants it through. */
    private Map<String, String> grantedSources() {
        Set<String> routes = new HashSet<>();
        collectRoutes(uiConfigService.getNavigation(), routes);
        Map<String, String> out = new LinkedHashMap<>();
        for (AgentSource s : AgentSource.ALL) {
            String r = s.grantedRoute(routes);
            if (r != null) out.put(s.key(), r);
        }
        return out;
    }

    private void collectRoutes(List<UiNavigationItemResponse> items, Set<String> out) {
        if (items == null) return;
        for (UiNavigationItemResponse i : items) {
            // Active rows only: inactive rows are the hidden detail-route registry
            // (e.g. "Risk Detail" /module/risk/:id with no permission), and some
            // carry looser permissions than the menu entry a user actually sees.
            if (i.isActive() && i.getRoute() != null && !i.getRoute().isBlank()) {
                out.add(i.getRoute().split("\\?")[0].toLowerCase(Locale.ROOT));
            }
            collectRoutes(i.getChildren(), out);
        }
    }

    // ── Formatting ────────────────────────────────────────────────────────────

    private void appendRecord(StringBuilder sb, Map<String, Citation> labels, AgentSource s, String route,
                              AgentRecordFinder.Found f, boolean current) {
        long n = labels.keySet().stream().filter(k -> k.startsWith("R")).count();
        String label = "R" + (n + 1);
        labels.put(label, new Citation(label, "RECORD", s.label(), f.ref(), f.title(), route + "/" + f.id()));
        sb.append('[').append(label).append("] ").append(s.label());
        if (f.ref() != null) sb.append(' ').append(f.ref());
        sb.append(" — ").append(f.title() == null ? "(untitled)" : f.title());
        if (f.status() != null)   sb.append(" | status ").append(f.status());
        if (f.severity() != null) sb.append(" | severity ").append(f.severity());
        f.fields().forEach((k, v) -> {
            if (AgentSource.PERSON_FIELDS.contains(k)) sb.append(" | ").append(k.equals("requestedBy") ? "requested by" : "owner").append(" {{person:").append(v).append("}}");
            else sb.append(" | ").append(humanise(k)).append(' ').append(v);
        });
        sb.append('\n');
        if (f.description() != null && !f.description().isBlank()) {
            sb.append("    ").append(trim(stripHtml(f.description()), current ? 1500 : 300)).append('\n');
        }
    }

    /** Owner ids were written as {{person:id}}; swap in names with one query. */
    private String resolvePeople(String text) {
        Matcher m = Pattern.compile("\\{\\{person:(\\d+)}}").matcher(text);
        Set<Long> ids = new HashSet<>();
        while (m.find()) ids.add(Long.valueOf(m.group(1)));
        Map<Long, String> names = new HashMap<>();
        if (!ids.isEmpty()) userRepository.findAllById(ids).forEach(u ->
                names.put(u.getId(), u.getFullName() == null || u.getFullName().isBlank() ? "user #" + u.getId() : u.getFullName()));
        m.reset();
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Long id = Long.valueOf(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(names.getOrDefault(id, "unassigned")));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String history(List<Turn> turns) {
        if (turns == null || turns.isEmpty()) return "(this is the first question)";
        List<Turn> last = turns.size() > HISTORY_TURNS ? turns.subList(turns.size() - HISTORY_TURNS, turns.size()) : turns;
        StringBuilder sb = new StringBuilder();
        for (Turn t : last) {
            if (t == null || t.getContent() == null) continue;
            sb.append("assistant".equalsIgnoreCase(t.getRole()) ? "Assistant: " : "User: ")
                    .append(trim(t.getContent(), HISTORY_CHARS)).append('\n');
        }
        return sb.length() == 0 ? "(this is the first question)" : sb.toString().trim();
    }

    private String accessibleLine(Map<String, String> granted) {
        if (granted.isEmpty()) return "no registers";
        List<String> l = new ArrayList<>();
        AgentSource.ALL.forEach(s -> { if (granted.containsKey(s.key())) l.add(s.labelPlural()); });
        return String.join(", ", l);
    }

    private String orgName(Long tenantId) {
        if (tenantId == null) return "the organisation";
        return orgProfileRepository.findByTenantId(tenantId)
                .map(p -> p.getShortName() != null && !p.getShortName().isBlank() ? p.getShortName() : p.getLegalName())
                .filter(n -> n != null && !n.isBlank())
                .orElseGet(() -> tenantRepository.findById(tenantId).map(t -> t.getName()).orElse("the organisation"));
    }

    private boolean british(Long tenantId) {
        if (tenantId == null) return true;
        return orgProfileRepository.findByTenantId(tenantId)
                .map(p -> p.getSpellingVariant() == null || !"AMERICAN".equalsIgnoreCase(p.getSpellingVariant()))
                .orElse(true);
    }

    private static final Set<String> STOP = Set.of(
            "the", "and", "for", "are", "our", "what", "which", "who", "whom", "how", "many", "much", "with",
            "that", "this", "these", "those", "have", "has", "had", "was", "were", "will", "would", "should",
            "could", "can", "does", "did", "any", "all", "list", "show", "tell", "give", "about", "from", "into",
            "their", "them", "they", "there", "where", "when", "why", "still", "open", "closed", "status",
            "summarise", "summarize", "please", "me", "we", "us", "is", "it", "its", "of", "to", "in", "on",
            "by", "or", "an", "a", "be", "do", "not", "no", "yes", "top", "most", "latest", "recent", "due",
            "high", "critical", "medium", "low", "owner", "owns", "own", "current", "currently", "each", "every");

    private static List<String> keywords(String qLower) {
        List<String> out = new ArrayList<>();
        for (String w : qLower.split("[^a-z0-9.\\-]+")) {
            if (w.length() < 3 || STOP.contains(w)) continue;
            if (!out.contains(w)) out.add(w);
            if (out.size() >= 6) break;
        }
        return out;
    }

    /** "risks" names the register; it is not a word to search inside risk titles. */
    private static List<String> stripSourceWords(List<String> words, AgentSource s) {
        List<String> out = new ArrayList<>();
        for (String w : words) {
            boolean names = false;
            for (String k : s.keywords()) if (w.startsWith(k) || k.startsWith(w)) { names = true; break; }
            if (!names && !w.equals(s.label().toLowerCase(Locale.ROOT)) && !w.equals(s.labelPlural().toLowerCase(Locale.ROOT))) out.add(w);
        }
        return out;
    }

    private static Long lastNumber(String route) {
        Matcher m = Pattern.compile("/(\\d+)(?:[/?#]|$)").matcher(route);
        Long last = null;
        while (m.find()) last = Long.valueOf(m.group(1));
        return last;
    }

    private static String fmtCounts(Map<String, Long> m) {
        StringJoiner j = new StringJoiner(", ");
        m.forEach((k, v) -> j.add((k == null ? "unset" : k) + " " + v));
        return j.toString();
    }

    private static String humanise(String attr) {
        return attr.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
    }

    private static String prettyType(ChunkSourceType t) {
        return switch (t) {
            case POLICY, POLICY_TEMPLATE -> "Policy";
            case CONTROL -> "Control";
            case FRAMEWORK_TEXT -> "Framework";
            case KNOWLEDGE_NOTE -> "Knowledge note";
            default -> "Document";
        };
    }

    private static String stripHtml(String s) {
        return s.replaceAll("<[^>]+>", " ").replaceAll("&nbsp;", " ").replaceAll("\\s+", " ").trim();
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}