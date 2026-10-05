package com.kashi.grc.accessreview.service;

import com.kashi.grc.accessreview.domain.AccessReviewCampaign;
import com.kashi.grc.accessreview.domain.AccessReviewItem;
import com.kashi.grc.accessreview.repository.AccessReviewCampaignRepository;
import com.kashi.grc.accessreview.repository.AccessReviewItemRepository;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.exception.ValidationException;
import com.kashi.grc.personnel.domain.Personnel;
import com.kashi.grc.personnel.repository.PersonnelRepository;
import com.kashi.grc.usermanagement.domain.Role;
import com.kashi.grc.usermanagement.domain.User;
import com.kashi.grc.usermanagement.repository.FirmAccessGrantRepository;
import com.kashi.grc.usermanagement.repository.UserPermissionOverrideRepository;
import com.kashi.grc.usermanagement.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Access certification campaigns.
 *
 * ── THE GENERATOR SWEEPS FOUR THINGS, NOT ONE ─────────────────────────────
 * Most tools review role assignments. That misses the two that matter most: a
 * direct permission override sitting outside any role, and an external audit
 * firm holding cross-tenant access to this tenant's data. Both are rarer than
 * role grants and both are riskier, which is exactly why nobody looks at them.
 *
 * ── AND IT REFUSES TO CALL A CAMPAIGN COMPLETE ON A PROMISE ───────────────
 * complete() throws while any REVOKE is still unconfirmed. A certification
 * asserting that access was removed, made while the access is still in place,
 * is worse than no certification — it converts a gap into documented,
 * signed-off assurance that the gap does not exist.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccessReviewService {

    private final AccessReviewCampaignRepository campaignRepository;
    private final AccessReviewItemRepository     itemRepository;
    private final UserRepository                 userRepository;
    private final PersonnelRepository            personnelRepository;
    private final UserPermissionOverrideRepository overrideRepository;
    private final FirmAccessGrantRepository      firmGrantRepository;

    /** No login in this long is worth a reviewer's attention regardless of role. */
    private static final int DORMANT_DAYS = 90;

    // ═════════════════════════════════════════════════════════════════════════
    // CREATE AND LAUNCH
    // ═════════════════════════════════════════════════════════════════════════

    @Transactional
    public AccessReviewCampaign create(Map<String, Object> req, Long userId, Long tenantId) {
        String name = str(req.get("name"));
        if (name == null) throw new ValidationException("The campaign needs a name.");

        return campaignRepository.save(AccessReviewCampaign.builder()
                .tenantId(tenantId)
                .campaignRef(nextRef(tenantId))
                .name(name)
                .description(str(req.get("description")))
                .scopeType(orDefault(str(req.get("scopeType")), "ALL_USERS"))
                .scopeRoleId(asLong(req.get("scopeRoleId")))
                .periodStart(asDate(req.get("periodStart")))
                .periodEnd(asDate(req.get("periodEnd")))
                .dueAt(asDate(req.get("dueAt")) == null ? null : asDate(req.get("dueAt")).atStartOfDay())
                .status(AccessReviewCampaign.Status.DRAFT)
                .createdBy(userId)
                .build());
    }

    /**
     * Freezes the population and creates an item per entitlement.
     *
     * Generation happens at LAUNCH, not continuously. A campaign whose
     * population keeps changing under the reviewers can never be finished, and
     * the certification would describe a moving target rather than a moment.
     */
    @Transactional
    public int launch(Long campaignId, Long tenantId) {
        AccessReviewCampaign c = require(campaignId, tenantId);
        if (c.getStatus() != AccessReviewCampaign.Status.DRAFT) {
            throw new ValidationException("Only a draft campaign can be launched.");
        }
        if (itemRepository.countByCampaignId(campaignId) > 0) {
            throw new ValidationException("This campaign already has items.");
        }

        List<User> users = userRepository.findAll().stream()
                .filter(u -> tenantId.equals(u.getTenantId()))
                .toList();

        // Roster rows by user id, for the manager lookup and to spot the users
        // who have none — that absence is itself a finding.
        Map<Long, Personnel> rosterByUser = new HashMap<>();
        for (Personnel p : personnelRepository.findAll()) {
            if (tenantId.equals(p.getTenantId()) && p.getUserId() != null) {
                rosterByUser.put(p.getUserId(), p);
            }
        }

        int created = 0;
        for (User u : users) {
            Personnel roster = rosterByUser.get(u.getId());
            if (!inScope(c, u, roster)) continue;

            Long reviewer = resolveReviewer(u, roster, rosterByUser);
            String label  = subjectLabel(u);
            List<String> subjectFlags = flagsFor(u, roster, reviewer);

            // 1. ROLES
            for (Role r : safeRoles(u)) {
                created += save(c, tenantId, u, roster, label, "ROLE", r.getId(),
                        r.getName(), reviewer, withRoleFlags(subjectFlags, r));
            }

            // 2. DIRECT PERMISSION OVERRIDES
            //    Always flagged PRIVILEGED. A permission granted outside any
            //    role was granted for a specific reason that is usually no
            //    longer true, and there is no role owner watching it.
            for (var o : overrideRepository.findAll()) {
                if (!u.getId().equals(o.getUserId())) continue;
                List<String> f = new ArrayList<>(subjectFlags);
                f.add("PRIVILEGED");
                created += save(c, tenantId, u, roster, label, "PERMISSION_OVERRIDE", o.getId(),
                        (o.isGranted() ? "GRANT " : "DENY ") + o.getPermissionCode(), reviewer, f);
            }

            // 3. THE ACCOUNT ITSELF
            //    Only when something is odd about it. Certifying every account's
            //    existence alongside its roles doubles the list for no gain —
            //    but an account with no roster row, or one belonging to somebody
            //    who has left, is a separate question from "should they have
            //    this role", and it needs its own answer.
            if (subjectFlags.contains("NO_ROSTER_ROW")
                    || subjectFlags.contains("OFFBOARDED_STILL_ACTIVE")
                    || subjectFlags.contains("DORMANT_90D")) {
                created += save(c, tenantId, u, roster, label, "ACCOUNT", u.getId(),
                        u.getEmail(), reviewer, subjectFlags);
            }
        }

        // 4. EXTERNAL FIRM GRANTS
        //    Not per user — a firm grant is an organisation holding access to
        //    this tenant's data, and it belongs to whoever runs the campaign
        //    rather than to any one person's manager.
        for (var g : firmGrantRepository.findAll()) {
            if (!tenantId.equals(g.getClientTenantId())) continue;
            if (!"ACTIVE".equalsIgnoreCase(g.getStatus())) continue;
            created += save(c, tenantId, null, null,
                    "External firm #" + g.getFirmTenantId(), "FIRM_GRANT", g.getId(),
                    "Cross-tenant access, expires " + g.getExpiresAt(),
                    c.getCreatedBy(), List.of("EXTERNAL_FIRM", "PRIVILEGED"));
        }

        c.setStatus(AccessReviewCampaign.Status.IN_PROGRESS);
        c.setLaunchedAt(LocalDateTime.now());
        campaignRepository.save(c);

        log.info("[ACCESS-REVIEW] Launched | ref={} | {} item(s) across {} user(s)",
                c.getCampaignRef(), created, users.size());
        return created;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // DECIDE AND CONFIRM
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Records a reviewer's decision.
     *
     * REVOKE and MODIFY both leave something to be done, so both set
     * revocationStatus PENDING. MODIFY is included deliberately: "reduce this
     * person to read-only" is as much an outstanding change as removing the
     * role, and a tool that only tracks revocations lets every modification
     * evaporate.
     */
    @Transactional
    public AccessReviewItem decide(Long itemId, String decisionRaw, String note,
                                   Long userId, Long tenantId) {
        AccessReviewItem item = requireItem(itemId, tenantId);

        AccessReviewItem.Decision decision;
        try {
            decision = AccessReviewItem.Decision.valueOf(String.valueOf(decisionRaw).toUpperCase());
        } catch (Exception e) {
            throw new ValidationException("Decision must be RETAIN, REVOKE or MODIFY.");
        }
        if (decision == AccessReviewItem.Decision.PENDING) {
            throw new ValidationException("Choose RETAIN, REVOKE or MODIFY.");
        }
        // Revoking without saying why leaves whoever carries it out guessing at
        // scope, and leaves the auditor with a decision nobody can explain.
        if (decision != AccessReviewItem.Decision.RETAIN && str(note) == null) {
            throw new ValidationException(
                    "Say what needs to change and why — whoever carries this out needs to know "
                            + "the scope, and it is the line an auditor reads.");
        }

        item.setDecision(decision);
        item.setDecisionNote(str(note));
        item.setDecidedAt(LocalDateTime.now());
        item.setDecidedBy(userId);
        item.setRevocationStatus(decision == AccessReviewItem.Decision.RETAIN
                ? AccessReviewItem.RevocationStatus.NOT_REQUIRED
                : AccessReviewItem.RevocationStatus.PENDING);

        return itemRepository.save(item);
    }

    /**
     * Confirms the change was actually made.
     *
     * A separate permission and usually a separate person: the manager decides,
     * IT carries it out. Letting the decider also confirm turns the proof back
     * into the promise it was meant to replace.
     */
    @Transactional
    public AccessReviewItem confirmRevocation(Long itemId, boolean succeeded, String note,
                                              Long userId, Long tenantId) {
        AccessReviewItem item = requireItem(itemId, tenantId);
        if (item.getRevocationStatus() == AccessReviewItem.RevocationStatus.NOT_REQUIRED) {
            throw new ValidationException("Nothing was due to change on this item.");
        }
        item.setRevocationStatus(succeeded
                ? AccessReviewItem.RevocationStatus.CONFIRMED
                : AccessReviewItem.RevocationStatus.FAILED);
        item.setRevocationConfirmedAt(LocalDateTime.now());
        item.setRevocationConfirmedBy(userId);
        item.setRevocationNote(str(note));
        return itemRepository.save(item);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // COMPLETE — THE GATE
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Closes the campaign, or refuses and says exactly what is outstanding.
     *
     * The refusal is the module. Anyone can build a status field; the value is
     * in the one place that will not let you write COMPLETED over a promise.
     */
    @Transactional
    public AccessReviewCampaign complete(Long campaignId, Long userId, Long tenantId) {
        AccessReviewCampaign c = require(campaignId, tenantId);
        List<AccessReviewItem> items = itemRepository.findByCampaignId(campaignId);

        long undecided = items.stream()
                .filter(i -> i.getDecision() == AccessReviewItem.Decision.PENDING).count();
        long unrevoked = items.stream()
                .filter(i -> i.getRevocationStatus() == AccessReviewItem.RevocationStatus.PENDING).count();
        long failed = items.stream()
                .filter(i -> i.getRevocationStatus() == AccessReviewItem.RevocationStatus.FAILED).count();

        if (undecided > 0) {
            throw new ValidationException(
                    undecided + " item(s) have not been reviewed yet.");
        }
        if (unrevoked > 0 || failed > 0) {
            // Moved to PENDING_REMEDIATION rather than left IN_PROGRESS, so the
            // list distinguishes "reviewers still working" from "reviewers done,
            // IT has not acted".
            c.setStatus(AccessReviewCampaign.Status.PENDING_REMEDIATION);
            campaignRepository.save(c);
            throw new ValidationException(
                    "Every item has been reviewed, but " + (unrevoked + failed) + " change(s) have "
                            + "not been confirmed as carried out. A certification saying access was "
                            + "removed, signed while it is still in place, is worse than no "
                            + "certification. The campaign is now awaiting remediation.");
        }

        Map<String, Object> summary = summarise(items);
        c.setStatus(AccessReviewCampaign.Status.COMPLETED);
        c.setCompletedAt(LocalDateTime.now());
        c.setCompletedBy(userId);
        // Frozen: recomputing this months later would give a different answer
        // than the one that was signed.
        c.setSummaryJson(toJson(summary));

        log.info("[ACCESS-REVIEW] Completed | ref={} | {}", c.getCampaignRef(), summary);
        return campaignRepository.save(c);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SCOPE, REVIEWERS AND FLAGS
    // ═════════════════════════════════════════════════════════════════════════

    private boolean inScope(AccessReviewCampaign c, User u, Personnel roster) {
        return switch (c.getScopeType()) {
            case "PRIVILEGED_ONLY" -> safeRoles(u).stream()
                    .anyMatch(r -> r.getName() != null
                            && (r.getName().contains("ADMIN") || r.getName().contains("OWNER")));
            case "VENDOR_STAFF"    -> u.getVendorId() != null;
            case "EXTERNAL_FIRMS"  -> false;   // handled by the firm-grant sweep
            case "BY_ROLE"         -> c.getScopeRoleId() != null && safeRoles(u).stream()
                    .anyMatch(r -> c.getScopeRoleId().equals(r.getId()));
            default                -> true;
        };
    }

    /**
     * Who decides.
     *
     * The roster manager first, because that person knows whether the access is
     * still needed. Falling back to User.managerId, then to nobody — and NOBODY
     * IS LEFT NULL rather than defaulting to an administrator.
     *
     * Auto-assigning unowned items to an admin is how a review becomes one
     * person clicking retain 400 times. An unassigned item carries the
     * NO_MANAGER flag and has to be assigned deliberately, which surfaces the
     * real problem: somebody has no manager on the roster.
     */
    private Long resolveReviewer(User u, Personnel roster, Map<Long, Personnel> rosterByUser) {
        if (roster != null && roster.getManagerPersonnelId() != null) {
            Optional<Personnel> mgr = personnelRepository.findById(roster.getManagerPersonnelId());
            if (mgr.isPresent() && mgr.get().getUserId() != null) return mgr.get().getUserId();
        }
        return u.getManagerId();
    }

    private List<String> flagsFor(User u, Personnel roster, Long reviewer) {
        List<String> f = new ArrayList<>();
        if (roster == null)  f.add("NO_ROSTER_ROW");
        if (reviewer == null) f.add("NO_MANAGER");
        if (roster != null && "OFFBOARDED".equalsIgnoreCase(String.valueOf(roster.getStatus()))) {
            f.add("OFFBOARDED_STILL_ACTIVE");
        }
        if (u.getLastLogin() == null
                || u.getLastLogin().isBefore(LocalDateTime.now().minusDays(DORMANT_DAYS))) {
            f.add("DORMANT_90D");
        }
        if (u.getVendorId() != null) f.add("VENDOR_STAFF");
        return f;
    }

    private List<String> withRoleFlags(List<String> base, Role r) {
        List<String> f = new ArrayList<>(base);
        if (r.getName() != null && (r.getName().contains("ADMIN") || r.getName().contains("OWNER"))) {
            f.add("PRIVILEGED");
        }
        return f;
    }

    /** One campaign, tenant-checked. Public because the controller needs it. */
    @Transactional(readOnly = true)
    public AccessReviewCampaign getCampaign(Long id, Long tenantId) {
        return require(id, tenantId);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // STATS
    // ═════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public Map<String, Object> getStats(Long tenantId) {
        List<AccessReviewItem> items = itemRepository.findByTenantId(tenantId);
        List<AccessReviewCampaign> campaigns =
                campaignRepository.findByTenantIdAndIsDeletedFalse(tenantId);

        Map<String, Long> byDecision = new LinkedHashMap<>();
        Map<String, Long> byEntitlement = new LinkedHashMap<>();
        long unconfirmed = 0, undecided = 0;
        for (AccessReviewItem i : items) {
            byDecision.merge(i.getDecision().name(), 1L, Long::sum);
            byEntitlement.merge(i.getEntitlementType(), 1L, Long::sum);
            if (i.getRevocationStatus() == AccessReviewItem.RevocationStatus.PENDING
                    || i.getRevocationStatus() == AccessReviewItem.RevocationStatus.FAILED) unconfirmed++;
            if (i.getDecision() == AccessReviewItem.Decision.PENDING) undecided++;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("campaigns", campaigns.size());
        out.put("activeCampaigns", campaigns.stream()
                .filter(c -> c.getStatus() == AccessReviewCampaign.Status.IN_PROGRESS
                        || c.getStatus() == AccessReviewCampaign.Status.PENDING_REMEDIATION)
                .count());
        out.put("totalItems", items.size());
        out.put("undecided", undecided);
        // The number that matters: decided to remove, not yet removed.
        out.put("unconfirmedRevocations", unconfirmed);
        out.put("byDecision", series(byDecision));
        out.put("byEntitlementType", series(byEntitlement));
        return out;
    }

    private Map<String, Object> summarise(List<AccessReviewItem> items) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", items.size());
        m.put("retained", items.stream()
                .filter(i -> i.getDecision() == AccessReviewItem.Decision.RETAIN).count());
        m.put("revoked", items.stream()
                .filter(i -> i.getDecision() == AccessReviewItem.Decision.REVOKE).count());
        m.put("modified", items.stream()
                .filter(i -> i.getDecision() == AccessReviewItem.Decision.MODIFY).count());
        m.put("completedAt", LocalDateTime.now().toString());
        return m;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    private int save(AccessReviewCampaign c, Long tenantId, User u, Personnel roster,
                     String label, String type, Long entId, String entLabel,
                     Long reviewer, List<String> flags) {
        itemRepository.save(AccessReviewItem.builder()
                .tenantId(tenantId)
                .campaignId(c.getId())
                .subjectUserId(u == null ? null : u.getId())
                .subjectPersonnelId(roster == null ? null : roster.getId())
                .subjectLabel(label)
                .entitlementType(type)
                .entitlementId(entId)
                .entitlementLabel(entLabel)
                .reviewerUserId(reviewer)
                .decision(AccessReviewItem.Decision.PENDING)
                .revocationStatus(AccessReviewItem.RevocationStatus.NOT_REQUIRED)
                .flagsJson(toJsonArray(flags))
                .build());
        return 1;
    }

    /** Roles are LAZY on User; an unopened session must not blow up the sweep. */
    private Set<Role> safeRoles(User u) {
        try {
            return u.getRoles() == null ? Set.of() : u.getRoles();
        } catch (Exception e) {
            return Set.of();
        }
    }

    private String subjectLabel(User u) {
        String n = u.getFullName();
        return (n == null || n.isBlank()) ? u.getEmail() : n;
    }

    private String nextRef(Long tenantId) {
        long seq = campaignRepository.nextRefSequence(tenantId);
        String candidate = String.format("ARC-%d-%03d", LocalDate.now().getYear(), seq);
        int guard = 0;
        while (campaignRepository.existsByCampaignRefAndTenantId(candidate, tenantId) && guard++ < 1000) {
            candidate = String.format("ARC-%d-%03d", LocalDate.now().getYear(), ++seq);
        }
        return candidate;
    }

    private AccessReviewCampaign require(Long id, Long tenantId) {
        AccessReviewCampaign c = campaignRepository.findById(id)
                .filter(x -> !x.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("AccessReviewCampaign", id));
        if (!tenantId.equals(c.getTenantId())) {
            throw new ResourceNotFoundException("AccessReviewCampaign", id);
        }
        return c;
    }

    private AccessReviewItem requireItem(Long id, Long tenantId) {
        AccessReviewItem i = itemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AccessReviewItem", id));
        if (!tenantId.equals(i.getTenantId())) {
            throw new ResourceNotFoundException("AccessReviewItem", id);
        }
        return i;
    }

    private List<Map<String, Object>> series(Map<String, Long> counts) {
        List<Map<String, Object>> out = new ArrayList<>(counts.size());
        counts.forEach((k, v) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", k);
            row.put("value", v);
            out.add(row);
        });
        return out;
    }

    /** Small enough that a JSON library would be more ceremony than it saves. */
    private String toJsonArray(List<String> values) {
        if (values == null || values.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(values.get(i).replace("\"", "\\\"")).append('"');
        }
        return sb.append(']').toString();
    }

    private String toJson(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v instanceof Number) sb.append(v);
            else sb.append('"').append(String.valueOf(v).replace("\"", "\\\"")).append('"');
        }
        return sb.append('}').toString();
    }

    private String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private String orDefault(String s, String fallback) { return s == null ? fallback : s; }

    private Long asLong(Object o) {
        try { return o == null ? null : Long.parseLong(String.valueOf(o)); }
        catch (Exception e) { return null; }
    }

    private LocalDate asDate(Object o) {
        try { return o == null ? null : LocalDate.parse(String.valueOf(o).substring(0, 10)); }
        catch (Exception e) { return null; }
    }
}