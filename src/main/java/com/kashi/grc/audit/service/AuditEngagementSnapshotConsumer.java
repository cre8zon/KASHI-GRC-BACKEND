package com.kashi.grc.audit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashi.grc.audit.domain.AuditEngagement;
import com.kashi.grc.audit.repository.AuditEngagementRepository;
import com.kashi.grc.common.config.multitenancy.TenantContext;
import com.kashi.grc.common.kafka.KafkaEventEnvelope;
import com.kashi.grc.common.kafka.KafkaTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumer for kashigrc.audit.engagement-snapshot-requested — does the
 * actual template-snapshot + workflow-start work that
 * AuditEngagementService.create() used to run inline, blocking whatever
 * request thread was creating the engagement (a single POST /engagements
 * call, or, worse, the project-instance cascade — one engagement per
 * planned template, in a loop, on the same request).
 *
 * IDEMPOTENCY: no new table — reuses AuditEngagement.snapshotStatus as the
 * natural de-duplication check, same pattern as ExecuteAssessmentConsumer's
 * StepInstance.status check. create() sets it to PROVISIONING right before
 * publishing; if a redelivered message arrives after this consumer (or a
 * manual retry) already flipped it to READY or FAILED, this is a no-op.
 *
 * RETRYABLE VS NOT: completeEngagementProvisioning() itself distinguishes —
 * BusinessException/ResourceNotFoundException (bad/missing template data)
 * mark the engagement FAILED and rethrow; any other exception (DB hiccup,
 * etc.) is a genuine transient failure. Either way this consumer re-throws
 * so the container's retry/backoff/DLT handling engages — the FAILED status
 * write survives that rethrow because it runs in its own REQUIRES_NEW
 * transaction (see AuditEngagementService.markSnapshotFailed).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "kashi.kafka.enabled", havingValue = "true")
public class AuditEngagementSnapshotConsumer {

    private final AuditEngagementRepository engagementRepository;
    private final AuditEngagementService    auditEngagementService;
    private final ObjectMapper              objectMapper;

    private record RequestedPayload(Long engagementId, Long templateId, Long createdBy,
                                    boolean startWorkflow, Long workflowId) {}

    @KafkaListener(
            topics = KafkaTopics.AUDIT_ENGAGEMENT_SNAPSHOT_REQUESTED,
            groupId = "kashigrc-audit-engagement",
            containerFactory = "kafkaListenerContainerFactory")
    public void onEngagementSnapshotRequested(KafkaEventEnvelope envelope) {
        try {
            TenantContext.setCurrentTenant(envelope.getTenantId());   // FIRST

            RequestedPayload p = envelope.payloadAs(RequestedPayload.class, objectMapper);

            AuditEngagement engagement = engagementRepository.findById(p.engagementId()).orElse(null);
            if (engagement == null) {
                log.warn("[AUDIT-ENGAGEMENT-SNAPSHOT-CONSUMER] Engagement {} not found — eventId={}, nothing to do",
                        p.engagementId(), envelope.getEventId());
                return;
            }
            // Idempotency — see class javadoc.
            if (!"PROVISIONING".equals(engagement.getSnapshotStatus())) {
                log.info("[AUDIT-ENGAGEMENT-SNAPSHOT-CONSUMER] Engagement {} already snapshotStatus={} — " +
                                "skipping duplicate delivery | eventId={}",
                        p.engagementId(), engagement.getSnapshotStatus(), envelope.getEventId());
                return;
            }

            Long overrideWorkflowId = (p.workflowId() != null && p.workflowId() > 0) ? p.workflowId() : null;

            auditEngagementService.completeEngagementProvisioning(
                    engagement, p.templateId(), envelope.getTenantId(),
                    p.startWorkflow(), overrideWorkflowId, p.createdBy());

            log.info("[AUDIT-ENGAGEMENT-SNAPSHOT-CONSUMER] Provisioning complete | engagementId={} | eventId={}",
                    p.engagementId(), envelope.getEventId());

        } catch (Exception e) {
            log.error("[AUDIT-ENGAGEMENT-SNAPSHOT-CONSUMER] Failed | eventId={} — {}",
                    envelope.getEventId(), e.toString());
            throw e; // MUST re-throw → retries → DLT
        } finally {
            TenantContext.clear();
        }
    }
}