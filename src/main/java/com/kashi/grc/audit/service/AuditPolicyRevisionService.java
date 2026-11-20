package com.kashi.grc.audit.service;

import com.kashi.grc.audit.domain.AuditPolicy;
import com.kashi.grc.audit.domain.AuditPolicyRevision;
import com.kashi.grc.audit.repository.AuditPolicyRepository;
import com.kashi.grc.audit.repository.AuditPolicyRevisionRepository;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.usermanagement.domain.User;
import com.kashi.grc.usermanagement.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Draft revisions of a policy body. See AuditPolicyRevision for what a revision
 * is and why it is not a version.
 *
 * Access mirrors AuditPolicyController exactly: reading follows the read rule
 * (own tenant, or a global row), writing follows the write rule (own tenant
 * only; global rows are platform-maintained). Restoring additionally requires
 * an editable status, the same DRAFT / UNDER_REVIEW set the editor uses.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditPolicyRevisionService {

    public static final Set<String> SOURCES =
            Set.of("MANUAL", "BEFORE_AI", "AI_DRAFT", "BEFORE_RESTORE", "RESTORED");

    private static final Set<AuditPolicy.PolicyStatus> EDITABLE =
            EnumSet.of(AuditPolicy.PolicyStatus.DRAFT, AuditPolicy.PolicyStatus.UNDER_REVIEW);

    private final AuditPolicyRevisionRepository revisionRepository;
    private final AuditPolicyRepository         policyRepository;
    private final UserRepository                userRepository;
    private final UtilityService                utilityService;
    private final AuditLibraryCacheService      libraryCache;

    // ── Read ──────────────────────────────────────────────────────────────────

    /** Newest first, without bodies — the History panel lists dozens. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(Long policyId) {
        requireReadable(load(policyId));
        List<AuditPolicyRevision> rows = revisionRepository.findByPolicyIdOrderByRevisionNoDesc(policyId);
        Map<Long, String> names = namesFor(rows.stream().map(AuditPolicyRevision::getCreatedBy)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        return rows.stream().map(r -> summary(r, names)).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(Long policyId, Long revisionId) {
        requireReadable(load(policyId));
        AuditPolicyRevision r = revisionRepository.findByIdAndPolicyId(revisionId, policyId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditPolicyRevision", revisionId));
        Map<String, Object> out = summary(r, namesFor(r.getCreatedBy() == null ? Set.of() : Set.of(r.getCreatedBy())));
        out.put("contentBody", r.getContentBody());
        return out;
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Snapshot a body. contentBody null means "what is saved on the policy right
     * now"; the editor passes its own HTML for BEFORE_AI so unsaved typing is
     * kept too. Returns the existing latest revision instead of a duplicate when
     * nothing changed.
     */
    @Transactional
    public Map<String, Object> snapshot(Long policyId, String source, String contentBody,
                                        Long aiInteractionId, String note) {
        AuditPolicy policy = load(policyId);
        requireOwned(policy);
        String src = source == null ? "MANUAL" : source.trim().toUpperCase();
        if (!SOURCES.contains(src)) {
            throw new BusinessException("INVALID_REVISION_SOURCE",
                    "Unknown revision source: " + source, HttpStatus.BAD_REQUEST);
        }
        AuditPolicyRevision r = take(policy, src, contentBody != null ? contentBody : policy.getContentBody(),
                aiInteractionId, note);
        return summary(r, namesFor(r.getCreatedBy() == null ? Set.of() : Set.of(r.getCreatedBy())));
    }

    /**
     * Put an older revision's body back on the policy. The current body is
     * snapshotted first (BEFORE_RESTORE), so a restore can itself be undone.
     */
    @Transactional
    public Map<String, Object> restore(Long policyId, Long revisionId) {
        AuditPolicy policy = load(policyId);
        requireOwned(policy);
        if (!EDITABLE.contains(policy.getStatus())) {
            throw new BusinessException("POLICY_NOT_EDITABLE",
                    "Only a draft or a policy under review can be changed. Start a new version to edit an approved policy.",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        AuditPolicyRevision target = revisionRepository.findByIdAndPolicyId(revisionId, policyId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditPolicyRevision", revisionId));

        take(policy, "BEFORE_RESTORE", policy.getContentBody(), null, null);
        policy.setContentBody(target.getContentBody());
        policyRepository.save(policy);
        AuditPolicyRevision restored = take(policy, "RESTORED", target.getContentBody(), null,
                "Restored from r" + target.getRevisionNo());
        libraryCache.evictLibraryLists();

        log.info("[AUDIT-POLICY-REVISION] Restored | policy={} from r{} as r{}",
                policyId, target.getRevisionNo(), restored.getRevisionNo());
        Map<String, Object> out = summary(restored, namesFor(
                restored.getCreatedBy() == null ? Set.of() : Set.of(restored.getCreatedBy())));
        out.put("contentBody", restored.getContentBody());
        return out;
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private AuditPolicyRevision take(AuditPolicy policy, String source, String body,
                                     Long aiInteractionId, String note) {
        String html = body == null ? "" : body;
        String hash = sha256(html);
        Optional<AuditPolicyRevision> latest = revisionRepository.findTopByPolicyIdOrderByRevisionNoDesc(policy.getId());
        if (latest.isPresent() && hash.equals(latest.get().getContentHash())) {
            return latest.get();      // unchanged since the last snapshot
        }
        AuditPolicyRevision r = AuditPolicyRevision.builder()
                .policyId(policy.getId())
                .tenantId(policy.getTenantId())
                .revisionNo(latest.map(l -> l.getRevisionNo() + 1).orElse(1))
                .policyVersion(policy.getVersion())
                .source(source)
                .contentBody(html)
                .contentHash(hash)
                .wordCount(words(html))
                .aiInteractionId(aiInteractionId)
                .note(note == null ? null : note.length() > 500 ? note.substring(0, 500) : note)
                .createdBy(utilityService.getLoggedInDataContext().getId())
                .build();
        return revisionRepository.save(r);
    }

    private AuditPolicy load(Long policyId) {
        return policyRepository.findById(policyId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditPolicy", policyId));
    }

    /** Same rule as AuditPolicyController.requireReadablePolicy. */
    private void requireReadable(AuditPolicy p) {
        if (utilityService.isSystemUser() || p.getTenantId() == null) return;
        if (Objects.equals(p.getTenantId(), utilityService.getLoggedInDataContext().getTenantId())) return;
        throw new BusinessException("POLICY_ACCESS_DENIED",
                "You can only work with policies belonging to your organisation", HttpStatus.FORBIDDEN);
    }

    /** Same rule as AuditPolicyController.requireOwnedPolicy. */
    private void requireOwned(AuditPolicy p) {
        if (utilityService.isSystemUser()) return;
        if (Objects.equals(p.getTenantId(), utilityService.getLoggedInDataContext().getTenantId())) return;
        throw new BusinessException("POLICY_ACCESS_DENIED",
                p.getTenantId() == null
                        ? "Global policies are maintained by the platform and cannot be edited"
                        : "You can only work with policies belonging to your organisation",
                HttpStatus.FORBIDDEN);
    }

    private Map<String, Object> summary(AuditPolicyRevision r, Map<Long, String> names) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("revisionNo", r.getRevisionNo());
        m.put("policyVersion", r.getPolicyVersion());
        m.put("source", r.getSource());
        m.put("wordCount", r.getWordCount());
        m.put("note", r.getNote());
        m.put("aiInteractionId", r.getAiInteractionId());
        m.put("createdBy", r.getCreatedBy());
        m.put("createdByName", r.getCreatedBy() == null ? null : names.get(r.getCreatedBy()));
        m.put("createdAt", r.getCreatedAt());
        return m;
    }

    private Map<Long, String> namesFor(Set<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        Map<Long, String> out = new HashMap<>();
        for (User u : userRepository.findAllById(ids)) {
            String n = u.getFullName();
            out.put(u.getId(), n == null || n.isBlank() ? u.getEmail() : n);
        }
        return out;
    }

    private static int words(String html) {
        String text = html.replaceAll("<[^>]+>", " ").replaceAll("&nbsp;", " ").trim();
        return text.isEmpty() ? 0 : text.split("\\s+").length;
    }

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
