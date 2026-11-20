package com.kashi.grc.ai.agent;

import com.kashi.grc.asset.domain.Asset;
import com.kashi.grc.audit.domain.AuditPolicy;
import com.kashi.grc.exception_register.domain.ExceptionRecord;
import com.kashi.grc.incident.domain.Incident;
import com.kashi.grc.issue.domain.Issue;
import com.kashi.grc.risk.domain.Risk;
import com.kashi.grc.vendor.domain.Vendor;

import java.util.List;
import java.util.Locale;

/**
 * A register KashiGuard AI may read, and how to read it.
 *
 * ── WHO MAY READ WHAT ────────────────────────────────────────────────────────
 * A source is offered to a user only when their own sidebar shows its list
 * page (one of listRoutes). The navigation is already filtered by side, permission and
 * tenant feature (UiConfigService.getNavigation), so the agent sees exactly
 * the registers the person could open themselves — no second permission model
 * to keep in step, and a role that loses a menu entry loses it here too.
 *
 * ── ATTRIBUTE NAMES ──────────────────────────────────────────────────────────
 * JPA attribute names, read through the Criteria API. An attribute missing from
 * an entity is skipped at runtime (AgentRecordFinder checks the metamodel), so
 * a renamed field degrades one column of context instead of failing the call.
 */
public record AgentSource(
        String key,             // RISK, ISSUE, ...
        String label,           // "Risk"
        String labelPlural,     // "Risks"
        Class<?> entity,
        List<String> listRoutes,// sidebar routes that grant access; the one the user has
                                // is also the base of record links (route + "/" + id)
        String refAttr,         // nullable
        String titleAttr,
        String descriptionAttr, // nullable
        String statusAttr,      // nullable
        String severityAttr,    // nullable
        List<String> fields,    // extra columns worth showing
        boolean tenantOwnedOnly,// GlobalOrTenant entities: exclude platform library rows
        List<String> keywords   // words in a question that point at this register
) {

    /** Attributes holding a user id, shown as that person's name. */
    public static final List<String> PERSON_FIELDS = List.of("ownerId", "requestedBy");

    /** The first of listRoutes found among the user's visible routes, or null. */
    public String grantedRoute(java.util.Set<String> visibleRoutes) {
        for (String r : listRoutes) if (visibleRoutes.contains(r)) return r;
        return null;
    }

    public boolean mentionedIn(String questionLower) {
        for (String k : keywords) if (questionLower.contains(k)) return true;
        return false;
    }

    public static AgentSource byRoute(String route) {
        if (route == null) return null;
        String r = route.split("\\?")[0].toLowerCase(Locale.ROOT);
        for (AgentSource s : ALL) {
            for (String lr : s.listRoutes) if (r.equals(lr) || r.startsWith(lr + "/")) return s;
        }
        return null;
    }

    public static AgentSource byKey(String key) {
        if (key == null) return null;
        for (AgentSource s : ALL) if (s.key.equalsIgnoreCase(key)) return s;
        return null;
    }

    public static final List<AgentSource> ALL = List.of(
            new AgentSource("POLICY", "Policy", "Policies", AuditPolicy.class,
                    List.of("/module/audit_policy"),
                    "policyRef", "title", "description", "status", null,
                    List.of("version", "ownerTeam", "reviewFrequencyMonths", "nextReviewDate", "frameworkRefs"),
                    true,
                    List.of("polic", "procedure", "standard")),

            new AgentSource("RISK", "Risk", "Risks", Risk.class,
                    List.of("/module/risk", "/module/risks"),
                    "riskRef", "title", "description", "status", null,
                    List.of("category", "inherentScore", "residualScore", "treatmentOption", "ownerId",
                            "ownerTeam", "nextReviewDate"),
                    true,
                    List.of("risk", "treatment", "residual", "inherent", "likelihood")),

            new AgentSource("ISSUE", "Issue", "Issues", Issue.class,
                    List.of("/module/issue", "/module/issues"),
                    "issueRef", "title", "description", "status", "severity",
                    List.of("category", "sourceModule", "dueAt", "ownerId", "frameworkRef"),
                    false,
                    List.of("issue", "finding", "vulnerab", "remediat", "overdue", "cve", "gap")),

            new AgentSource("INCIDENT", "Incident", "Incidents", Incident.class,
                    List.of("/module/incident", "/module/incidents"),
                    "incidentRef", "title", "description", "status", "severity",
                    List.of("incidentType", "occurredAt", "detectedAt", "ownerId", "ownerTeam"),
                    false,
                    List.of("incident", "breach", "outage", "attack", "phishing", "ransomware")),

            new AgentSource("ASSET", "Asset", "Assets", Asset.class,
                    List.of("/module/asset", "/module/assets"),
                    "assetRef", "name", "description", "status", null,
                    List.of("assetType", "criticality", "classification", "environment", "hostingModel",
                            "ownerId", "ownerTeam"),
                    false,
                    List.of("asset", "server", "laptop", "device", "inventory", "database", "endpoint")),

            new AgentSource("EXCEPTION", "Exception", "Exceptions", ExceptionRecord.class,
                    List.of("/module/exception", "/module/exception_record", "/module/exceptions"),
                    "exceptionRef", "title", "justification", "status", null,
                    List.of("compensatingControls", "expiresAt", "reviewDueAt", "requestedBy"),
                    false,
                    List.of("exception", "waiver", "deviation")),

            new AgentSource("VENDOR", "Vendor", "Vendors", Vendor.class,
                    List.of("/module/vendor", "/tprm/vendors"),
                    null, "name", "servicesProvided", "status", null,
                    List.of("criticality", "riskClassification", "currentRiskScore", "dataAccessLevel", "country"),
                    false,
                    List.of("vendor", "supplier", "third part", "third-part", "tprm", "sub-processor", "subprocessor"))
    );
}
