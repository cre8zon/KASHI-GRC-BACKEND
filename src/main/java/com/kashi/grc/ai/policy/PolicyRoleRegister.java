package com.kashi.grc.ai.policy;

import com.kashi.grc.ai.domain.AiOrgProfile;
import com.kashi.grc.ai.domain.PolicyRoleAssignment;
import com.kashi.grc.ai.repository.AiOrgProfileRepository;
import com.kashi.grc.ai.repository.PolicyRoleAssignmentRepository;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.usermanagement.domain.User;
import com.kashi.grc.usermanagement.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The policy role register: who holds each accountable role in a tenant.
 * See PolicyRoleAssignment for why policies name roles and how history is kept.
 *
 * Three consumers:
 *   - the AI profile screen (list / assign / vacate),
 *   - PolicyContextAssembler, which tells the model the exact role titles to use,
 *   - PolicyVariableResolver, which fills {{role_<key>}} with the current holder
 *     when a policy is read.
 *
 * It also keeps the org profile's security owner and privacy officer NAMES in
 * step (applyTo): older placeholders such as [[SECURITY OFFICER]] still read
 * those columns, and they must not name someone who has left the role.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PolicyRoleRegister {

    private final PolicyRoleAssignmentRepository repository;
    private final UserRepository                 userRepository;
    private final AiOrgProfileRepository         profileRepository;

    // ── Read ──────────────────────────────────────────────────────────────────

    /** Every assignment, current and past, newest first within each role. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(Long tenantId) {
        List<PolicyRoleAssignment> rows = repository.findByTenantIdOrderByRoleTitleAscEffectiveFromDesc(tenantId);
        Map<Long, User> users = usersById(rows.stream().map(PolicyRoleAssignment::getUserId).collect(Collectors.toSet()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (PolicyRoleAssignment r : rows) {
            User u = users.get(r.getUserId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("roleKey", r.getRoleKey());
            m.put("roleTitle", r.getRoleTitle());
            m.put("userId", r.getUserId());
            m.put("userName", u == null ? null : name(u));
            m.put("userEmail", u == null ? null : u.getEmail());
            m.put("effectiveFrom", r.getEffectiveFrom());
            m.put("effectiveTo", r.getEffectiveTo());
            m.put("current", r.getEffectiveTo() == null);
            m.put("variable", "{{role_" + r.getRoleKey() + "}}");
            m.put("note", r.getNote());
            out.add(m);
        }
        return out;
    }

    /** Current holder's name per role key — for the variable resolver. */
    @Transactional(readOnly = true)
    public Map<String, String> currentHolderNames(Long tenantId) {
        if (tenantId == null) return Map.of();
        List<PolicyRoleAssignment> rows = repository.findByTenantIdAndEffectiveToIsNull(tenantId);
        Map<Long, User> users = usersById(rows.stream().map(PolicyRoleAssignment::getUserId).collect(Collectors.toSet()));
        Map<String, String> out = new HashMap<>();
        for (PolicyRoleAssignment r : rows) {
            User u = users.get(r.getUserId());
            if (u != null) out.put(r.getRoleKey(), name(u));
        }
        return out;
    }

    /** Current role titles -> keys, for the model's instructions and the roles table. */
    @Transactional(readOnly = true)
    public Map<String, String> currentRoleKeysByTitle(Long tenantId) {
        if (tenantId == null) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        repository.findByTenantIdAndEffectiveToIsNull(tenantId).stream()
                .sorted(Comparator.comparing(PolicyRoleAssignment::getRoleTitle, String.CASE_INSENSITIVE_ORDER))
                .forEach(r -> out.put(r.getRoleTitle(), r.getRoleKey()));
        return out;
    }

    /**
     * Every role the tenant has ever had, current or vacant: key -> latest
     * title. Lets a reader tell "vacant role" apart from "not a role at all".
     */
    @Transactional(readOnly = true)
    public Map<String, String> roleTitlesByKey(Long tenantId) {
        if (tenantId == null) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        // newest first within each role, so putIfAbsent keeps the latest title
        repository.findByTenantIdOrderByRoleTitleAscEffectiveFromDesc(tenantId)
                .forEach(r -> out.putIfAbsent(r.getRoleKey(), r.getRoleTitle()));
        return out;
    }

    /**
     * How a policy owner reads when it names a register role:
     * "Engineering Lead (Priyanka Paridhi)", or "Engineering Lead — vacant".
     * Null when the owner is not a register role (a team name such as
     * "Engineering"), so the caller shows it as written.
     */
    @Transactional(readOnly = true)
    public String ownerDisplay(Long tenantId, String owner) {
        if (tenantId == null || owner == null || owner.isBlank()) return null;
        String key = slug(owner.trim().replaceAll("\\s+", " "));
        String title = roleTitlesByKey(tenantId).get(key);
        if (title == null) return null;
        String holder = currentHolderNames(tenantId).get(key);
        return holder == null ? title + " — vacant" : title + " (" + holder + ")";
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Give a role to a user from today. The current holder's tenure is ended,
     * not overwritten. Assigning the person who already holds it is a no-op.
     */
    @Transactional
    public Map<String, Object> assign(Long tenantId, Long assignedBy, String roleTitle, Long userId, String note) {
        return assign(tenantId, assignedBy, roleTitle, userId, note, null);
    }

    /**
     * As above, and fills the holder's job title when it is empty. Job title
     * was never mandatory, so most users have none; the screen offers to set it
     * while assigning. It never overwrites a title that exists: that field
     * belongs to whoever manages users, and changing someone's title is not
     * what assigning a policy role means.
     */
    @Transactional
    public Map<String, Object> assign(Long tenantId, Long assignedBy, String roleTitle, Long userId, String note,
                                      String jobTitle) {
        String title = roleTitle == null ? "" : roleTitle.trim().replaceAll("\\s+", " ");
        if (title.isEmpty() || title.length() > 120) {
            throw new BusinessException("INVALID_ROLE_TITLE",
                    "Give the role a title of up to 120 characters, as it should appear in policies.",
                    HttpStatus.BAD_REQUEST);
        }
        User user = userId == null ? null : userRepository.findById(userId).orElse(null);
        // Organisation roles go to the organisation's own people — not to an
        // external auditor or vendor user who merely has access to this tenant.
        if (user == null || user.isDeleted() || !Objects.equals(user.getTenantId(), tenantId) || !user.isActive()) {
            throw new BusinessException("INVALID_ROLE_HOLDER",
                    "Choose an active user from your own organisation.", HttpStatus.BAD_REQUEST);
        }

        String jt = jobTitle == null ? "" : jobTitle.trim().replaceAll("\\s+", " ");
        if (!jt.isEmpty() && (user.getJobTitle() == null || user.getJobTitle().isBlank())) {
            user.setJobTitle(jt.length() > 255 ? jt.substring(0, 255) : jt);
            userRepository.save(user);
            log.info("[POLICY-ROLES] tenant={} user={} job title set to '{}' (was empty)", tenantId, userId, user.getJobTitle());
        }

        String key = keyFor(tenantId, title);
        LocalDate today = LocalDate.now();
        Optional<PolicyRoleAssignment> current = repository.findFirstByTenantIdAndRoleKeyAndEffectiveToIsNull(tenantId, key);
        if (current.isPresent() && Objects.equals(current.get().getUserId(), userId)) {
            return list(tenantId).stream().filter(m -> Objects.equals(m.get("id"), current.get().getId()))
                    .findFirst().orElse(Map.of());
        }
        current.ifPresent(c -> { c.setEffectiveTo(today); repository.save(c); });

        PolicyRoleAssignment row = PolicyRoleAssignment.builder()
                .tenantId(tenantId)
                .roleKey(key)
                .roleTitle(title)
                .userId(userId)
                .effectiveFrom(today)
                .assignedBy(assignedBy)
                .note(note == null || note.isBlank() ? null : note.length() > 300 ? note.substring(0, 300) : note)
                .build();
        repository.save(row);
        log.info("[POLICY-ROLES] tenant={} role={} -> user={} (previous ended: {})",
                tenantId, key, userId, current.isPresent());
        syncProfile(tenantId);
        return list(tenantId).stream().filter(m -> Objects.equals(m.get("id"), row.getId()))
                .findFirst().orElse(Map.of());
    }

    /** End the current holder's tenure and leave the role vacant. History stays. */
    @Transactional
    public void vacate(Long tenantId, String roleKey) {
        repository.findFirstByTenantIdAndRoleKeyAndEffectiveToIsNull(tenantId, roleKey).ifPresent(c -> {
            c.setEffectiveTo(LocalDate.now());
            repository.save(c);
            log.info("[POLICY-ROLES] tenant={} role={} vacated (was user={})", tenantId, roleKey, c.getUserId());
        });
        syncProfile(tenantId);
    }

    // ── Org profile names ─────────────────────────────────────────────────────

    /**
     * Sets the profile's holder names from the register, where the register
     * governs them. Does not save.
     *   - Security owner: the profile stores which role owns security as
     *     security_owner_title. If that role is (or was) in the register, the
     *     name is its current holder, or empty while it is vacant.
     *   - Privacy officer: the current holder of a role whose title names
     *     privacy, data protection or DPO, once such a role exists.
     * Profiles with no matching role keep whatever was typed before the
     * register existed.
     * Not annotated: it runs inside the caller's transaction (profile save,
     * assign, vacate), and a read-only marker there could suppress the flush.
     */
    public void applyTo(AiOrgProfile p) {
        if (p == null || p.getTenantId() == null) return;
        List<PolicyRoleAssignment> all = repository.findByTenantIdOrderByRoleTitleAscEffectiveFromDesc(p.getTenantId());
        if (all.isEmpty()) return;
        Map<String, String> names = currentHolderNames(p.getTenantId());
        Set<String> keys = all.stream().map(PolicyRoleAssignment::getRoleKey).collect(Collectors.toSet());

        String ownerTitle = p.getSecurityOwnerTitle();
        if (ownerTitle != null && !ownerTitle.isBlank()) {
            String k = slug(ownerTitle.trim().replaceAll("\\s+", " "));
            if (keys.contains(k)) p.setSecurityOwnerName(names.get(k));
        }

        List<String> privacyKeys = keys.stream().filter(PolicyRoleRegister::isPrivacyKey).sorted().toList();
        if (!privacyKeys.isEmpty()) {
            p.setPrivacyOfficerName(privacyKeys.stream().map(names::get).filter(Objects::nonNull)
                    .findFirst().orElse(null));
        }
    }

    private void syncProfile(Long tenantId) {
        profileRepository.findByTenantId(tenantId).ifPresent(p -> {
            String owner = p.getSecurityOwnerName(), privacy = p.getPrivacyOfficerName();
            applyTo(p);
            if (!Objects.equals(owner, p.getSecurityOwnerName()) || !Objects.equals(privacy, p.getPrivacyOfficerName())) {
                profileRepository.save(p);
            }
        });
    }

    static boolean isPrivacyKey(String key) {
        return key != null && (key.contains("privacy") || key.contains("data_protection")
                || key.equals("dpo") || key.startsWith("dpo_") || key.endsWith("_dpo") || key.contains("_dpo_"));
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    /**
     * The key for a title. Reuses the key of any earlier assignment with the
     * same title (case-insensitive), so {{role_engineering_lead}} in an approved
     * policy keeps resolving after the role changes hands or falls vacant.
     */
    private String keyFor(Long tenantId, String title) {
        String slug = slug(title);
        return repository.findFirstByTenantIdAndRoleKeyOrderByEffectiveFromDesc(tenantId, slug)
                .map(PolicyRoleAssignment::getRoleKey).orElse(slug);
    }

    static String slug(String title) {
        String s = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (s.isEmpty()) s = "role";
        return s.length() > 50 ? s.substring(0, 50) : s;
    }

    private Map<Long, User> usersById(Set<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        Map<Long, User> out = new HashMap<>();
        userRepository.findAllById(ids).forEach(u -> out.put(u.getId(), u));
        return out;
    }

    private static String name(User u) {
        String n = u.getFullName();
        return n == null || n.isBlank() ? u.getEmail() : n;
    }
}