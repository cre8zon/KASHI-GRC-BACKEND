package com.kashi.grc.ai.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashi.grc.ai.chat.AiChatService;
import com.kashi.grc.ai.chat.AiChatService.AiCall;
import com.kashi.grc.ai.config.AiProperties;
import com.kashi.grc.ai.domain.AiEnums.TaskType;
import com.kashi.grc.ai.guardrail.ReferenceIntegrityGuard;
import com.kashi.grc.ai.orchestration.AiPipeline;
import com.kashi.grc.ai.orchestration.AiPipelineContext;
import com.kashi.grc.ai.orchestration.AiStep;
import com.kashi.grc.ai.orchestration.SelfCritiqueStep;
import com.kashi.grc.ai.policy.PolicyAiDtos.*;
import com.kashi.grc.audit.domain.AuditPolicy;
import com.kashi.grc.audit.repository.AuditPolicyRepository;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.ucf.domain.CommonControl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The policy AI surface. Every user-visible AI action on a policy lives here.
 *
 * ── THE FIVE ACTIONS ─────────────────────────────────────────────────────────
 *   draft()          full policy from controls + org profile     [pipeline]
 *   rewriteSection() selection rewrite in the editor             [one call]
 *   suggestMappings()which controls this policy satisfies        [pipeline]
 *   analyseGaps()    what a framework expects and is missing     [pipeline]
 *   explainClause()  plain-English gloss for a reviewer          [one call]
 *
 * ── NOTHING HERE WRITES TO AuditPolicy ───────────────────────────────────────
 * Every method returns a suggestion. The controller hands it to the UI, a human
 * accepts or edits it, and the existing AuditPolicyController does the write
 * through the existing DRAFT -> UNDER_REVIEW -> APPROVED lifecycle.
 *
 * That is not caution for its own sake. The approval chain is the audit
 * evidence: it proves a named human at a known time accepted the text. An AI
 * path that wrote directly would produce policies with no reviewer in the
 * record, which is precisely the artefact an auditor will reject — and the
 * lifecycle you already built is what makes doing this right almost free.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PolicyAiService {

    private final AiChatService            chatService;
    private final PolicyContextAssembler   contextAssembler;
    private final PolicyHtmlRenderer       htmlRenderer;
    private final ReferenceIntegrityGuard  referenceGuard;
    private final AuditPolicyRepository    policyRepository;
    private final PolicyTemplatePlaceholders placeholders;
    private final AiProperties             props;
    private final ObjectMapper             mapper;

    // ══════════════════════════════════════════════════════════════════════════
    // METADATA — the CREATE path, before any policy row exists
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Turn one sentence of intent into the fields needed to create a policy.
     *
     * ── WHY A SEPARATE STEP RATHER THAN PART OF draft() ──────────────────────
     * Because the user must be able to correct it. Title, framework refs and
     * control mappings are the grounding for everything the draft then produces;
     * getting them wrong silently produces a fluent policy about the wrong
     * subject. Splitting the step puts a human between the guess and the
     * consequence, at the one moment when correcting it is cheap.
     *
     * ── CONTROL CODES ARE VALIDATED, AS EVERYWHERE ELSE ─────────────────────
     * The model chooses from the enumerated catalogue and anything outside it is
     * dropped. A fabricated control code here would be worse than in a draft,
     * because it becomes the policy's stored control_tags and flows into
     * coverage reporting without anyone reading it again.
     *
     * Single call, cheap model. This runs while the user is waiting on a form.
     */
    @Transactional(readOnly = true)
    public MetadataResponse suggestMetadata(MetadataRequest req, Long tenantId, Long userId) {

        List<CommonControl> candidates = contextAssembler.candidateControlsForTemplate(
                req.getTemplateId(), req.getFrameworks(), 200);
        Set<String> allowed = new LinkedHashSet<>();
        candidates.forEach(c -> allowed.add(c.getCode()));

        AiChatService.AiResult r = chatService.completeJson(
                AiCall.of("policy.metadata", TaskType.POLICY_METADATA_EXTRACT)
                        .tenant(tenantId).user(userId)
                        .entity("AuditPolicy", null)
                        .var("intent", req.getIntent())
                        .var("frameworks", req.getFrameworks() == null || req.getFrameworks().isEmpty()
                                ? "not specified" : String.join(", ", req.getFrameworks()))
                        .var("availableControls", contextAssembler.buildCandidateBlock(candidates)));

        JsonNode json = r.json();
        MetadataResponse out = new MetadataResponse();
        out.setTitle(json.path("title").asText(null));
        out.setDescription(json.path("description").asText(null));
        out.setOwnerTeam(json.path("ownerTeam").asText(null));
        out.setRationale(json.path("rationale").asText(null));
        out.setReviewFrequencyMonths(json.path("reviewFrequencyMonths").asInt(12));
        out.setInteractionId(r.interactionId());

        List<String> fw = new ArrayList<>();
        json.path("frameworkRefs").forEach(n -> fw.add(n.asText()));
        out.setFrameworkRefs(fw);

        // Drop rather than reject: a good title and description are still useful
        // even if one control code was invented, and the user is about to review
        // the list anyway. suggestMappings() uses strict() because there the
        // codes ARE the payload.
        List<String> cited = referenceGuard.extractField(json.path("suggestedControls"), "controlCode");
        var integrity = referenceGuard.filter(cited, allowed);

        List<MappingSuggestion> mappings = new ArrayList<>();
        for (JsonNode m : json.path("suggestedControls")) {
            String code = m.path("controlCode").asText(null);
            if (code == null || !integrity.valid().contains(code)) continue;
            MappingSuggestion ms = new MappingSuggestion();
            ms.setControlCode(code);
            ms.setControlTitle(candidates.stream().filter(c -> c.getCode().equals(code))
                    .map(CommonControl::getTitle).findFirst().orElse(null));
            ms.setRationale(m.path("rationale").asText(null));
            ms.setConfidence(m.path("confidence").asDouble(0.5));
            mappings.add(ms);
        }
        out.setSuggestedControls(mappings);

        List<String> warnings = new ArrayList<>();
        if (!integrity.clean()) {
            warnings.add("Removed " + integrity.fabricated().size()
                    + " control reference(s) that do not exist in your library");
        }
        if (candidates.isEmpty()) {
            warnings.add("No controls found for the selected frameworks — mappings could not be suggested");
        }
        out.setWarnings(warnings);
        return out;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DRAFT — the multi-step pipeline
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Steps: assemble -> generate -> critique/revise -> validate references -> render.
     *
     * Every step is logged as its own ai_interactions row under one correlation
     * id, so the finished policy can be traced back through each decision that
     * produced it.
     */
    @Transactional(readOnly = true)
    public DraftResponse draft(DraftRequest req, Long tenantId, Long userId) {

        AiPipelineContext ctx = new AiPipelineContext();
        ctx.setTenantId(tenantId);
        ctx.setUserId(userId);
        ctx.setEntityType("AuditPolicy");

        AiPipeline pipeline = new AiPipeline("policy-draft", props);

        // ── 1: gather grounding (no model call) ───────────────────────────────
        pipeline.add(new AiStep() {
            @Override public String name() { return "assemble-context"; }
            @Override public void execute(AiPipelineContext c) {
                /*
                 * EDIT PATH. When policyId is present the policy already exists
                 * and its own metadata is more authoritative than anything the
                 * request carries — the title was chosen and saved, the
                 * frameworks were set, the controls were linked. Falling back to
                 * the request only fills what the policy does not have.
                 */
                String  title      = req.getTitle();
                List<String> fws   = req.getFrameworks();
                List<String> codes = req.getControlCodes();

                if (req.getPolicyId() != null) {
                    AuditPolicy existing = loadOwned(req.getPolicyId(), tenantId);
                    c.setEntityId(existing.getId());
                    if (existing.getTitle() != null && !existing.getTitle().isBlank()) {
                        title = existing.getTitle();
                    }
                    if ((fws == null || fws.isEmpty()) && existing.getFrameworkRefs() != null) {
                        fws = splitList(existing.getFrameworkRefs());
                    }
                    if (codes == null || codes.isEmpty()) {
                        /*
                         * controlTags, not commonControlCodes. The column
                         * common_control_codes exists on audit_policies but is
                         * not mapped on the AuditPolicy entity, so reading it
                         * here does not compile.
                         *
                         * No loss: the tag_suggestions on the create form's
                         * controlTags field are UCF leaf codes (APP-01.4,
                         * AST-01.1, ...), so controlTags IS the control-code
                         * field in practice. If the entity later maps
                         * commonControlCodes, prefer it here and fall back to
                         * this.
                         */
                        codes = splitList(existing.getControlTags());
                    }
                }

                PolicyContextAssembler.PolicyContext pc = contextAssembler.assemble(
                        tenantId, title, codes, fws, req.getAdditionalInstructions());
                c.put("policyContext", pc);
                c.put("resolvedTitle", title);
                c.addChunks(pc.retrievedChunkIds());
                if (!pc.grounded()) {
                    c.addWarning("No reference material was available — the draft is based on the "
                            + "organisation profile and selected controls only");
                }
            }
        });

        // ── 2: generate the structured draft ──────────────────────────────────
        pipeline.add(new AiStep() {
            @Override public String name() { return "generate-draft"; }
            @Override public void execute(AiPipelineContext c) {
                PolicyContextAssembler.PolicyContext pc =
                        (PolicyContextAssembler.PolicyContext) c.get("policyContext");

                AiChatService.AiResult r = chatService.completeJson(
                        AiCall.of("policy.draft", TaskType.POLICY_DRAFT)
                                .tenant(tenantId).user(userId)
                                .correlation(c.getCorrelationId())
                                .pipeline("policy-draft").step(1, name())
                                .entity("AuditPolicy", null)
                                .chunks(pc.retrievedChunkIds())
                                .context(pc.retrievedBlock())
                                .var("title", c.has("resolvedTitle")
                                        ? String.valueOf(c.get("resolvedTitle")) : req.getTitle())
                                .var("orgContext", pc.orgBlock())
                                .var("controlRequirements", pc.controlBlock())
                                .var("frameworks", req.getFrameworks() == null ? "" : String.join(", ", req.getFrameworks()))
                                .var("additionalInstructions",
                                        req.getAdditionalInstructions() == null ? "" : req.getAdditionalInstructions()));

                c.addInteraction(r.interactionId());
                c.addTokens(r.totalTokens());
                c.put("draftJson", r.json());
                c.put("model", r.model());
            }
        });

        // ── 3: self-critique against concrete criteria ────────────────────────
        // Skipped in quick mode: the user asked for speed and made that trade.
        if (!req.isQuickMode()) {
            pipeline.add(new SelfCritiqueStep(
                    chatService, "policy.critique", "policy.revise", "draftText",
                    List.of(
                            "Every control listed in the requirements is addressed somewhere in the draft",
                            "No control code, clause or standard reference appears that was not in the supplied list",
                            "No factual claim is made about the organisation that the profile does not support",
                            "No unfilled template placeholder such as [Company Name] or {{x}} remains",
                            "Each section states who is responsible and what they must do, not merely that something is important",
                            "Wording is consistent with the requested tone and spelling variant"
                    )) {
                /*
                 * The critique step operates on text; the draft is JSON. Flatten
                 * before and merge back after so the step stays generic and
                 * reusable for the vendor and risk surfaces later.
                 */
                @Override public void execute(AiPipelineContext c) {
                    JsonNode draft = (JsonNode) c.get("draftJson");
                    if (draft == null) return;
                    c.put("draftText", flatten(draft));
                    super.execute(c);
                }
            });
        }

        // ── 4: reject fabricated references ───────────────────────────────────
        pipeline.add(new AiStep() {
            @Override public String name() { return "validate-references"; }
            @Override public void execute(AiPipelineContext c) {
                PolicyContextAssembler.PolicyContext pc =
                        (PolicyContextAssembler.PolicyContext) c.get("policyContext");
                JsonNode draft = (JsonNode) c.get("draftJson");
                if (draft == null || pc.allowedControlCodes().isEmpty()) return;

                List<String> cited = referenceGuard.extractField(draft.path("suggestedControls"), "controlCode");
                var result = referenceGuard.filter(cited, pc.allowedControlCodes());

                if (!result.clean()) {
                    c.addWarning("Removed " + result.fabricated().size()
                            + " control reference(s) that do not exist in your library: "
                            + String.join(", ", result.fabricated()));
                }
                c.put("validControlCodes", new LinkedHashSet<>(result.valid()));
            }
        });

        pipeline.run(ctx);
        return buildResponse(ctx, req);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // REWRITE — the editor's most-used action
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Single call, no pipeline. Latency is the feature here: the user has text
     * selected and is waiting. Critique would double the wait for a marginal
     * gain on a change they are about to read in full anyway.
     */
    @Transactional(readOnly = true)
    public RewriteResponse rewriteSection(RewriteRequest req, Long tenantId, Long userId) {
        AuditPolicy policy = loadOwned(req.getPolicyId(), tenantId);

        AiChatService.AiResult r = chatService.complete(
                AiCall.of("policy.rewrite", TaskType.POLICY_SECTION_REWRITE)
                        .tenant(tenantId).user(userId)
                        .entity("AuditPolicy", policy.getId())
                        .var("selectedText", req.getSelectedText())
                        .var("mode", req.getMode())
                        .var("customInstruction", req.getCustomInstruction() == null ? "" : req.getCustomInstruction())
                        .var("surroundingContext", req.getSurroundingContext() == null ? "" : req.getSurroundingContext())
                        .var("policyTitle", policy.getTitle()));

        RewriteResponse out = new RewriteResponse();
        out.setOriginalText(req.getSelectedText());
        out.setRewrittenText(r.content() == null ? null : r.content().trim());
        out.setInteractionId(r.interactionId());
        return out;
    }

    /** Streaming variant. Same call, tokens pushed to the browser as they arrive. */
    @Transactional(readOnly = true)
    public void rewriteSectionStreaming(RewriteRequest req, Long tenantId, Long userId, Consumer<String> onToken) {
        AuditPolicy policy = loadOwned(req.getPolicyId(), tenantId);
        chatService.stream(
                AiCall.of("policy.rewrite", TaskType.POLICY_SECTION_REWRITE)
                        .tenant(tenantId).user(userId)
                        .entity("AuditPolicy", policy.getId())
                        .var("selectedText", req.getSelectedText())
                        .var("mode", req.getMode())
                        .var("customInstruction", req.getCustomInstruction() == null ? "" : req.getCustomInstruction())
                        .var("surroundingContext", req.getSurroundingContext() == null ? "" : req.getSurroundingContext())
                        .var("policyTitle", policy.getTitle()),
                onToken);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CONTROL MAPPING — the highest-risk action in the module
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Suggest which controls this policy satisfies.
     *
     * ── WHY strict() AND NOT filter() HERE ───────────────────────────────────
     * The references ARE the payload. If the model returns a code that does not
     * exist, there is no partially-correct answer worth salvaging — the whole
     * response is discarded and the user is told, rather than shown four real
     * mappings and one fabricated one they have no way to distinguish.
     *
     * These suggestions are still never written automatically. A mapping is a
     * compliance assertion; a human accepts it, and their acceptance is recorded
     * in ai_suggestion_feedback.
     */
    @Transactional(readOnly = true)
    public MappingResponse suggestMappings(MappingRequest req, Long tenantId, Long userId) {
        AuditPolicy policy = loadOwned(req.getPolicyId(), tenantId);

        String policyText = extractPolicyText(policy);
        if (policyText.isBlank()) {
            throw new com.kashi.grc.common.exception.BusinessException(
                    "POLICY_NO_CONTENT",
                    "This policy has no text to analyse yet",
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
        }

        int max = req.getMaxSuggestions() == null ? 10 : Math.min(req.getMaxSuggestions(), 25);
        /*
         * Template scope first. An engagement running KashiGRC ISO 27001:2022
         * should be offered the 55 controls on that audit plan, not the 116
         * reachable by framework tag — suggesting an out-of-scope control either
         * pollutes coverage reporting or teaches the reviewer to distrust the panel.
         */
        List<CommonControl> candidates = contextAssembler.candidateControlsForTemplate(
                req.getTemplateId(), req.getFrameworks(), 200);
        Set<String> allowed = new LinkedHashSet<>();
        candidates.forEach(c -> allowed.add(c.getCode()));

        AiChatService.AiResult r = chatService.completeJson(
                AiCall.of("policy.mapping", TaskType.POLICY_CONTROL_MAPPING)
                        .tenant(tenantId).user(userId)
                        .entity("AuditPolicy", policy.getId())
                        .var("policyTitle", policy.getTitle())
                        .var("policyText", truncate(policyText, 30000))
                        .var("availableControls", contextAssembler.buildCandidateBlock(candidates))
                        .var("maxSuggestions", max));

        List<String> cited = referenceGuard.extractField(r.json().path("mappings"), "controlCode");
        referenceGuard.strict(cited, allowed, "control codes");   // any fabrication rejects the response

        List<MappingSuggestion> suggestions = new ArrayList<>();
        for (JsonNode m : r.json().path("mappings")) {
            MappingSuggestion s = new MappingSuggestion();
            s.setControlCode(m.path("controlCode").asText());
            s.setControlTitle(candidates.stream()
                    .filter(c -> c.getCode().equals(s.getControlCode()))
                    .map(CommonControl::getTitle).findFirst().orElse(null));
            s.setRationale(m.path("rationale").asText(null));
            s.setConfidence(m.path("confidence").asDouble(0.5));
            s.setEvidenceSection(m.path("evidenceSection").asText(null));
            suggestions.add(s);
        }

        MappingResponse out = new MappingResponse();
        out.setSuggestions(suggestions);
        out.setInteractionId(r.interactionId());
        out.setCandidatesConsidered(candidates.size());
        out.setWarnings(List.of());
        return out;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // GAP ANALYSIS
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public GapResponse analyseGaps(GapRequest req, Long tenantId, Long userId) {
        AuditPolicy policy = loadOwned(req.getPolicyId(), tenantId);

        PolicyContextAssembler.PolicyContext pc = contextAssembler.assemble(
                tenantId, policy.getTitle(), req.getControlCodes(), List.of(req.getFramework()), null);

        AiChatService.AiResult r = chatService.completeJson(
                AiCall.of("policy.gap", TaskType.POLICY_GAP_ANALYSIS)
                        .tenant(tenantId).user(userId)
                        .entity("AuditPolicy", policy.getId())
                        .chunks(pc.retrievedChunkIds())
                        .context(pc.retrievedBlock())
                        .var("policyTitle", policy.getTitle())
                        .var("policyText", truncate(extractPolicyText(policy), 30000))
                        .var("framework", req.getFramework())
                        .var("controlRequirements", pc.controlBlock()));

        List<Gap> gaps = new ArrayList<>();
        for (JsonNode g : r.json().path("gaps")) {
            Gap gap = new Gap();
            gap.setControlCode(g.path("controlCode").asText(null));
            gap.setControlTitle(pc.controlTitles().get(gap.getControlCode()));
            gap.setSeverity(g.path("severity").asText("PARTIAL"));
            gap.setWhatIsExpected(g.path("whatIsExpected").asText(null));
            gap.setWhatIsMissing(g.path("whatIsMissing").asText(null));
            gap.setSuggestedText(g.path("suggestedText").asText(null));
            gaps.add(gap);
        }

        GapResponse out = new GapResponse();
        out.setGaps(gaps);
        out.setCoverageScore(r.json().path("coverageScore").asDouble(0));
        out.setSummary(r.json().path("summary").asText(null));
        out.setInteractionId(r.interactionId());
        out.setCitations(List.of());
        return out;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // EXPLAIN
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public ExplainResponse explainClause(ExplainRequest req, Long tenantId, Long userId) {
        AuditPolicy policy = loadOwned(req.getPolicyId(), tenantId);

        AiChatService.AiResult r = chatService.completeJson(
                AiCall.of("policy.explain", TaskType.POLICY_CLAUSE_EXPLAIN)
                        .tenant(tenantId).user(userId)
                        .entity("AuditPolicy", policy.getId())
                        .var("clause", req.getClause())
                        .var("policyTitle", policy.getTitle())
                        .var("audience", req.getAudience() == null ? "REVIEWER" : req.getAudience())
                        .cacheable(true));   // deterministic and frequently repeated

        ExplainResponse out = new ExplainResponse();
        out.setExplanation(r.json().path("explanation").asText(null));
        List<String> implications = new ArrayList<>();
        r.json().path("implications").forEach(n -> implications.add(n.asText()));
        out.setImplications(implications);
        out.setInteractionId(r.interactionId());
        return out;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    private DraftResponse buildResponse(AiPipelineContext ctx, DraftRequest req) {
        JsonNode json = (JsonNode) ctx.get("draftJson");
        DraftResponse out = new DraftResponse();

        if (json != null) {
            out.setTitle(json.path("title").asText(req.getTitle()));
            out.setPurpose(json.path("purpose").asText(null));
            out.setScope(json.path("scope").asText(null));
            out.setSuggestedReviewMonths(json.path("reviewFrequencyMonths").asInt(12));

            List<Section> sections = new ArrayList<>();
            for (JsonNode s : json.path("sections")) {
                Section sec = new Section();
                sec.setHeading(s.path("heading").asText(null));
                sec.setBody(s.path("body").asText(null));
                List<String> addressed = new ArrayList<>();
                s.path("addressesControls").forEach(n -> addressed.add(n.asText()));
                sec.setAddressesControls(addressed);
                sections.add(sec);
            }
            out.setSections(sections);

            List<Definition> defs = new ArrayList<>();
            for (JsonNode d : json.path("definitions")) {
                Definition def = new Definition();
                def.setTerm(d.path("term").asText(null));
                def.setMeaning(d.path("meaning").asText(null));
                defs.add(def);
            }
            out.setDefinitions(defs);

            List<RoleResponsibility> roles = new ArrayList<>();
            for (JsonNode rr : json.path("roles")) {
                RoleResponsibility role = new RoleResponsibility();
                role.setRole(rr.path("role").asText(null));
                role.setResponsibility(rr.path("responsibility").asText(null));
                roles.add(role);
            }
            out.setRoles(roles);

            @SuppressWarnings("unchecked")
            Set<String> valid = (Set<String>) ctx.get("validControlCodes");
            List<MappingSuggestion> mappings = new ArrayList<>();
            for (JsonNode m : json.path("suggestedControls")) {
                String code = m.path("controlCode").asText(null);
                if (valid != null && !valid.contains(code)) continue;   // fabricated: already reported as a warning
                MappingSuggestion ms = new MappingSuggestion();
                ms.setControlCode(code);
                ms.setRationale(m.path("rationale").asText(null));
                ms.setConfidence(m.path("confidence").asDouble(0.5));
                ms.setEvidenceSection(m.path("evidenceSection").asText(null));
                mappings.add(ms);
            }
            out.setSuggestedControls(mappings);
            out.setContentHtml(htmlRenderer.render(out));

            /*
             * Placeholders that survived into the finished draft. The prompt
             * deliberately asks for [[MARKED GAPS]] rather than invented values,
             * so their presence is correct behaviour — but the reviewer has to
             * be told, or they will approve a policy containing [[RTO]] and
             * find out when an auditor reads it aloud.
             */
            List<String> unfilled = placeholders.findUnfilled(out.getContentHtml());
            if (!unfilled.isEmpty()) {
                ctx.addWarning(unfilled.size() + " detail(s) need your input: "
                        + String.join(", ", unfilled.stream().limit(8).toList())
                        + (unfilled.size() > 8 ? " and " + (unfilled.size() - 8) + " more" : ""));
            }
        }

        PolicyContextAssembler.PolicyContext pc =
                (PolicyContextAssembler.PolicyContext) ctx.get("policyContext");

        out.setInteractionId(ctx.getRootInteractionId());
        out.setCorrelationId(ctx.getCorrelationId());
        out.setWarnings(ctx.getWarnings());
        out.setModel((String) ctx.get("model"));
        out.setTokensUsed((int) ctx.getTokensSpent());
        out.setGroundedInRetrieval(pc != null && pc.grounded());
        out.setCitations(List.of());
        return out;
    }

    /**
     * Ownership check. Mirrors the pattern AuditPolicyController uses: a global
     * policy is readable by everyone, a tenant policy only by its owner.
     * Loading by id alone here would reintroduce exactly the cross-tenant hole
     * that controller's comments describe fixing.
     */
    private AuditPolicy loadOwned(Long id, Long tenantId) {
        AuditPolicy p = policyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AuditPolicy", id));
        if (p.getTenantId() != null && !p.getTenantId().equals(tenantId)) {
            throw new com.kashi.grc.common.exception.ForbiddenException(
                    "This policy belongs to another organisation");
        }
        return p;
    }

    private String extractPolicyText(AuditPolicy p) {
        if (p.getContentBody() == null) return "";
        return p.getContentBody().replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }

    /** Flatten a structured draft to text so the generic critique step can read it. */
    private static String flatten(JsonNode draft) {
        StringBuilder sb = new StringBuilder();
        sb.append(draft.path("title").asText("")).append("\n\n");
        sb.append("PURPOSE\n").append(draft.path("purpose").asText("")).append("\n\n");
        sb.append("SCOPE\n").append(draft.path("scope").asText("")).append("\n\n");
        for (JsonNode s : draft.path("sections")) {
            sb.append(s.path("heading").asText("")).append('\n')
                    .append(s.path("body").asText("")).append("\n\n");
        }
        return sb.toString();
    }

    /** Splits the comma- or space-delimited list columns the entity stores. */
    private static List<String> splitList(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return java.util.Arrays.stream(csv.split("[,\\s]+"))
                .map(String::trim).filter(v -> !v.isEmpty()).toList();
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "\n[...truncated]";
    }
}