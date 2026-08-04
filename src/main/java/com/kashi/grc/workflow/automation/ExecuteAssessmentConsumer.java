package com.kashi.grc.workflow.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashi.grc.common.config.multitenancy.TenantContext;
import com.kashi.grc.common.kafka.KafkaEventEnvelope;
import com.kashi.grc.common.kafka.KafkaTopics;
import com.kashi.grc.workflow.domain.StepInstance;
import com.kashi.grc.workflow.domain.WorkflowInstance;
import com.kashi.grc.workflow.domain.WorkflowStep;
import com.kashi.grc.workflow.enums.StepStatus;
import com.kashi.grc.workflow.repository.StepInstanceRepository;
import com.kashi.grc.workflow.repository.WorkflowInstanceRepository;
import com.kashi.grc.workflow.repository.WorkflowStepRepository;
import com.kashi.grc.workflow.service.WorkflowEngineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Consumer for kashigrc.assessment.execute-requested — does the actual
 * EXECUTE_ASSESSMENT provisioning work that WorkflowEngineService.
 * createStepInstance() used to run inline on whatever request thread
 * started the step (see the EXCEPTION comment there for why this one
 * handler, specifically, is worth taking off the request thread).
 *
 * IDEMPOTENCY: no new table — reuses StepInstance.status as the natural
 * de-duplication check. createStepInstance() sets the step to IN_PROGRESS
 * right before publishing; if a redelivered message arrives after this
 * consumer (or the 5-minute retryStuckSystemSteps sweep) already advanced
 * the step past IN_PROGRESS, this is a no-op. This mirrors the "natural DB
 * constraint" idempotency option from the team's own Kafka integration
 * process doc, rather than adding an EmailLog-style tracking table for a
 * single event type.
 *
 * RETRYABLE VS NOT:
 *   - AutomatedActionRegistry.dispatch() returning false/empty means
 *     ExecuteAssessmentAction hit a KNOWN logical problem (vendor not
 *     found, no risk-template mapping, etc.) — not retryable, retrying
 *     the same inputs would fail identically. Logged, left IN_PROGRESS;
 *     the existing 5-minute retryStuckSystemSteps sweep (unchanged,
 *     already existed before Kafka) will keep trying it synchronously,
 *     the same safety net it already provided pre-Kafka.
 *   - Any THROWN exception (DB hiccup, etc.) is a genuine transient
 *     failure — re-thrown so the container's retry/backoff/DLT handling
 *     engages, same as every other consumer in this app.
 *
 * TENANT ISOLATION: TenantContext set from the envelope FIRST, cleared in
 * finally — listener threads are pooled and reused; leaking tenant context
 * across messages is a cross-tenant data exposure, not just a bug.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "kashi.kafka.enabled", havingValue = "true")
public class ExecuteAssessmentConsumer {

    private final StepInstanceRepository      stepInstanceRepository;
    private final WorkflowInstanceRepository  instanceRepository;
    private final WorkflowStepRepository      stepRepository;
    private final AutomatedActionRegistry     automatedActionRegistry;
    private final WorkflowEngineService       workflowEngineService;
    private final ObjectMapper                objectMapper;

    private record RequestedPayload(Long workflowInstanceId, Long stepInstanceId) {}

    @KafkaListener(
            topics = KafkaTopics.ASSESSMENT_EXECUTE_REQUESTED,
            groupId = "kashigrc-assessment",
            containerFactory = "kafkaListenerContainerFactory")
    public void onExecuteAssessmentRequested(KafkaEventEnvelope envelope) {
        try {
            TenantContext.setCurrentTenant(envelope.getTenantId());   // FIRST

            RequestedPayload p = envelope.payloadAs(RequestedPayload.class, objectMapper);

            StepInstance si = stepInstanceRepository.findById(p.stepInstanceId()).orElse(null);
            if (si == null) {
                log.warn("[EXECUTE_ASSESSMENT-CONSUMER] StepInstance {} not found — eventId={}, nothing to do",
                        p.stepInstanceId(), envelope.getEventId());
                return;
            }
            // Idempotency — see class javadoc.
            if (si.getStatus() != StepStatus.IN_PROGRESS) {
                log.info("[EXECUTE_ASSESSMENT-CONSUMER] StepInstance {} already status={} — " +
                                "skipping duplicate delivery | eventId={}",
                        p.stepInstanceId(), si.getStatus(), envelope.getEventId());
                return;
            }

            WorkflowInstance instance = instanceRepository.findById(si.getWorkflowInstanceId())
                    .orElseThrow(() -> new com.kashi.grc.common.exception.ResourceNotFoundException(
                            "WorkflowInstance", si.getWorkflowInstanceId()));
            WorkflowStep step = stepRepository.findById(si.getStepId())
                    .orElseThrow(() -> new com.kashi.grc.common.exception.ResourceNotFoundException(
                            "WorkflowStep", si.getStepId()));

            AutomatedActionContext ctx = AutomatedActionContext.builder()
                    .workflowInstance(instance)
                    .step(step)
                    .stepInstance(si)
                    .tenantId(instance.getTenantId())
                    .initiatedBy(instance.getInitiatedBy())
                    .build();

            Optional<Boolean> result = automatedActionRegistry.dispatch("EXECUTE_ASSESSMENT", ctx);

            if (result.isPresent() && Boolean.TRUE.equals(result.get())) {
                workflowEngineService.completeSystemStepAndAdvance(si.getId(), instance.getInitiatedBy(),
                        "Automated action 'EXECUTE_ASSESSMENT' completed successfully (async via Kafka)");
                log.info("[EXECUTE_ASSESSMENT-CONSUMER] Completed and advanced | stepInstanceId={} | eventId={}",
                        si.getId(), envelope.getEventId());
            } else {
                log.warn("[EXECUTE_ASSESSMENT-CONSUMER] Handler returned false/empty — leaving IN_PROGRESS " +
                                "for the periodic retry sweep | stepInstanceId={} | eventId={}",
                        si.getId(), envelope.getEventId());
            }

        } catch (Exception e) {
            log.error("[EXECUTE_ASSESSMENT-CONSUMER] Failed | eventId={} — {}",
                    envelope.getEventId(), e.toString());
            throw e; // MUST re-throw → retries → DLT
        } finally {
            TenantContext.clear();
        }
    }
}