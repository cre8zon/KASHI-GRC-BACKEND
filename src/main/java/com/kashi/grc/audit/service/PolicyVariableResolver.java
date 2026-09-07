package com.kashi.grc.audit.service;

import com.kashi.grc.audit.domain.AuditPolicy;
import com.kashi.grc.tenant.repository.TenantRepository;
import com.kashi.grc.usermanagement.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves {{placeholders}} in policy content at READ time.
 *
 * ── WHY READ TIME AND NOT COPY TIME ─────────────────────────────────────────
 * The obvious implementation substitutes when a policy is adopted, baking the
 * values into content_body. That breaks in three ways:
 *
 *   • {{approver_name}} and {{approval_date}} DO NOT EXIST when the copy is
 *     made. They are decided at step 3 of the workflow, possibly weeks later.
 *     No copy-time substitution can produce them.
 *   • {{policy_owner}} is decided by the workflow and changes when someone
 *     leaves. Baked, it is wrong the moment it is written.
 *   • {{company_name}} baked into 39 documents means a rename is a bulk
 *     find-and-replace across policy content.
 *
 * Resolving on the way out means the document always reads true, and the
 * source text stays reusable — a tenant's copy is still recognisably the
 * platform policy, which matters when the platform updates it.
 *
 * ── UNRESOLVED PLACEHOLDERS ─────────────────────────────────────────────────
 * Left as a readable hint — "[approver — pending]" — not blanked and not left
 * as raw {{approver_name}}. A DRAFT policy genuinely has no approver, and the
 * document should say so rather than look broken or, worse, look complete.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PolicyVariableResolver {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*}}");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("d MMMM yyyy");

    private final TenantRepository tenantRepository;
    private final UserRepository   userRepository;

    /**
     * @param body     raw content_body, may be null
     * @param policy   the policy being rendered — supplies owner, approver, dates
     * @param tenantId the READER's tenant. Passed explicitly rather than taken
     *                 from policy.tenantId because a GLOBAL policy has none, and
     *                 a tenant previewing one before adopting should still see
     *                 their own company name.
     */
    public String resolve(String body, AuditPolicy policy, Long tenantId) {
        if (body == null || body.isEmpty() || !body.contains("{{")) return body;

        // A GLOBAL policy is a TEMPLATE — leave its placeholders alone.
        //
        // Resolving them against the READER's tenant made a platform admin see
        // their own tenant name written into every global policy — not what the
        // template says and not what any tenant will receive. It also hid the
        // placeholders from the people who maintain them: a typo'd
        // {{company_nmae}} is invisible if it renders as a company name.
        //
        // Tenants are unaffected — they read their own adopted copy, which is
        // tenant-owned and still resolves.
        if (policy != null && policy.getTenantId() == null) return body;

        Map<String, String> vars = buildVars(policy, tenantId);

        Matcher m = PLACEHOLDER.matcher(body);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String key = m.group(1).toLowerCase();
            String val = vars.get(key);
            if (val == null || val.isBlank()) {
                // Unknown OR not yet known — both render as a hint. Deliberately
                // not distinguishing: a typo'd placeholder and a pending approver
                // both mean "this is not filled in", and the author fixes either
                // by looking at the document.
                val = "[" + key.replace('_', ' ') + " — pending]";
            }
            m.appendReplacement(out, Matcher.quoteReplacement(val));
        }
        m.appendTail(out);
        return out.toString();
    }

    private Map<String, String> buildVars(AuditPolicy policy, Long tenantId) {
        Map<String, String> v = new HashMap<>();

        if (tenantId != null) {
            tenantRepository.findById(tenantId).ifPresent(t -> {
                v.put("company_name", t.getName());
                v.put("organisation_name", t.getName());
                v.put("organization_name", t.getName());   // both spellings — the
                // seeded policies use US
            });
        }
        if (policy == null) return v;

        if (policy.getOwnerTeam() != null && !policy.getOwnerTeam().isBlank()) {
            v.put("policy_owner", policy.getOwnerTeam());
        } else if (policy.getOwnerId() != null) {
            userRepository.findById(policy.getOwnerId())
                    .ifPresent(u -> v.put("policy_owner", u.getFullName()));
        }

        if (policy.getApprovedById() != null) {
            userRepository.findById(policy.getApprovedById())
                    .ifPresent(u -> v.put("approver_name", u.getFullName()));
        }
        if (policy.getApprovedAt() != null) {
            v.put("approval_date", policy.getApprovedAt().format(DATE_FMT));
        }
        if (policy.getEffectiveDate() != null) {
            v.put("effective_date", policy.getEffectiveDate().format(DATE_FMT));
        }
        if (policy.getNextReviewDate() != null) {
            v.put("next_review_date", policy.getNextReviewDate().format(DATE_FMT));
        }
        if (policy.getReviewFrequencyMonths() != null) {
            int months = policy.getReviewFrequencyMonths();
            v.put("review_cycle", months == 12 ? "Annual"
                    : months == 6  ? "Every 6 months"
                      : months == 3  ? "Quarterly"
                        : months + " months");
        }
        v.put("policy_ref",   policy.getPolicyRef());
        v.put("policy_title", policy.getTitle());
        v.put("policy_version", policy.getVersion() == null ? null : String.valueOf(policy.getVersion()));
        return v;
    }
}