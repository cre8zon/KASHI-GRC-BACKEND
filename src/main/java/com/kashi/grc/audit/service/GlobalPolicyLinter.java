package com.kashi.grc.audit.service;

import com.kashi.grc.audit.domain.AuditPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Warns when a GLOBAL policy contains text that should have been a placeholder.
 *
 * A platform policy is read by every organisation, with {{company_name}} and the
 * rest resolved per tenant at read time. If the author typed a literal instead —
 * their own company name, a colleague's name, today's date — that literal ships
 * to every client. There is no error at any layer: the policy saves, approves
 * and renders. It is simply wrong in a way only a reader notices.
 *
 * WARNS, NEVER BLOCKS. Every rule here is a heuristic over prose, and a false
 * positive that refuses approval would be worse than the problem: an author with
 * a legitimate literal ("aligned with ISO 27001:2022") must not be stuck. The
 * warnings ride back on the approve response for the UI to show.
 *
 * Only runs for tenant_id IS NULL. A tenant's own policy naming that tenant is
 * correct, and linting it would be noise.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GlobalPolicyLinter {

    /** One finding. `hint` names the variable that should have been used. */
    public record Warning(String rule, String found, String hint) {}

    // A date written out where {{approval_date}} or {{effective_date}} belongs.
    // Deliberately narrow: real formatted dates, not any number.
    private static final Pattern LITERAL_DATE = Pattern.compile(
            "\\b(\\d{1,2}\\s+(January|February|March|April|May|June|July|August|September|October|November|December)\\s+\\d{4}"
                    + "|(January|February|March|April|May|June|July|August|September|October|November|December)\\s+\\d{1,2},?\\s+\\d{4}"
                    + "|\\d{4}-\\d{2}-\\d{2}"
                    + "|\\d{1,2}/\\d{1,2}/\\d{4})\\b");

    // "Owner: Jane Smith" / "Approved by: A. Rao" — a person pinned into a
    // document that will be read by organisations that have never heard of them.
    private static final Pattern LABELLED_PERSON = Pattern.compile(
            "(?i)\\b(policy owner|owner|approved by|approver|reviewed by|prepared by)\\s*[:\\-]\\s*"
                    + "([A-Z][a-z]+(?:\\s+[A-Z][a-z.']+){1,2})");

    /**
     * @param tenantName the name of the tenant whose user is authoring. Passing it
     *                   in rather than looking it up keeps this class free of
     *                   repositories and trivially testable.
     */
    public List<Warning> lint(AuditPolicy policy, String tenantName) {
        List<Warning> out = new ArrayList<>();
        if (policy == null || policy.getTenantId() != null) return out;   // global only

        String body = stripHtml(policy.getContentBody());
        if (body == null || body.isBlank()) return out;

        // 1. The author's own organisation name, the failure this exists for.
        if (tenantName != null && tenantName.length() >= 4
                && body.toLowerCase().contains(tenantName.toLowerCase())) {
            out.add(new Warning(
                    "TENANT_NAME_IN_GLOBAL_POLICY",
                    tenantName,
                    "Use {{company_name}} so each organisation sees their own name."));
        }

        // 2. Literal dates.
        Matcher d = LITERAL_DATE.matcher(body);
        if (d.find()) {
            out.add(new Warning(
                    "LITERAL_DATE",
                    d.group(),
                    "If this is the approval or effective date, use {{approval_date}} "
                            + "or {{effective_date}} — a fixed date is wrong for every later adopter."));
        }

        // 3. A named person against an ownership label.
        Matcher p = LABELLED_PERSON.matcher(body);
        if (p.find()) {
            out.add(new Warning(
                    "NAMED_PERSON",
                    p.group(),
                    "Use {{policy_owner}} or {{approver_name}} — the owner differs per organisation."));
        }

        if (!out.isEmpty()) {
            log.info("[POLICY-LINT] global policy {} approved with {} warning(s): {}",
                    policy.getId(), out.size(), out.stream().map(Warning::rule).toList());
        }
        return out;
    }

    /**
     * contentBody is HTML from the WYSIWYG editor. Without stripping, tag and
     * attribute text ("style", "colspan") joins the prose and the person pattern
     * matches markup rather than content.
     */
    private static String stripHtml(String html) {
        if (html == null) return null;
        return html.replaceAll("<[^>]+>", " ").replaceAll("&nbsp;", " ").replaceAll("\\s+", " ");
    }
}