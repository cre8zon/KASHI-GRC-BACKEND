package com.kashi.grc.common.security;

import java.util.List;

/**
 * Which permission each module write needs, for the writes no ui_actions row
 * describes: creating a record, the edit forms, the lifecycle moves a form
 * submits, and linking.
 *
 * ModuleActionGuard consults this ONLY when no active ui_action is bound to the
 * endpoint. A button-bound endpoint keeps the button's own required_permission,
 * so a screen designer who changes a button's permission changes the API too.
 *
 * Codes are taken verbatim from the permissions table (risk:*, issue:*,
 * incident:*, asset:*, personnel:*, exception:*). Any one listed code passes.
 * Where the table carries two eras of the same permission (ISSUE_CREATE and
 * issue:create), both are listed so neither kind of role is locked out.
 *
 * A caller without the code still passes when they hold an open task on, or
 * override authority over, the record's active workflow step — the same
 * people the screen already lets edit on that step.
 *
 * Deliberately absent:
 *   PATCH /v1/issues/{id}/status and the named issue transitions (triage,
 *   start, submit-for-review, resolve, close, accept-risk): IssueController
 *   already requires the workflow step holder or an override holder.
 *   POST /v1/issues/ingest: anonymous by design, gated by SecurityConfig.
 */
final class ModuleWriteRules {

    private ModuleWriteRules() {}

    /** method, path template ({x} = one segment), workflow entity type, codes (any of). */
    record Rule(String method, String template, String entityType, List<String> codes) {}

    private static Rule r(String method, String template, String entityType, String... codes) {
        return new Rule(method, template, entityType, List.of(codes));
    }

