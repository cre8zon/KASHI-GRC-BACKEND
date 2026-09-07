package com.kashi.grc.ai.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashi.grc.ai.domain.AiOrgProfile;
import com.kashi.grc.audit.domain.AuditPolicy;
import com.kashi.grc.audit.service.PolicyVariableResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Supplements PolicyVariableResolver for AI-specific placeholder handling.
 *
 * ── READ THIS FIRST: WHO OWNS WHAT ───────────────────────────────────────────
 * Your codebase now has PolicyVariableResolver, which resolves {{placeholders}}
 * at READ time from Tenant, User and the policy's own workflow fields. That is
 * the AUTHORITATIVE resolver and this class does not compete with it.
 *
 *   PolicyVariableResolver owns   company_name, organisation_name,
 *                                 policy_owner, approver_name, approval_date,
 *                                 effective_date, next_review_date,
 *                                 review_cycle, policy_ref, policy_title,
 *                                 policy_version
 *
 *   This class owns               the technical-estate and contact variables
 *                                 that live on AiOrgProfile and nowhere else:
 *                                 cloud_provider, identity_provider, mdm, edr,
 *                                 code_repository, ticketing_system,
 *                                 key_management_system, data_residency,
 *                                 security_contact, incident_contact,
 *                                 privacy_officer, plus anything in
 *                                 customFactsJson
 *
 * hydrate() DELEGATES to PolicyVariableResolver first, then fills what remains.
 * That ordering matters: the workflow knows who actually approved a policy;
 * AiOrgProfile only knows who is nominally responsible. Real workflow data wins.
 *
 * ── THE PROBLEM THIS SOLVES ──────────────────────────────────────────────────
 * 25 of your 26 real policy templates carry mustache placeholders:
 * {{company_name}} appears 72 times, {{policy_owner}} 75 times, plus 25 more
 * specific ones ({{deprovision_sla}}, {{rto}}, {{min_tls_version}}, ...).
 *
 * Those templates are global (tenant_id NULL) and APPROVED, so PolicyCorpusHook
 * indexes them as POLICY_TEMPLATE and they become the primary retrieval corpus
 * for policy drafting. Which is exactly what you want — except that the model
 * then reads {{company_name}} in its reference material and faithfully copies
 * the convention into new drafts. The customer receives a "generated" policy
 * containing {{company_name}}, which reads as broken rather than as unfinished.
 *
 * ── TWO USES, DIFFERENT DIRECTIONS ───────────────────────────────────────────
 *
 *   normaliseForCorpus()  — at ingestion. Rewrites {{x}} to [[X]], matching the
 *                           placeholder convention the draft prompt already
 *                           instructs the model to use for unknown facts. The
 *                           retrieved material then REINFORCES the instruction
 *                           instead of contradicting it.
 *
 *   hydrate()             — at generation or at /customise. Substitutes real
 *                           values from the tenant's AiOrgProfile. This is the
 *                           more valuable direction: it turns your 26 templates
 *                           from static documents into a library that
 *                           instantiates per customer, which is a large part of
 *                           what Vanta's policy templates actually do.
 *
 * ── WHY UNKNOWN VALUES BECOME [[VISIBLE]] AND NOT BLANK ──────────────────────
 * A policy that says "revoked within  of termination" is worse than one saying
 * "revoked within [[DEPROVISION SLA]]". The first looks finished and is wrong;
 * the second tells the reviewer exactly what to supply. Same reasoning as
 * PromptRenderer's strict mode.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PolicyTemplatePlaceholders {

    private final PolicyVariableResolver variableResolver;
    private final ObjectMapper mapper;

    private static final Pattern MUSTACHE = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_.]+)\\s*}}");
    private static final Pattern SQUARE   = Pattern.compile("\\[\\[\\s*([^\\]]+?)\\s*]]");

    /**
     * Placeholders that map onto AiOrgProfile columns. Everything not listed
     * here is a policy parameter (an SLA, a cadence, a tool name) that belongs
     * on the tenant's own configuration, not on the shared profile — those stay
     * visible as [[...]] for a human to fill.
     */
    private static String resolve(String key, AiOrgProfile p) {
        if (p == null) return null;

        /*
         * WORKFLOW variables are never resolved here, even if AiOrgProfile
         * happens to hold something that looks right. policy_owner from the org
         * profile is who is nominally responsible; policy_owner from the
         * workflow is who actually signed. In an approved compliance document
         * those must not be allowed to differ, and the workflow is the one with
         * an audit trail behind it.
         */
        PolicyVariableCatalog.Variable def = PolicyVariableCatalog.get(key);
        if (def != null && def.isWorkflow()) return null;

        return switch (key.toLowerCase()) {
            /*
             * company_name / organisation_name / policy_owner are NOT handled
             * here. PolicyVariableResolver fills them from Tenant and the
             * policy's workflow fields, which are more current than anything
             * AiOrgProfile holds. They appear below only as a fallback for the
             * DRAFT-generation path, where no AuditPolicy row exists yet and
             * so the authoritative resolver has nothing to work from.
             */
            case "legal_name"          -> p.getLegalName();
            case "security_owner" ->
                    joinRole(p.getSecurityOwnerName(), p.getSecurityOwnerTitle());
            case "security_team"       -> p.getSecurityOwnerTitle();
            case "privacy_officer", "dpo" -> p.getPrivacyOfficerName();
            case "security_contact", "security_email" -> p.getSecurityContactEmail();
            case "incident_reporting_channel", "incident_contact" -> p.getIncidentContactEmail();
            case "identity_provider", "access_request_system" -> p.getIdentityProvider();
            case "mdm", "mdm_solution" -> p.getMdmSolution();
            case "edr", "endpoint_protection" -> p.getEndpointProtection();
            case "industry"            -> p.getIndustry();
            case "employee_count"      -> p.getEmployeeCount() == null ? null : String.valueOf(p.getEmployeeCount());
            case "headquarters", "headquarters_country" -> p.getHeadquartersCountry();
            case "operating_countries" -> p.getOperatingCountries();
            case "frameworks_in_scope" -> p.getFrameworksInScope();
            case "remote_work_model"   -> p.getRemoteWorkModel();
            case "code_repository"     -> p.getCodeRepository();
            case "ticketing_system"    -> p.getTicketingSystem();
            case "cloud_provider", "cloud_providers" -> p.getCloudProviders();
            case "key_management_system" ->
                // Derived rather than stored: if they are on one cloud, the
                // managed KMS is the overwhelmingly likely answer, and a
                // reviewer correcting "AWS KMS" is a smaller ask than a
                // reviewer inventing the whole sentence.
                    kmsFor(p.getCloudProviders());
            case "data_residency", "data_residency_regions" -> p.getDataResidencyRegions();
            case "review_cadence", "policy_review_cadence" ->
                    p.getDefaultReviewFrequencyMonths() == null ? null
                            : p.getDefaultReviewFrequencyMonths() + " months";
            default -> null;
        };
    }

    // ── Ingestion direction ───────────────────────────────────────────────────

    /**
     * {{deprovision_sla}} -> [[DEPROVISION SLA]].
     *
     * Applied by PolicyCorpusHook before a template reaches the vector index,
     * so no retrieved passage ever teaches the model to emit mustache syntax.
     */
    public String normaliseForCorpus(String html) {
        if (html == null || html.isBlank()) return html;
        Matcher m = MUSTACHE.matcher(html);
        StringBuilder sb = new StringBuilder();
        int count = 0;
        while (m.find()) {
            String key = m.group(1);
            /*
             * WORKFLOW placeholders stay as mustache in the corpus. They are the
             * convention the AI is being taught to reproduce — a retrieved
             * example showing "approved by {{approver_name}}" is teaching the
             * right thing. Rewriting them to [[APPROVER NAME]] would teach the
             * model to emit a manual placeholder for a value the workflow fills
             * automatically, which is precisely the mistake this whole split
             * exists to prevent.
             */
            PolicyVariableCatalog.Variable def = PolicyVariableCatalog.get(key);
            if (def != null && def.isWorkflow()) continue;

            m.appendReplacement(sb, Matcher.quoteReplacement(
                    "[[" + key.replace('_', ' ').toUpperCase() + "]]"));
            count++;
        }
        m.appendTail(sb);
        if (count > 0) log.debug("[AI-TEMPLATE] normalised {} mustache placeholder(s) for the corpus", count);
        return sb.toString();
    }

    // ── Generation direction ──────────────────────────────────────────────────

    public record Hydration(String content, List<String> filled, List<String> unresolved) {
        public boolean fullyResolved() { return unresolved.isEmpty(); }
    }

    /**
     * Full hydration for a persisted policy: authoritative resolver first,
     * AiOrgProfile for the remainder.
     *
     * Use this wherever an AuditPolicy row exists. The resolver leaves unknown
     * placeholders as "[approver — pending]", which this pass does not touch —
     * a genuinely pending approver must keep reading as pending, not get
     * silently replaced by a name from the org profile.
     */
    public Hydration hydrate(String content, AiOrgProfile profile, AuditPolicy policy, Long tenantId) {
        if (content == null || content.isBlank()) return new Hydration(content, List.of(), List.of());
        String resolved = variableResolver.resolve(content, policy, tenantId);
        return hydrate(resolved, profile);
    }

    /**
     * Profile-only hydration, for the DRAFT path where no AuditPolicy exists yet.
     * Falls back to AiOrgProfile for company name and owner, because at
     * generation time there is no persisted policy for the authoritative
     * resolver to read from.
     */
    public Hydration hydrate(String content, AiOrgProfile profile) {
        if (content == null || content.isBlank()) return new Hydration(content, List.of(), List.of());

        Map<String, String> custom = customFacts(profile);
        Set<String> filled = new LinkedHashSet<>();
        Set<String> unresolved = new LinkedHashSet<>();

        String out = replace(content, MUSTACHE, profile, custom, filled, unresolved, true);
        out = replace(out, SQUARE, profile, custom, filled, unresolved, false);

        if (!unresolved.isEmpty()) {
            log.debug("[AI-TEMPLATE] {} placeholder(s) left for the reviewer: {}", unresolved.size(), unresolved);
        }
        return new Hydration(out, new ArrayList<>(filled), new ArrayList<>(unresolved));
    }

    private String replace(String content, Pattern pattern, AiOrgProfile profile,
                           Map<String, String> custom, Set<String> filled,
                           Set<String> unresolved, boolean mustacheStyle) {
        Matcher m = pattern.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String raw = m.group(1).trim();
            String key = raw.toLowerCase().replace(' ', '_');

            String value = resolve(key, profile);
            if (value == null || value.isBlank()) value = custom.get(key);

            if (value != null && !value.isBlank()) {
                filled.add(key);
                m.appendReplacement(sb, Matcher.quoteReplacement(value));
            } else {
                unresolved.add(key);
                // Always emit the square form — one convention downstream.
                m.appendReplacement(sb, Matcher.quoteReplacement(
                        "[[" + (mustacheStyle ? raw.replace('_', ' ').toUpperCase() : raw) + "]]"));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * AiOrgProfile.customFactsJson doubles as the overflow for policy
     * parameters. A tenant storing {"label":"deprovision_sla","value":"4 hours"}
     * gets {{deprovision_sla}} filled with no schema change — which is what that
     * field is for, and why the org-profile screen should mention it.
     */
    private Map<String, String> customFacts(AiOrgProfile p) {
        Map<String, String> out = new LinkedHashMap<>();
        // Draft-path fallbacks only. When a real AuditPolicy exists,
        // PolicyVariableResolver has already replaced these and the values here
        // are never reached.
        if (p != null) {
            String company = firstNonBlank(p.getShortName(), p.getLegalName());
            if (company != null) {
                out.put("company_name", company);
                out.put("organisation_name", company);
                out.put("organization_name", company);
            }
            String owner = joinRole(p.getSecurityOwnerName(), p.getSecurityOwnerTitle());
            if (owner != null) out.put("policy_owner", owner);
        }
        if (p == null || p.getCustomFactsJson() == null || p.getCustomFactsJson().isBlank()) return out;
        try {
            JsonNode facts = mapper.readTree(p.getCustomFactsJson());
            if (facts.isArray()) {
                for (JsonNode f : facts) {
                    String label = f.path("label").asText("").trim().toLowerCase().replace(' ', '_');
                    String value = f.path("value").asText("");
                    if (!label.isEmpty() && !value.isBlank()) out.put(label, value);
                }
            }
        } catch (Exception e) {
            log.debug("[AI-TEMPLATE] customFactsJson unparseable, skipped");
        }
        return out;
    }

    // ── Post-generation check ─────────────────────────────────────────────────

    /**
     * Placeholders surviving into finished output.
     *
     * PolicyAiService surfaces these as warnings so the UI can highlight them.
     * A reviewer who can see the four gaps will fill them; a reviewer who cannot
     * will approve a policy containing [[RTO]] and only discover it when an
     * auditor reads it aloud.
     */
    public List<String> findUnfilled(String content) {
        if (content == null) return List.of();
        Set<String> found = new LinkedHashSet<>();
        Matcher sq = SQUARE.matcher(content);
        while (sq.find()) found.add(sq.group(1).trim());
        Matcher mu = MUSTACHE.matcher(content);
        while (mu.find()) {
            String key = mu.group(1).trim();
            // A {{approver_name}} in a DRAFT is correct, not an omission. Only
            // report it if the catalogue does not recognise it as workflow-filled.
            PolicyVariableCatalog.Variable def = PolicyVariableCatalog.get(key);
            if (def != null && def.isWorkflow()) continue;
            found.add(key);
        }
        return new ArrayList<>(found);
    }

    private static String kmsFor(String cloudProviders) {
        if (cloudProviders == null) return null;
        String c = cloudProviders.toUpperCase();
        if (c.contains("AWS"))   return "AWS KMS";
        if (c.contains("AZURE")) return "Azure Key Vault";
        if (c.contains("GCP") || c.contains("GOOGLE")) return "Google Cloud KMS";
        return null;
    }

    private static String joinRole(String name, String title) {
        if (name == null || name.isBlank()) return null;
        return (title == null || title.isBlank()) ? name : name + " (" + title + ")";
    }

    private static String firstNonBlank(String... v) {
        for (String s : v) if (s != null && !s.isBlank()) return s;
        return null;
    }
}