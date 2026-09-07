package com.kashi.grc.ai.policy;

import lombok.Getter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The complete catalogue of policy variables, and where each one comes from.
 *
 * ── THE THREE-WAY DISTINCTION THAT MAKES THIS WORK ───────────────────────────
 * A generated policy contains three different kinds of unknown, and treating
 * them identically is what makes AI-drafted documents feel wrong:
 *
 *   WORKFLOW      Not knowable at generation time, and knowable LATER without
 *                 anyone typing it. policy_owner, approver_name, approval_date.
 *                 The AI must emit {{mustache}} for these so
 *                 PolicyVariableResolver fills them as the adoption workflow
 *                 progresses. Writing "[approver]" here would be wrong: the
 *                 system knows the approver the moment step 3 completes.
 *
 *   ORG FACT      Known now, from Tenant or AiOrgProfile. company_name,
 *                 cloud_provider, identity_provider. The AI writes the LITERAL
 *                 VALUE — "Northwind encrypts data in AWS KMS" reads as a
 *                 policy; "{{company_name}} encrypts data in {{cloud_provider}}"
 *                 reads as a template someone forgot to finish.
 *
 *   PARAMETER     A decision nobody has made yet. deprovision_sla, rto,
 *                 patch_sla_critical. The AI emits [[DEPROVISION SLA]] because
 *                 a human must choose it — and a visible gap is correctable
 *                 while a plausible invention is not.
 *
 * Collapsing these into one mechanism gives you either documents full of raw
 * mustache, or documents asserting an SLA nobody agreed to. Both are worse than
 * the split.
 *
 * ── WHY A REGISTRY AND NOT A SWITCH STATEMENT ────────────────────────────────
 * Three consumers need the same list: the resolver, the prompt (which must tell
 * the model which convention to use for which variable), and the UI variable
 * picker. Duplicating it three ways guarantees they drift, and the drift is
 * silent — the model keeps emitting a variable the resolver stopped handling.
 */
public final class PolicyVariableCatalog {

    private PolicyVariableCatalog() {}

    public enum Source {
        /** Filled by PolicyVariableResolver from the workflow. AI emits {{key}}. */
        WORKFLOW,
        /** Known from Tenant. AI writes the literal value. */
        TENANT,
        /** Known from AiOrgProfile. AI writes the literal value. */
        ORG_PROFILE,
        /** From AiOrgProfile.customFactsJson if set, otherwise [[PLACEHOLDER]]. */
        PARAMETER
    }

    @Getter
    public static class Variable {
        private final String key;
        private final String label;
        private final Source source;
        private final String category;
        private final String example;
        private final String guidance;

        Variable(String key, String label, Source source, String category, String example, String guidance) {
            this.key = key; this.label = label; this.source = source;
            this.category = category; this.example = example; this.guidance = guidance;
        }
        public boolean isWorkflow()  { return source == Source.WORKFLOW; }
        public boolean isParameter() { return source == Source.PARAMETER; }
    }

    private static final Map<String, Variable> BY_KEY = new LinkedHashMap<>();

    private static void v(String key, String label, Source s, String cat, String example, String guidance) {
        BY_KEY.put(key, new Variable(key, label, s, cat, example, guidance));
    }