    static final List<Rule> RULES = List.of(
            // ── Risk register ────────────────────────────────────────────────
            r("POST",   "/v1/risks",                         "RISK", "risk:create"),
            r("PUT",    "/v1/risks/{id}",                    "RISK", "risk:create", "risk:assess", "risk:treat"),
            r("PUT",    "/v1/risks/{id}/assessment",         "RISK", "risk:assess"),
            r("POST",   "/v1/risks/{id}/assess",             "RISK", "risk:assess"),
            r("PUT",    "/v1/risks/{id}/treatment",          "RISK", "risk:treat"),
            r("POST",   "/v1/risks/{id}/plan-treatment",     "RISK", "risk:treat"),
            r("POST",   "/v1/risks/{id}/mark-treated",       "RISK", "risk:treat"),
            r("POST",   "/v1/risks/{id}/accept",             "RISK", "risk:accept"),
            r("POST",   "/v1/risks/{id}/close",              "RISK", "risk:close"),
            r("POST",   "/v1/risks/{id}/reopen",             "RISK", "risk:close"),
            r("POST",   "/v1/risks/{id}/controls",           "RISK", "risk:treat"),
            r("DELETE", "/v1/risks/{id}/controls/{x}",       "RISK", "risk:treat"),
            r("POST",   "/v1/risks/library/adopt-all",       null,   "risk:create"),
            r("POST",   "/v1/risks/library/{id}/adopt",      null,   "risk:create"),

            // ── Issues ───────────────────────────────────────────────────────
            r("POST",   "/v1/issues",                        "ISSUE", "issue:create", "ISSUE_CREATE"),
            r("PUT",    "/v1/issues/{id}",                   "ISSUE", "issue:update"),
            r("POST",   "/v1/issues/{id}/evidence/{x}/review", "ISSUE", "issue:validate", "ISSUE_RESOLVE"),
            r("POST",   "/v1/issues/{id}/request-changes",   "ISSUE", "issue:validate", "ISSUE_RESOLVE"),
            r("POST",   "/v1/issues/{id}/reopen",            "ISSUE", "issue:reopen"),
            r("POST",   "/v1/issues/{id}/link-finding",      "ISSUE", "issue:link-finding"),

            // ── Incidents ────────────────────────────────────────────────────
            r("POST",   "/v1/incidents",                     "INCIDENT", "incident:create"),
            r("PUT",    "/v1/incidents/{id}",                "INCIDENT", "incident:update"),
            r("PUT",    "/v1/incidents/{id}/response",       "INCIDENT", "incident:respond"),
            r("PUT",    "/v1/incidents/{id}/regulatory",     "INCIDENT", "incident:report"),
            r("POST",   "/v1/incidents/{id}/triage",         "INCIDENT", "incident:triage"),
            r("POST",   "/v1/incidents/{id}/false-positive", "INCIDENT", "incident:triage"),
            r("POST",   "/v1/incidents/{id}/investigate",    "INCIDENT", "incident:respond"),
            r("POST",   "/v1/incidents/{id}/contain",        "INCIDENT", "incident:respond"),
            r("POST",   "/v1/incidents/{id}/eradicate",      "INCIDENT", "incident:respond"),
            r("POST",   "/v1/incidents/{id}/recover",        "INCIDENT", "incident:respond"),
            r("POST",   "/v1/incidents/{id}/close",          "INCIDENT", "incident:close"),
            r("POST",   "/v1/incidents/{id}/reopen",         "INCIDENT", "incident:close"),
            r("POST",   "/v1/incidents/{id}/notifications",  "INCIDENT", "incident:report"),
            r("POST",   "/v1/incidents/{id}/assets",         "INCIDENT", "incident:respond"),
            r("DELETE", "/v1/incidents/{id}/assets/{x}",     "INCIDENT", "incident:respond"),
            r("POST",   "/v1/incidents/{id}/risks",          "INCIDENT", "incident:respond"),
            r("DELETE", "/v1/incidents/{id}/risks/{x}",      "INCIDENT", "incident:respond"),

            // ── Assets ───────────────────────────────────────────────────────
            r("POST",   "/v1/assets",                        "ASSET", "asset:create"),
            r("PUT",    "/v1/assets/{id}",                   "ASSET", "asset:update"),
            r("PUT",    "/v1/assets/{id}/valuation",         "ASSET", "asset:update"),
            r("PUT",    "/v1/assets/{id}/lifecycle",         "ASSET", "asset:update"),
            r("POST",   "/v1/assets/{id}/send-repair",       "ASSET", "asset:update"),
            r("POST",   "/v1/assets/{id}/store",             "ASSET", "asset:update"),
            r("POST",   "/v1/assets/{id}/return-service",    "ASSET", "asset:update"),
            r("POST",   "/v1/assets/{id}/retire",            "ASSET", "asset:retire"),
            r("POST",   "/v1/assets/{id}/reinstate",         "ASSET", "asset:retire"),
            r("POST",   "/v1/assets/{id}/dispose",           "ASSET", "asset:dispose"),
            r("POST",   "/v1/assets/{id}/risks",             "ASSET", "asset:update"),
            r("DELETE", "/v1/assets/{id}/risks/{x}",         "ASSET", "asset:update"),

            // ── Personnel ────────────────────────────────────────────────────
            r("POST",   "/v1/personnel",                     "PERSONNEL", "personnel:create"),
            r("POST",   "/v1/personnel/pull-users",          null,        "personnel:create"),
            r("PUT",    "/v1/personnel/{id}",                "PERSONNEL", "personnel:update"),
            r("PUT",    "/v1/personnel/{id}/employment",     "PERSONNEL", "personnel:update"),
            r("PUT",    "/v1/personnel/{id}/screening",      "PERSONNEL", "personnel:screen"),
            r("POST",   "/v1/personnel/{id}/activate",       "PERSONNEL", "personnel:update"),
            r("POST",   "/v1/personnel/{id}/never-started",  "PERSONNEL", "personnel:update"),
            r("POST",   "/v1/personnel/{id}/start-leave",    "PERSONNEL", "personnel:update"),
            r("POST",   "/v1/personnel/{id}/return-leave",   "PERSONNEL", "personnel:update"),
            r("POST",   "/v1/personnel/{id}/give-notice",    "PERSONNEL", "personnel:update", "personnel:offboard"),
            r("POST",   "/v1/personnel/{id}/start-offboarding",    "PERSONNEL", "personnel:offboard"),
            r("POST",   "/v1/personnel/{id}/complete-offboarding", "PERSONNEL", "personnel:offboard"),
            r("POST",   "/v1/personnel/{id}/exclusions",     "PERSONNEL", "personnel:exclude"),
            r("DELETE", "/v1/personnel/{id}/exclusions/{x}", "PERSONNEL", "personnel:exclude"),
            r("POST",   "/v1/personnel/{id}/assets",         "PERSONNEL", "personnel:update"),
            r("DELETE", "/v1/personnel/{id}/assets/{x}",     "PERSONNEL", "personnel:update"),
            r("DELETE", "/v1/personnel/{id}",                "PERSONNEL", "personnel:delete"),

            // ── Exception register ───────────────────────────────────────────
            r("POST",   "/v1/exceptions",                    "EXCEPTION", "exception:create"),
            r("POST",   "/v1/exceptions/{id}/submit",        "EXCEPTION", "exception:create"),
            r("POST",   "/v1/exceptions/{id}/links",         "EXCEPTION", "exception:create"),
            r("POST",   "/v1/exceptions/{id}/approve",       "EXCEPTION", "exception:approve"),
            r("POST",   "/v1/exceptions/{id}/reject",        "EXCEPTION", "exception:approve"),
            r("POST",   "/v1/exceptions/{id}/renew",         "EXCEPTION", "exception:renew"),
            r("POST",   "/v1/exceptions/{id}/revoke",        "EXCEPTION", "exception:revoke")
    );

    /** The rule for this request, or null when the endpoint has none. */
    static Rule match(String method, String path) {
        for (Rule rule : RULES) {
            if (rule.method().equalsIgnoreCase(method) && matches(rule.template(), path)) return rule;
        }
        return null;
    }

    /** The {id} segment of the request, when the template has one and it is numeric. */
    static Long entityId(Rule rule, String path) {
        String[] ts = trim(rule.template()).split("/");
        String[] ps = trim(path).split("/");
        for (int i = 0; i < ts.length && i < ps.length; i++) {
            if ("{id}".equals(ts[i])) {
                try { return Long.parseLong(ps[i]); } catch (NumberFormatException e) { return null; }
            }
        }
        return null;
    }

    private static boolean matches(String template, String path) {
        String[] ts = trim(template).split("/");
        String[] ps = trim(path).split("/");
        if (ts.length != ps.length) return false;
        for (int i = 0; i < ts.length; i++) {
            boolean placeholder = ts[i].startsWith("{") && ts[i].endsWith("}");
            if (placeholder ? ps[i].isEmpty() : !ts[i].equals(ps[i])) return false;
        }
        return true;
    }

    private static String trim(String s) {
        return s.endsWith("/") && s.length() > 1 ? s.substring(0, s.length() - 1) : s;
    }
}