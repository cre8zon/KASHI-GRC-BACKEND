package com.kashi.grc.ai.rag;

import com.kashi.grc.ai.config.AiProperties;
import com.kashi.grc.ai.domain.AiEnums.ChunkSourceType;
import com.kashi.grc.ai.policy.PolicyTemplatePlaceholders;
import com.kashi.grc.ai.repository.AiDocumentChunkRepository;
import com.kashi.grc.audit.domain.AuditPolicy;
import com.kashi.grc.audit.repository.AuditPolicyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Self-healing sweep that keeps the corpus in step with the policy library.
 *
 * ── WHY A SWEEP AND NOT JUST HOOKS ───────────────────────────────────────────
 * PolicyCorpusHook covers the normal save path. It does not cover
 * AuditPolicyBulkAdoptService.adoptOne(), which calls policyRepository.save()
 * directly — so a tenant bulk-adopting the platform library with "approve
 * immediately" gets APPROVED policies that retrieval cannot see. Their own
 * adopted set, the single most relevant corpus they have, would be invisible.
 *
 * The lesson generalises: any write path that reaches the repository without
 * going through a service can bypass a hook, and new ones appear over time.
 * Hooks are the fast path; this sweep is the correctness guarantee, and it
 * catches paths neither of us has thought of yet.
 *
 * ── WHY NOT JUST PATCH adoptOne ──────────────────────────────────────────────
 * Do both. The one-line hook call gives immediate indexing so a tenant who
 * adopts and drafts in the same session gets grounded output. The sweep means
 * a missed hook degrades to "indexed within the hour" rather than "silently
 * never indexed". See the note at the bottom for the exact patch.
 *
 * ── CHEAP BY CONSTRUCTION ────────────────────────────────────────────────────
 * Content hashing means an unchanged policy costs one comparison and no
 * embedding call. A sweep over a library where nothing changed is close to free,
 * which is what makes hourly defensible.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PolicyCorpusReconciler {

    private final AuditPolicyRepository       policyRepository;
    private final AiDocumentChunkRepository   chunkRepository;
    private final IngestionAsyncFacade        ingestionAsync;
    private final IngestionService            ingestionService;
    private final PolicyTemplatePlaceholders  placeholders;
    private final AiProperties                props;

    /**
     * Hourly. Deliberately not more often: bulk adoption is a rare, deliberate
     * act, and an unindexed policy for up to an hour costs a slightly less
     * grounded draft, not a wrong one.
     */
    @Scheduled(cron = "0 20 * * * *")
    public void hourlySweep() {
        if (!props.isEnabled()) return;
        reconcile();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> reconcile() {
        long started = System.currentTimeMillis();
        int queued = 0, retired = 0, unchanged = 0;
        String batchId = UUID.randomUUID().toString();

        for (AuditPolicy p : policyRepository.findAll()) {
            boolean approved = p.getStatus() == AuditPolicy.PolicyStatus.APPROVED;
            boolean hasBody  = p.getContentBody() != null && !p.getContentBody().isBlank();
            ChunkSourceType type = p.getTenantId() == null
                    ? ChunkSourceType.POLICY_TEMPLATE : ChunkSourceType.POLICY;

            // Not eligible: make sure it is not sitting in the index from an
            // earlier state. An unpublished policy that keeps grounding new
            // drafts is worse than one that was never indexed.
            if (!approved || !hasBody) {
                if (chunkRepository.countMatchingHash(type, p.getId(), "") >= 0
                        && !chunkRepository.findBySourceTypeAndSourceIdOrderByChunkIndexAsc(type, p.getId()).isEmpty()) {
                    ingestionService.retire(type, p.getId());
                    retired++;
                }
                continue;
            }

            // Eligible: compare against what is indexed. The hash is computed on
            // the SAME normalised text the hook would ingest, or every sweep
            // would see a mismatch and re-embed the entire library hourly.
            String normalised = placeholders.normaliseForCorpus(p.getContentBody());
            String plain = normalised.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
            String hash  = IngestionService.sha256(plain);

            if (chunkRepository.countMatchingHash(type, p.getId(), hash) > 0) { unchanged++; continue; }

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("status",        String.valueOf(p.getStatus()));
            meta.put("policyRef",     String.valueOf(p.getPolicyRef()));
            meta.put("policyVersion", String.valueOf(p.getVersion()));
            meta.put("frameworkRefs", String.valueOf(p.getFrameworkRefs()));
            meta.put("controlTags",   String.valueOf(p.getControlTags()));

            ingestionAsync.ingest(new IngestionService.IngestRequest(
                    type, p.getId(),
                    (p.getPolicyRef() == null ? "" : p.getPolicyRef() + " ") + p.getTitle()
                            + (p.getVersion() == null ? "" : " v" + p.getVersion()),
                    normalised, true, p.getTenantId(), null, batchId, meta));
            queued++;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("queued", queued);
        out.put("retired", retired);
        out.put("unchanged", unchanged);
        out.put("batchId", batchId);
        out.put("durationMs", System.currentTimeMillis() - started);

        if (queued > 0 || retired > 0) {
            log.info("[AI-CORPUS-SWEEP] queued={} retired={} unchanged={} batch={}",
                    queued, retired, unchanged, batchId);
        } else {
            log.debug("[AI-CORPUS-SWEEP] nothing to do ({} unchanged)", unchanged);
        }
        return out;
    }

    /*
     * ── OPTIONAL ONE-LINE PATCH TO AuditPolicyBulkAdoptService ───────────────
     *
     * In adoptOne(), immediately after `policyRepository.save(copy)`:
     *
     *     policyCorpusHook.onPolicySaved(copy);
     *
     * plus the field:
     *
     *     private final PolicyCorpusHook policyCorpusHook;
     *
     * It is fire-and-forget on the AI executor and cannot fail the adoption —
     * see IngestionAsyncFacade. Without it everything still works, just up to
     * an hour later. With it, a tenant who adopts and immediately drafts gets
     * output grounded in their own newly adopted policies, which is exactly the
     * moment the grounding matters most.
     */
}
