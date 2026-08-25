package com.kashi.grc.audit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashi.grc.common.config.multitenancy.TenantContext;
import com.kashi.grc.common.kafka.KafkaEventEnvelope;
import com.kashi.grc.common.kafka.KafkaTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumer for kashigrc.audit.policy-bulk-adopt-requested.
 *
 * Adopting the whole platform library is ~39 policies, each writing a copy plus
 * its mappings plus an exclusion row. At the round-trip latency this deployment
 * actually has (~250ms — a trivial notification count takes a second), that is
 * minutes of work, well past any request timeout. Same reasoning that moved
 * engagement provisioning off the request thread.
 *
 * ── IDEMPOTENCY ─────────────────────────────────────────────────────────────
 * No new table and no status column: adoptAll SKIPS any platform policy this
 * tenant has already adopted, checked per policy via
 * countByPreviousVersionIdAndTenantId. A redelivered message therefore adopts
 * nothing the first delivery already did — it re-runs as a no-op, or picks up
 * whatever was added in between, which is the behaviour you want either way.
 *
 * ── WHY THIS DOES NOT RE-THROW ──────────────────────────────────────────────
 * Deliberately different from AuditEngagementSnapshotConsumer, which re-throws
 * so the container retries and eventually DLTs.
 *
 * A partial bulk adopt is not a failure to retry — it is a result. adoptAll
 * already isolates each policy in its own transaction and reports created /
 * skipped / failed. Re-throwing would replay the whole batch to re-attempt the
 * one policy that failed, and since the successful ones are now skipped, the
 * retry would report "created 0, skipped 38, failed 1" over and over until it
 * hit the DLT. Logging the outcome once is the honest answer; the failures are
 * named and the user can re-run.
 *
 * A genuine infrastructure failure (database down) throws before adoptAll gets
 * anywhere, and that DOES propagate.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "kashi.kafka.enabled", havingValue = "true")
public class AuditPolicyBulkAdoptConsumer {

    private final AuditPolicyBulkAdoptService bulkAdoptService;
    private final ObjectMapper                objectMapper;

    private record RequestedPayload(Long tenantId, Long actorUserId,
                                    boolean approve, String ownerTeam, Long ownerId) {}

    @KafkaListener(
            topics = KafkaTopics.AUDIT_POLICY_BULK_ADOPT_REQUESTED,
            groupId = "kashigrc-audit-policy",
            containerFactory = "kafkaListenerContainerFactory")
    public void onBulkAdoptRequested(KafkaEventEnvelope envelope) {
        try {
            TenantContext.setCurrentTenant(envelope.getTenantId());   // FIRST — every
            // repository read
            // below is scoped
            RequestedPayload p = envelope.payloadAs(RequestedPayload.class, objectMapper);

            var result = bulkAdoptService.adoptAll(
                    envelope.getTenantId(), p.actorUserId(), p.approve(),
                    p.ownerTeam(), p.ownerId());

            log.info("[POLICY-BULK-CONSUMER] Complete | tenantId={} created={} skipped={} failed={} | eventId={}",
                    envelope.getTenantId(), result.created(), result.skipped(),
                    result.failed(), envelope.getEventId());

            if (result.failed() > 0) {
                // Named individually so a partial run is diagnosable rather than
                // a number. These are the ones to delete and retry.
                log.warn("[POLICY-BULK-CONSUMER] Failures | eventId={} | {}",
                        envelope.getEventId(), String.join(" ; ", result.problems()));
            }

        } catch (Exception e) {
            log.error("[POLICY-BULK-CONSUMER] Failed before adoption could run | eventId={} — {}",
                    envelope.getEventId(), e.toString());
            throw e;   // infrastructure failure — retry / DLT is correct here
        } finally {
            TenantContext.clear();
        }
    }
}