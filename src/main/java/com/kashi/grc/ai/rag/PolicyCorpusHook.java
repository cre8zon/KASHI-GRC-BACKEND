package com.kashi.grc.ai.rag;

import com.kashi.grc.ai.config.AiProperties;
import com.kashi.grc.ai.domain.AiEnums.ChunkSourceType;
import com.kashi.grc.ai.policy.PolicyTemplatePlaceholders;
import com.kashi.grc.audit.domain.AuditPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keeps the retrieval corpus in step with the policy library.
 *
 * ── WIRING (two lines in AuditPolicyService) ─────────────────────────────────
 * After a policy is saved or its status changes:
 *
 *     policyCorpusHook.onPolicySaved(policy);
 *
 * And when it is deleted:
 *
 *     policyCorpusHook.onPolicyDeleted(policy);
 *
 * Both are fire-and-forget on the AI executor. Neither can fail a save — see
 * IngestionAsyncFacade for why ingestion must never sit on the critical
 * path of a user action.
 *
 * ── WHY ONLY APPROVED POLICIES ARE INDEXED ───────────────────────────────────
 * A DRAFT is somebody's unfinished thinking. Grounding a new generation in it
 * would propagate half-formed text into other documents and, worse, would let a
 * policy that was never approved influence one that is about to be. The corpus
 * should contain what the organisation stands behind.
 *
 * DEPRECATED policies are retired rather than deleted: the chunks stay for
 * provenance on generations that already cited them, but they stop grounding new
 * work. Superseded requirements leaking into a fresh policy is exactly the
 * failure a compliance team would never forgive.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PolicyCorpusHook {

    private final IngestionService     ingestionService;
    private final IngestionAsyncFacade ingestionAsync;
    private final AiProperties     props;
    private final PolicyTemplatePlaceholders placeholders;

    public void onPolicySaved(AuditPolicy policy) {
        if (!props.isEnabled() || policy == null) return;
        if (policy.getContentBody() == null || policy.getContentBody().isBlank()) return;

        String status = String.valueOf(policy.getStatus());

        if ("DEPRECATED".equals(status)) {
            ingestionService.retire(sourceTypeFor(policy), policy.getId());
            return;
        }
        /*
         * Only APPROVED is indexed. The lifecycle is now
         * DRAFT -> UNDER_REVIEW -> APPROVED -> DEPRECATED, with unpublish able
         * to send an APPROVED policy back to DRAFT.
         *
         * UNDER_REVIEW is deliberately excluded alongside DRAFT: a policy in
         * review is being argued about, and grounding a new generation in text
         * that a reviewer is midway through rejecting is exactly the failure
         * this rule exists to prevent.
         *
         * An unpublish therefore has to REMOVE the policy from the corpus, not
         * merely stop refreshing it — otherwise the last approved version keeps
         * grounding new work after it was withdrawn.
         */
        if (!"APPROVED".equals(status)) {
            log.debug("[AI-CORPUS] policy {} is {} — retiring from retrieval", policy.getId(), status);
            ingestionService.retire(sourceTypeFor(policy), policy.getId());
            return;
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("status",        status);
        metadata.put("policyRef",     String.valueOf(policy.getPolicyRef()));
        metadata.put("policyVersion", String.valueOf(policy.getVersion()));
        metadata.put("frameworkRefs", String.valueOf(policy.getFrameworkRefs()));
        metadata.put("controlTags",   String.valueOf(policy.getControlTags()));

        ingestionAsync.ingest(new IngestionService.IngestRequest(
                sourceTypeFor(policy),
                policy.getId(),
                buildRef(policy),
                /*
                 * Mustache placeholders are rewritten to [[SQUARE]] form before
                 * indexing. Your POL-01..POL-26 templates carry {{company_name}}
                 * 72 times and {{policy_owner}} 75 times; left as-is, retrieval
                 * teaches the model that mustache is how a finished policy looks
                 * and it copies the syntax into fresh drafts. See
                 * PolicyTemplatePlaceholders.
                 */
                placeholders.normaliseForCorpus(policy.getContentBody()),
                true,                        // TipTap emits HTML
                policy.getTenantId(),
                policy.getCreatedBy(),
                null,
                metadata));
    }

    public void onPolicyDeleted(AuditPolicy policy) {
        if (!props.isEnabled() || policy == null) return;
        ingestionService.purge(sourceTypeFor(policy), policy.getId());
    }

    /**
     * A global policy is platform library material every tenant may retrieve;
     * a tenant policy is that customer's alone. The distinction is what the
     * Qdrant scope filter enforces, so it has to be set correctly here.
     */
    private ChunkSourceType sourceTypeFor(AuditPolicy p) {
        return p.getTenantId() == null ? ChunkSourceType.POLICY_TEMPLATE : ChunkSourceType.POLICY;
    }

    /** Citation string shown in the provenance panel. */
    private String buildRef(AuditPolicy p) {
        StringBuilder sb = new StringBuilder();
        if (p.getPolicyRef() != null && !p.getPolicyRef().isBlank()) sb.append(p.getPolicyRef()).append(' ');
        sb.append(p.getTitle() == null ? "Untitled policy" : p.getTitle());
        if (p.getVersion() != null) sb.append(" v").append(p.getVersion());
        return sb.toString();
    }
}