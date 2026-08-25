package com.kashi.grc.audit.service;

import com.kashi.grc.audit.domain.AuditPolicy;
import com.kashi.grc.audit.domain.AuditPolicyControlMapping;
import com.kashi.grc.audit.repository.AuditPolicyControlMappingRepository;
import com.kashi.grc.audit.repository.AuditPolicyRepository;
import com.kashi.grc.tenant.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Bulk adoption of the platform policy library into one tenant.
 *
 * ── WHY THIS IS A SERVICE AND NOT A LOOP IN THE CONTROLLER ──────────────────
 * The controller version called customisePolicy() on itself, which bypasses the
 * Spring proxy — the inner @Transactional was ignored and everything ran in one
 * transaction. A single failing policy marked it rollback-only, so all 39
 * adoptions vanished at commit time having already reported success.
 *
 * Here adoptOne() is REQUIRES_NEW, invoked across a bean boundary, so each
 * policy commits or rolls back on its own. One bad policy costs one policy.
 *
 * ── WHY IT DUPLICATES SOME OF customisePolicy ───────────────────────────────
 * It does, and that is a real cost — the exclusion behaviour in particular took
 * three attempts to get right and now exists twice. The alternative was calling
 * the controller from the consumer, which drags HTTP concerns (ResponseEntity,
 * the logged-in user context that does not exist on a Kafka thread) into a
 * background job. If this diverges, adoptOne is the copy to fix.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditPolicyBulkAdoptService {

    private final AuditPolicyRepository               policyRepository;
    private final AuditPolicyControlMappingRepository mappingRepository;
    private final TenantRepository                    tenantRepository;
    private final AuditLibraryCacheService            libraryCache;

    /** Result of one run, reported back through the job record. */
    public record AdoptResult(int created, int skipped, int failed, List<String> problems) {}

    /**
     * @param approve  true = copies land APPROVED, stamped with actorUserId and now.
     *                 Platform policies are pre-vetted, so this is a legitimate
     *                 choice — but it IS an approval and is recorded as one.
     * @param ownerTeam optional, stamped on every copy.
     */
    public AdoptResult adoptAll(Long tenantId, Long actorUserId, boolean approve,
                                String ownerTeam, Long ownerId) {
        // APPROVED only.
        //
        // Excluding DEPRECATED was not enough: it also swept up platform policies
        // still in DRAFT or UNDER_REVIEW — documents the platform has not
        // published — and adopted them into a tenant as though they were
        // finished. A tenant adopting "the platform library" means the part of it
        // that is actually published.
        List<AuditPolicy> globals = policyRepository.findAll().stream()
                .filter(p -> p.getTenantId() == null)
                .filter(p -> p.getStatus() == AuditPolicy.PolicyStatus.APPROVED)
                .toList();

        int created = 0, skipped = 0, failed = 0;
        List<String> problems = new java.util.ArrayList<>();

        for (AuditPolicy source : globals) {
            if (policyRepository.countByPreviousVersionIdAndTenantId(source.getId(), tenantId) > 0) {
                skipped++;                      // already adopted — re-runs pick up what is new
                continue;
            }
            try {
                adoptOne(source, tenantId, actorUserId, approve, ownerTeam, ownerId);
                created++;
            } catch (Exception ex) {
                failed++;
                problems.add(source.getPolicyRef() + ": " + ex.getMessage());
                log.warn("[POLICY-BULK] Adopt failed | policyId={} ref={} | {}",
                        source.getId(), source.getPolicyRef(), ex.getMessage());
            }
        }

        libraryCache.evictLibraryLists();
        log.info("[POLICY-BULK] Done | tenantId={} created={} skipped={} failed={} approved={}",
                tenantId, created, skipped, failed, approve);
        return new AdoptResult(created, skipped, failed, problems);
    }

    /**
     * REQUIRES_NEW: this policy's copy, its mappings and its exclusions commit
     * together or not at all, independently of every other policy in the run.
     * That is the whole reason this method exists separately.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void adoptOne(AuditPolicy source, Long tenantId, Long userId,
                         boolean approve, String ownerTeam, Long ownerId) {

        AuditPolicy copy = AuditPolicy.builder()
                .title(source.getTitle())
                .policyRef(uniqueRef(source.getPolicyRef(), tenantId))
                .description(source.getDescription())
                .version(1)
                .previousVersionId(source.getId())
                .contentType(source.getContentType())
                .contentBody(source.getContentBody())
                .externalUrl(source.getExternalUrl())
                .evidenceRecordId(source.getEvidenceRecordId())
                .status(approve ? AuditPolicy.PolicyStatus.APPROVED
                        : AuditPolicy.PolicyStatus.DRAFT)
                // The chosen owner, not whoever ran the adoption. Approving and
                // owning are different claims: one is an event, the other a
                // standing assignment that outlives this run.
                .ownerId(ownerId != null ? ownerId : userId)
                .ownerTeam(ownerTeam != null && !ownerTeam.isBlank()
                        ? ownerTeam.trim() : source.getOwnerTeam())
                .reviewFrequencyMonths(source.getReviewFrequencyMonths())
                .controlTags(source.getControlTags())
                .frameworkRefs(source.getFrameworkRefs())
                .createdBy(userId)
                .tenantId(tenantId)
                .build();

        if (approve) {
            // Recorded as a real approval — whoever pressed the button owns it.
            copy.setApprovedById(userId);
            copy.setApprovedAt(LocalDateTime.now());
        }
        policyRepository.save(copy);

        // Carry the platform mappings across as tenant rows, and exclude the
        // originals so an engagement does not snapshot the same document twice.
        for (AuditPolicyControlMapping m : mappingRepository.findByPolicyId(source.getId())) {
            if (m.getTenantId() != null) continue;         // another tenant's row

            AuditPolicyControlMapping mine = new AuditPolicyControlMapping();
            mine.setPolicyId(copy.getId());
            mine.setControlId(m.getControlId());
            mine.setMappingType(m.getMappingType());
            mine.setMappingNote(m.getMappingNote());
            mine.setTenantId(tenantId);
            mine.setCreatedBy(userId);
            mappingRepository.save(mine);

            boolean alreadyExcluded = mappingRepository
                    .findByControlIdAndTenantId(m.getControlId(), tenantId).stream()
                    .anyMatch(x -> java.util.Objects.equals(x.getPolicyId(), source.getId())
                            && x.getMappingType() == AuditPolicyControlMapping.MappingType.EXCLUDED);
            if (alreadyExcluded) continue;

            AuditPolicyControlMapping ex = new AuditPolicyControlMapping();
            ex.setPolicyId(source.getId());
            ex.setControlId(m.getControlId());
            ex.setMappingType(AuditPolicyControlMapping.MappingType.EXCLUDED);
            ex.setMappingNote("Superseded by " + copy.getPolicyRef());
            ex.setTenantId(tenantId);
            ex.setCreatedBy(userId);
            mappingRepository.save(ex);
        }
    }

    /** POL-03 → POL-03-META, with a numeric suffix if that is taken. */
    private String uniqueRef(String sourceRef, Long tenantId) {
        if (sourceRef == null || sourceRef.isBlank()) return sourceRef;
        String suffix = tenantRepository.findById(tenantId)
                .map(t -> t.getCode() != null && !t.getCode().isBlank()
                        ? t.getCode().toUpperCase().trim() : String.valueOf(tenantId))
                .orElse(String.valueOf(tenantId));

        String candidate = sourceRef + "-" + suffix;
        int n = 2;
        while (policyRepository.existsByPolicyRefAndTenantId(candidate, tenantId)) {
            candidate = sourceRef + "-" + suffix + "-" + n++;
        }
        return candidate;
    }
}