    static {
        // ── WORKFLOW — the adoption workflow supplies these as it progresses ──
        // The AI emits {{mustache}}. PolicyVariableResolver fills them on read.
        v("policy_owner",      "Policy owner",       Source.WORKFLOW, "Workflow", "Head of Security",
                "Assigned when the drafter submits for review");
        v("approver_name",     "Approver",           Source.WORKFLOW, "Workflow", "Meera Raghavan",
                "Set when the approval step completes");
        v("approval_date",     "Approval date",      Source.WORKFLOW, "Workflow", "14 March 2026",
                "Stamped at approval");
        v("reviewer_name",     "Reviewer",           Source.WORKFLOW, "Workflow", "Arjun Nair",
                "Set at the review step");
        v("effective_date",    "Effective date",     Source.WORKFLOW, "Workflow", "1 April 2026", null);
        v("next_review_date",  "Next review date",   Source.WORKFLOW, "Workflow", "1 April 2027", null);
        v("review_cycle",      "Review cycle",       Source.WORKFLOW, "Workflow", "Annual", null);
        v("policy_ref",        "Policy reference",   Source.WORKFLOW, "Workflow", "POL-03", null);
        v("policy_title",      "Policy title",       Source.WORKFLOW, "Workflow", "Access Control Policy", null);
        v("policy_version",    "Version",            Source.WORKFLOW, "Workflow", "2", null);

        // ── TENANT — always the tenant the policy is being created FOR ────────
        v("company_name",      "Company name",       Source.TENANT, "Organisation", "Northwind Data Systems",
                "Always the adopting tenant, never the platform");
        v("organisation_name", "Organisation name",  Source.TENANT, "Organisation", "Northwind Data Systems", null);

        // ── ORG PROFILE — identity and posture ────────────────────────────────
        v("legal_name",           "Legal entity name",   Source.ORG_PROFILE, "Organisation", "Northwind Data Systems Private Limited", null);
        v("industry",             "Industry",            Source.ORG_PROFILE, "Organisation", "B2B SaaS", null);
        v("employee_count",       "Headcount",           Source.ORG_PROFILE, "Organisation", "140", null);
        v("headquarters",         "Headquarters",        Source.ORG_PROFILE, "Organisation", "India", null);
        v("operating_countries",  "Operating countries", Source.ORG_PROFILE, "Organisation", "India, Singapore", null);
        v("frameworks_in_scope",  "Frameworks in scope", Source.ORG_PROFILE, "Organisation", "SOC 2, ISO 27001, DPDP", null);
        v("data_residency",       "Data residency",      Source.ORG_PROFILE, "Organisation", "ap-south-1, eu-west-1", null);
        v("remote_work_model",    "Working model",       Source.ORG_PROFILE, "Organisation", "Remote-first", null);

        // ── ORG PROFILE — technical estate. The specificity multiplier. ───────
        v("cloud_provider",        "Cloud provider",     Source.ORG_PROFILE, "Technical estate", "AWS", null);
        v("identity_provider",     "Identity provider",  Source.ORG_PROFILE, "Technical estate", "Okta", null);
        v("mdm",                   "Device management",  Source.ORG_PROFILE, "Technical estate", "Jamf", null);
        v("edr",                   "Endpoint protection",Source.ORG_PROFILE, "Technical estate", "CrowdStrike", null);
        v("code_repository",       "Source control",     Source.ORG_PROFILE, "Technical estate", "GitHub", null);
        v("ticketing_system",      "Ticketing",          Source.ORG_PROFILE, "Technical estate", "Jira", null);
        v("key_management_system", "Key management",     Source.ORG_PROFILE, "Technical estate", "AWS KMS",
                "Derived from the cloud provider when not set explicitly");

        // ── ORG PROFILE — accountable people ──────────────────────────────────
        v("security_owner",     "Security owner",     Source.ORG_PROFILE, "Roles", "Meera Raghavan (Head of Security)", null);
        v("security_contact",   "Security contact",   Source.ORG_PROFILE, "Roles", "security@northwind.example", null);
        v("privacy_officer",    "Privacy officer",    Source.ORG_PROFILE, "Roles", "Arjun Nair", null);
        v("incident_contact",   "Incident reporting", Source.ORG_PROFILE, "Roles", "incident@northwind.example", null);

        // ── PARAMETERS — decisions a human must make ──────────────────────────
        // These are the ones customers most often have not decided. Emitting a
        // plausible default here is the single most dangerous thing the AI could
        // do: an SLA nobody agreed to, in an approved document, is a commitment.
        v("deprovision_sla",          "Leaver revocation SLA",   Source.PARAMETER, "Parameters", "4 hours", null);
        v("access_review_cadence",    "Access review cadence",   Source.PARAMETER, "Parameters", "Quarterly", null);
        v("patch_sla_critical",       "Critical patch SLA",      Source.PARAMETER, "Parameters", "7 days", null);
        v("patch_sla_high",           "High patch SLA",          Source.PARAMETER, "Parameters", "30 days", null);
        v("vulnerability_scan_cadence","Scan cadence",           Source.PARAMETER, "Parameters", "Weekly", null);
        v("backup_frequency",         "Backup frequency",        Source.PARAMETER, "Parameters", "Daily", null);
        v("backup_test_cadence",      "Restore test cadence",    Source.PARAMETER, "Parameters", "Quarterly", null);
        v("rto",                      "Recovery time objective", Source.PARAMETER, "Parameters", "4 hours", null);
        v("rpo",                      "Recovery point objective",Source.PARAMETER, "Parameters", "1 hour", null);
        v("log_retention",            "Log retention period",    Source.PARAMETER, "Parameters", "12 months",
                "DPDP Rule 6 requires at least one year");
        v("min_tls_version",          "Minimum TLS version",     Source.PARAMETER, "Parameters", "TLS 1.2", null);
        v("password_min_length",      "Minimum password length", Source.PARAMETER, "Parameters", "12 characters", null);
        v("session_timeout",          "Session timeout",         Source.PARAMETER, "Parameters", "30 minutes", null);
        v("training_cadence",         "Training cadence",        Source.PARAMETER, "Parameters", "Annually", null);
        v("vendor_review_cadence",    "Vendor review cadence",   Source.PARAMETER, "Parameters", "Annually", null);
        v("incident_notification_sla","Incident notification",   Source.PARAMETER, "Parameters", "6 hours",
                "CERT-In Directions require 6 hours from noticing");
        v("breach_notification_sla",  "Breach notification",     Source.PARAMETER, "Parameters", "72 hours",
                "DPDP Rule 7 requires a detailed report within 72 hours");
        v("data_retention_period",    "Data retention period",   Source.PARAMETER, "Parameters", "3 years", null);
    }

