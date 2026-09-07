package com.kashi.grc.ai.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashi.grc.ai.domain.AiEnums.ChunkSourceType;
import com.kashi.grc.ai.domain.AiOrgProfile;
import com.kashi.grc.ai.rag.RetrievalService;
import com.kashi.grc.ai.repository.AiOrgProfileRepository;
import com.kashi.grc.ucf.domain.CommonControl;
import com.kashi.grc.audit.domain.AuditControl;
import com.kashi.grc.audit.domain.AuditSection;
import com.kashi.grc.audit.domain.AuditSectionControlMapping;
import com.kashi.grc.audit.domain.AuditTemplateSectionMapping;
import com.kashi.grc.ucf.domain.CommonControlMapping;
import com.kashi.grc.ucf.repository.CommonControlRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the grounding for a policy generation. THE QUALITY LAYER.
 *
 * ── WHY THIS MATTERS MORE THAN THE MODEL ─────────────────────────────────────
 * Swapping model A for model B moves output quality a little. Feeding the model
 * the company's actual name, cloud provider, jurisdictions and the exact text of
 * the controls it must satisfy moves it enormously. Everything expensive about
 * this feature is in this class, and it is almost all database work.
 *
 * ── STRUCTURED FIRST, RETRIEVED SECOND ───────────────────────────────────────
 * The controls come from CommonControl by CODE — an exact lookup, not a
 * similarity search. That is the point made when we discussed whether to lead
 * with RAG: for policy generation the grounding is mostly a JOIN you already
 * know how to write, and it is more precise, cheaper and more auditable than
 * embedding search could be.
 *
 * Retrieval is additive on top: house tone from the tenant's own approved
 * policies, prior art from the platform library. When the corpus is empty the
 * block is empty and generation still works — which is what makes this shippable
 * before the corpus exists.
 *
 * ── THE CANDIDATE SET IS A SECURITY CONTROL ──────────────────────────────────
 * allowedControlCodes() returns the enumerated set the model may reference.
 * ReferenceIntegrityGuard checks the output against it. Together they are what
 * stops the platform manufacturing a false compliance claim, which is the single
 * worst thing an AI feature in a GRC tool can do.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PolicyContextAssembler {

    private final AiOrgProfileRepository  orgProfileRepository;
    private final CommonControlRepository controlRepository;
    private final RetrievalService        retrievalService;
    private final PolicyTemplatePlaceholders placeholders;
    private final ObjectMapper            mapper;

    @PersistenceContext
    private EntityManager em;

    /** Everything one generation needs, plus the guard's allow-list. */
    public record PolicyContext(
            String orgBlock,
            String controlBlock,
            String retrievedBlock,
            List<Long> retrievedChunkIds,
            Set<String> allowedControlCodes,
            Map<String, String> controlTitles,
            AiOrgProfile profile,
            boolean grounded
    ) {}

    @Transactional(readOnly = true)
    public PolicyContext assemble(Long tenantId, String title, List<String> controlCodes,
                                  List<String> frameworks, String additionalInstructions) {

        AiOrgProfile profile = tenantId == null ? null
                : orgProfileRepository.findByTenantId(tenantId).orElse(null);

        // ── Controls: exact lookup by code ────────────────────────────────────
        List<CommonControl> controls = (controlCodes == null || controlCodes.isEmpty())
                ? List.of()
                : controlRepository.findAllById(resolveIdsByCode(controlCodes));

        // Fall back to a code-based query when ids are not directly resolvable.
        if (controls.isEmpty() && controlCodes != null && !controlCodes.isEmpty()) {
            controls = em.createQuery(
                            "select c from CommonControl c where c.code in :codes and c.active = true", CommonControl.class)
                    .setParameter("codes", controlCodes)
                    .getResultList();
        }

        Set<String> allowed = new LinkedHashSet<>();
        Map<String, String> titles = new LinkedHashMap<>();
        for (CommonControl c : controls) { allowed.add(c.getCode()); titles.put(c.getCode(), c.getTitle()); }

        if (controlCodes != null && allowed.size() < controlCodes.size()) {
            List<String> unknown = controlCodes.stream().filter(c -> !allowed.contains(c)).toList();
            log.warn("[AI-POLICY-CTX] {} requested control code(s) not found in the catalogue: {}",
                    unknown.size(), unknown);
        }

        // ── Retrieval: additive, never required ───────────────────────────────
        String retrievedBlock = "";
        List<Long> chunkIds = List.of();
        boolean grounded = false;

        try {
            String query = buildRetrievalQuery(title, controls, frameworks, additionalInstructions);
            RetrievalService.RetrievalResult r = retrievalService.retrieve(
                    query, tenantId,
                    List.of(ChunkSourceType.POLICY, ChunkSourceType.POLICY_TEMPLATE, ChunkSourceType.FRAMEWORK_TEXT),
                    null);
            if (!r.isEmpty()) {
                /*
                 * Hydrate the retrieved templates with this tenant's real facts
                 * before they reach the prompt. The model then sees
                 * "Northwind's Head of Security reviews..." as prior art rather
                 * than "{{company_name}}'s {{policy_owner}} reviews...", which
                 * is the difference between a useful example and one that
                 * teaches it to emit placeholders.
                 */
                retrievedBlock = placeholders.hydrate(r.contextBlock(), profile).content();
                // Draft path: no AuditPolicy row exists yet, so the profile-only
                // overload is correct here. PolicyAiService uses the delegating
                // overload for rewrite/gap/explain, where a policy does exist.
                chunkIds       = r.chunkIds();
                grounded       = true;
            }
        } catch (Exception e) {
            // Corpus empty or Qdrant down. Structured context alone is still good.
            log.debug("[AI-POLICY-CTX] retrieval unavailable, continuing ungrounded: {}", e.getMessage());
        }

        return new PolicyContext(
                buildOrgBlock(profile, frameworks),
                buildControlBlock(controls),
                retrievedBlock, chunkIds, allowed, titles, profile, grounded);
    }

    // ── Organisation grounding ────────────────────────────────────────────────

    /**
     * Turns the org profile into prose facts.
     *
     * Prose rather than JSON on purpose: models follow narrative context more
     * reliably than a key-value dump, and the negative constraints at the end
     * matter more than anything above them. A policy that claims the company
     * holds cardholder data when it does not is a false compliance statement in
     * the customer's own document set.
     */
    private String buildOrgBlock(AiOrgProfile p, List<String> frameworks) {
        if (p == null) {
            return """
                   ORGANISATION CONTEXT
                   No organisation profile has been completed. Write the policy generically,
                   using the placeholder [[ORGANISATION NAME]] wherever the company is named.
                   Do not invent company-specific facts, systems, locations or role holders.
                   """;
        }

        StringBuilder sb = new StringBuilder("ORGANISATION CONTEXT\n");
        line(sb, "Legal name", p.getLegalName());
        line(sb, "Referred to in body text as", p.getShortName() != null ? p.getShortName() : p.getLegalName());
        line(sb, "Industry", p.getIndustry());
        if (p.getEmployeeCount() != null) line(sb, "Approximate headcount", String.valueOf(p.getEmployeeCount()));
        line(sb, "Headquarters", p.getHeadquartersCountry());
        line(sb, "Operating countries", p.getOperatingCountries());
        line(sb, "Working model", p.getRemoteWorkModel());

        String fw = (frameworks != null && !frameworks.isEmpty())
                ? String.join(", ", frameworks) : p.getFrameworksInScope();
        line(sb, "Frameworks in scope", fw);
        line(sb, "Data residency requirements", p.getDataResidencyRegions());

        List<String> dataTypes = new ArrayList<>();
        if (Boolean.TRUE.equals(p.getProcessesPersonalData()))   dataTypes.add("personal data");
        if (Boolean.TRUE.equals(p.getProcessesHealthData()))     dataTypes.add("health data");
        if (Boolean.TRUE.equals(p.getProcessesCardholderData())) dataTypes.add("cardholder data");
        if (Boolean.TRUE.equals(p.getProcessesChildrenData()))   dataTypes.add("children's data");
        if (!dataTypes.isEmpty()) line(sb, "Processes", String.join(", ", dataTypes));

        sb.append("\nTECHNICAL ESTATE (reference these specifically instead of writing generically)\n");
        line(sb, "Cloud providers", p.getCloudProviders());
        line(sb, "Identity provider", p.getIdentityProvider());
        line(sb, "Device management", p.getMdmSolution());
        line(sb, "Endpoint protection", p.getEndpointProtection());
        line(sb, "Source control", p.getCodeRepository());
        line(sb, "Ticketing", p.getTicketingSystem());
        if (Boolean.TRUE.equals(p.getHasOnPremise())) line(sb, "Also operates", "on-premise infrastructure");

        sb.append("\nACCOUNTABLE ROLES (name these where the policy assigns responsibility)\n");
        line(sb, "Security owner", join(p.getSecurityOwnerName(), p.getSecurityOwnerTitle()));
        line(sb, "Privacy officer", p.getPrivacyOfficerName());
        line(sb, "Security contact", p.getSecurityContactEmail());
        line(sb, "Incident reporting", p.getIncidentContactEmail());

        sb.append("\nDRAFTING CONVENTIONS\n");
        line(sb, "Tone", p.getToneOfVoice());
        line(sb, "Spelling", p.getSpellingVariant());

        // Customer-authored facts. In practice the most-used field on the profile.
        if (p.getCustomFactsJson() != null && !p.getCustomFactsJson().isBlank()) {
            try {
                JsonNode facts = mapper.readTree(p.getCustomFactsJson());
                if (facts.isArray() && facts.size() > 0) {
                    sb.append("\nADDITIONAL ORGANISATION FACTS\n");
                    for (JsonNode f : facts) {
                        sb.append("- ").append(f.path("label").asText("")).append(": ")
                                .append(f.path("value").asText("")).append('\n');
                    }
                }
            } catch (Exception e) {
                log.debug("[AI-POLICY-CTX] custom facts JSON unparseable, skipped");
            }
        }

        // The negative constraints. Deliberately last, deliberately emphatic.
        if (p.getProhibitedClaims() != null && !p.getProhibitedClaims().isBlank()) {
            sb.append("\nSTATEMENTS THAT MUST NOT APPEAR\n")
                    .append("The following are factually untrue of this organisation. Never assert them:\n")
                    .append(p.getProhibitedClaims()).append('\n');
        }

        sb.append('\n').append(PolicyVariableCatalog.promptInstructions());

        sb.append("""

                  ACCURACY RULE
                  Use only the facts above. Where a required detail is absent, insert a clearly
                  marked placeholder such as [[RETENTION PERIOD]] rather than inventing a value.
                  A visible gap is correctable; a plausible invention is not.
                  """);
        return sb.toString();
    }

    // ── Control grounding ─────────────────────────────────────────────────────

    /**
     * The requirement text the policy must satisfy, plus the closed list of
     * codes the model may cite.
     */
    private String buildControlBlock(List<CommonControl> controls) {
        if (controls.isEmpty()) {
            return "CONTROL REQUIREMENTS\nNo specific controls were selected. Write to general good practice.\n";
        }

        StringBuilder sb = new StringBuilder("CONTROL REQUIREMENTS\n")
                .append("This policy must satisfy the following controls. Address every one.\n\n");

        for (CommonControl c : controls) {
            sb.append("[").append(c.getCode()).append("] ").append(c.getTitle()).append('\n');
            if (c.getDescription() != null && !c.getDescription().isBlank()) {
                sb.append("  Requirement: ").append(c.getDescription().trim()).append('\n');
            }
            if (c.getDomainCode() != null) sb.append("  Domain: ").append(c.getDomainCode()).append('\n');
            sb.append('\n');
        }

        sb.append("""
                  REFERENCE RULE — STRICT
                  You may cite ONLY the control codes listed above, exactly as written.
                  Do not cite any other control, clause or standard reference, even one you
                  believe exists. References outside this list are rejected automatically and
                  the response is discarded.
                  """);
        return sb.toString();
    }

    // ── Candidate set for mapping suggestions ─────────────────────────────────

    /**
     * The catalogue slice a mapping call may choose from, with an explicit cap.
     *
     * The cap is not arbitrary. Beyond roughly two hundred candidates, selection
     * quality falls off and prompt cost climbs; better to narrow by framework or
     * domain first. Presenting the whole catalogue and hoping is how mapping
     * suggestions become noise.
     */
    @Transactional(readOnly = true)
    public List<CommonControl> candidateControls(List<String> frameworks, int max) {

        /*
         * FRAMEWORK FILTERING VIA common_control_mappings.
         *
         * Your mapping table already carries (common_control_code, framework_ref,
         * citation), so "controls relevant to ISO 27001" is a join, not a guess.
         * This matters more than it looks: without it, a mapping call for an
         * ISO engagement is offered all 134 leaf controls including the 55 that
         * only exist for SOC 2, and selection quality falls with every
         * irrelevant candidate the model has to read past.
         *
         * NodeLevel.CONTROL only — DOMAIN and FAMILY nodes are navigational.
         * Offering "IAM — Identity & Access Management" as a mappable control
         * produces a suggestion that is true, useless, and impossible to
         * evidence.
         */
        if (frameworks != null && !frameworks.isEmpty()) {
            /*
             * TWO CROSSWALK SOURCES, AND BOTH ARE NEEDED.
             *
             * common_control_mappings is the curated crosswalk with typed
             * relationships. audit_controls.common_control_code is the mapping
             * carried on the library control rows themselves. They overlap but
             * neither is a superset:
             *
             *   ISO 27001  ccm 112 | audit_controls 55 | union 116
             *   SOC 2      ccm  79 | audit_controls 40 | union  83
             *
             * Reading only common_control_mappings drops GOV-01.6, GOV-02.1,
             * PRI-03.3 and PRI-03.5 from an ISO engagement's candidate set —
             * they are mapped, just not in that table. A control absent from
             * the candidate set can never be suggested, and ReferenceIntegrityGuard
             * would reject it as fabricated if the model named it anyway. So the
             * union is the correct scope, not a convenience.
             */
            List<CommonControl> scoped = em.createQuery("""
                    select c from CommonControl c
                    where c.active = true
                      and c.nodeLevel = :leaf
                      and (c.code in (
                              select m.commonControlCode from CommonControlMapping m
                              where m.frameworkRef in :frameworks and m.active = true)
                        or c.code in (
                              select a.commonControlCode from AuditControl a
                              where a.frameworkRef in :frameworks and a.commonControlCode is not null))
                    order by c.domainCode asc, c.code asc
                    """, CommonControl.class)
                    .setParameter("leaf", CommonControl.NodeLevel.CONTROL)
                    .setParameter("frameworks", frameworks)
                    .setMaxResults(max)
                    .getResultList();

            if (!scoped.isEmpty()) {
                log.debug("[AI-POLICY-CTX] {} candidate control(s) for frameworks {}", scoped.size(), frameworks);
                return scoped;
            }
            // An unmapped framework (a new one, or a tenant-private code) must not
            // silently yield zero candidates and an empty mapping panel.
            log.warn("[AI-POLICY-CTX] no mappings for {} — falling back to the full leaf catalogue", frameworks);
        }

        return em.createQuery("""
                select c from CommonControl c
                where c.active = true and c.nodeLevel = :leaf
                order by c.domainCode asc, c.code asc
                """, CommonControl.class)
                .setParameter("leaf", CommonControl.NodeLevel.CONTROL)
                .setMaxResults(max)
                .getResultList();
    }

    /**
     * Framework citations a control satisfies, e.g. IAM-03.2 -> "ISO27001 A.8.2".
     *
     * Shown beside each suggestion so a reviewer sees WHY a control was proposed
     * in framework terms they recognise. Only identifiers are ever surfaced —
     * never ISO or AICPA requirement text, which is copyrighted.
     */
    @Transactional(readOnly = true)
    public Map<String, String> citationsFor(List<String> controlCodes, List<String> frameworks) {
        Map<String, String> out = new LinkedHashMap<>();
        if (controlCodes == null || controlCodes.isEmpty()) return out;

        List<Object[]> rows = em.createQuery("""
                select m.commonControlCode, m.frameworkRef, m.citation
                from CommonControlMapping m
                where m.commonControlCode in :codes and m.active = true
                  and (:noFw = true or m.frameworkRef in :frameworks)
                """, Object[].class)
                .setParameter("codes", controlCodes)
                .setParameter("noFw", frameworks == null || frameworks.isEmpty())
                .setParameter("frameworks", frameworks == null || frameworks.isEmpty() ? List.of("") : frameworks)
                .getResultList();

        for (Object[] r : rows) {
            String cite = r[1] + " " + r[2];
            out.merge(String.valueOf(r[0]), cite, (a, b) -> a + ", " + b);
        }
        return out;
    }

    /*
     * ── WHY ORDERING IS domainCode, code AND NOT sortOrder ───────────────────
     *
     * sort_order is SIBLING-scoped, not global: it means "my position among my
     * parent's children". 134 leaf controls share only 24 distinct values —
     * GOV-01.1, HRS-01.1, AST-01.1, IAM-01.1 and thirteen others all carry
     * sort_order = 11. Ordering by it alone leaves 16-way ties broken by
     * whatever the storage engine happens to return, which is not stable across
     * executions.
     *
     * That is not cosmetic here. AiChatService derives inputHash from the
     * rendered prompt variables, and the candidate block is built from this
     * ordering. Unstable ordering means the same logical request produces a
     * different prompt each time, so the cache never hits and eval runs go noisy
     * for reasons that have nothing to do with the prompt under test.
     *
     * domainCode then code is deterministic, and groups all IAM controls
     * together in code order — which is also how a reviewer expects to read them.
     */

    /**
     * Candidates scoped to an audit template, via template -> section -> control.
     *
     * ── WHY THIS BEATS FILTERING BY frameworkRef ────────────────────────────
     * "ISO 27001" as a framework tag reaches 116 UCF leaf controls. The template
     * a customer is actually being audited against — KashiGRC ISO 27001:2022 —
     * reaches 55. Those 55 are the controls in scope for that engagement; the
     * other 61 are ISO-adjacent but not on the audit plan.
     *
     * Suggesting an out-of-scope control is not harmless. The reviewer either
     * accepts it, and the coverage report now claims evidence for something
     * nobody is testing, or rejects it, and learns the panel wastes their time.
     * Halving the candidate set also measurably improves selection quality —
     * every irrelevant option is one more thing the model reads past.
     *
     * ── THE PATH COLUMN DOES THE WORK ───────────────────────────────────────
     * AuditSection.path is a materialised ancestry string ("/651/652/653"), so
     * the whole subtree under a template's roots is one LIKE rather than a
     * recursive walk. Two round-trips total, which matters on Aiven.
     *
     * Falls back to the framework filter when a template has no control links —
     * a half-built template must not produce an empty mapping panel.
     */
    @Transactional(readOnly = true)
    public List<CommonControl> candidateControlsForTemplate(Long templateId, List<String> frameworks, int max) {
        if (templateId == null) return candidateControls(frameworks, max);

        List<String> rootPaths = em.createQuery("""
                select sec.path from AuditSection sec
                where sec.id in (
                    select m.sectionId from AuditTemplateSectionMapping m where m.templateId = :tpl)
                """, String.class)
                .setParameter("tpl", templateId)
                .getResultList();

        if (rootPaths.isEmpty()) {
            log.warn("[AI-POLICY-CTX] template {} has no sections — falling back to framework scope", templateId);
            return candidateControls(frameworks, max);
        }

        /*
         * Path values are inconsistent in the current data: some carry leading
         * and trailing slashes ("/599/600/"), others do not ("651/652"). Both
         * forms are matched rather than assuming one, because getting this wrong
         * returns an empty set that looks exactly like "this template has no
         * controls". See 09-section-path-normalise.sql for the data-side fix.
         */
        List<CommonControl> scoped = em.createQuery("""
                select distinct c from CommonControl c
                where c.active = true
                  and c.nodeLevel = :leaf
                  and c.code in (
                      select ctl.commonControlCode from AuditControl ctl
                      where ctl.commonControlCode is not null
                        and ctl.id in (
                            select scm.controlId from AuditSectionControlMapping scm
                            where scm.sectionId in (
                                select sec2.id from AuditSection sec2
                                where sec2.path in :roots
                                   or exists (select 1 from AuditSection r
                                              where r.path in :roots
                                                and sec2.path like concat(r.path, '%')))))
                order by c.domainCode asc, c.code asc
                """, CommonControl.class)
                .setParameter("leaf", CommonControl.NodeLevel.CONTROL)
                .setParameter("roots", rootPaths)
                .setMaxResults(max)
                .getResultList();

        if (scoped.isEmpty()) {
            log.warn("[AI-POLICY-CTX] template {} resolved to no controls — falling back to framework scope", templateId);
            return candidateControls(frameworks, max);
        }

        log.debug("[AI-POLICY-CTX] {} candidate control(s) scoped to template {}", scoped.size(), templateId);
        return scoped;
    }

    /** Render candidates compactly. Verbose descriptions here blow the prompt budget for no gain. */
    public String buildCandidateBlock(List<CommonControl> candidates) {
        StringBuilder sb = new StringBuilder("AVAILABLE CONTROLS — choose only from this list\n\n");
        Map<String, String> citations = citationsFor(
                candidates.stream().map(CommonControl::getCode).toList(), null);

        for (CommonControl c : candidates) {
            sb.append(c.getCode()).append(" | ").append(c.getTitle());
            String cite = citations.get(c.getCode());
            if (cite != null) sb.append(" | maps to: ").append(cite);
            if (c.getDescription() != null && !c.getDescription().isBlank()) {
                String d = c.getDescription().trim();
                sb.append(" | ").append(d.length() > 200 ? d.substring(0, 200) + "..." : d);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildRetrievalQuery(String title, List<CommonControl> controls,
                                       List<String> frameworks, String extra) {
        StringBuilder q = new StringBuilder(title == null ? "" : title);
        for (CommonControl c : controls) q.append(' ').append(c.getTitle());
        if (frameworks != null) frameworks.forEach(f -> q.append(' ').append(f));
        if (extra != null && !extra.isBlank()) q.append(' ').append(extra);
        return q.toString().trim();
    }

    private List<Long> resolveIdsByCode(List<String> codes) {
        try {
            return em.createQuery("select c.id from CommonControl c where c.code in :codes", Long.class)
                    .setParameter("codes", codes).getResultList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private void line(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) sb.append("- ").append(label).append(": ").append(value).append('\n');
    }

    private String join(String a, String b) {
        if (a == null || a.isBlank()) return b;
        if (b == null || b.isBlank()) return a;
        return a + " (" + b + ")";
    }
}