    public static Variable get(String key) {
        return key == null ? null : BY_KEY.get(key.trim().toLowerCase().replace(' ', '_'));
    }

    public static List<Variable> all() { return new ArrayList<>(BY_KEY.values()); }

    public static List<Variable> bySource(Source s) {
        return BY_KEY.values().stream().filter(v -> v.getSource() == s).toList();
    }

    /** Keys the AI must emit as {{mustache}} rather than resolve. */
    public static List<String> workflowKeys() {
        return bySource(Source.WORKFLOW).stream().map(Variable::getKey).toList();
    }

    /**
     * The instruction block injected into the draft prompt.
     *
     * Written as rules with worked examples because that is what models follow.
     * "Use placeholders appropriately" produces inconsistent output; showing the
     * three cases side by side produces consistent output.
     */
    public static String promptInstructions() {
        StringBuilder sb = new StringBuilder("""
                PLACEHOLDER CONVENTIONS — follow these exactly

                Three kinds of value appear in a policy, and each is written differently.

                1. WORKFLOW VALUES — write these as {{double_braces}}, never as a name.
                   The approval workflow fills them in automatically as it progresses,
                   so a literal name written now would be wrong and would not update.
                   Available workflow variables:
                """);
        for (Variable v : bySource(Source.WORKFLOW)) {
            sb.append("     {{").append(v.getKey()).append("}}  — ").append(v.getLabel());
            if (v.getGuidance() != null) sb.append(" (").append(v.getGuidance()).append(")");
            sb.append('\n');
        }
        sb.append("""
                   Example: "This policy is owned by {{policy_owner}} and was approved
                   by {{approver_name}} on {{approval_date}}."

                2. ORGANISATION FACTS — write the LITERAL VALUE from the context given
                   above. Never write these as a placeholder; you have been told them.
                   Example: "Northwind encrypts customer data at rest using AWS KMS."
                   NOT:     "{{company_name}} encrypts data using {{key_management_system}}."

                3. UNDECIDED PARAMETERS — write as [[UPPER CASE IN DOUBLE BRACKETS]]
                   when the value has not been supplied. These are decisions a human
                   must make; inventing a plausible one puts a commitment nobody agreed
                   to into an approved document.
                   Example: "Leaver access is revoked within [[DEPROVISION SLA]] of the
                   HR exit trigger."
                   Common parameters: """);
        sb.append(String.join(", ",
                bySource(Source.PARAMETER).stream().map(Variable::getKey).limit(12).toList()));
        sb.append("\n");
        return sb.toString();
    }
}