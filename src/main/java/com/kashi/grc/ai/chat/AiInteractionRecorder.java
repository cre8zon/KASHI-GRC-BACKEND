package com.kashi.grc.ai.chat;

import com.kashi.grc.ai.domain.AiEnums.InteractionStatus;
import com.kashi.grc.ai.domain.AiEnums.ProviderType;
import com.kashi.grc.ai.domain.AiEnums.TaskType;
import com.kashi.grc.ai.domain.AiInteraction;
import com.kashi.grc.ai.guardrail.GuardrailException;
import com.kashi.grc.ai.repository.AiInteractionRepository;
import com.kashi.grc.ai.usage.AiUsageService;
import com.kashi.grc.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes failure and refusal rows in their own transaction.
 *
 * ── WHY THIS IS A SEPARATE BEAN ──────────────────────────────────────────────
 * This started life as two protected methods on AiChatService, and it did not
 * work. Spring's @Transactional is proxy-based: a call from one method of a bean
 * to another method of the SAME bean bypasses the proxy entirely, so
 * REQUIRES_NEW was silently ignored and the audit row rolled back with the
 * caller's transaction — the exact behaviour it was written to prevent, failing
 * silently in exactly the scenario it was meant to cover.
 *
 * Self-invocation is the most common way REQUIRES_NEW quietly does nothing.
 * The fix is a real bean boundary, so the call actually goes through the proxy.
 *
 * ── WHY THE AUDIT ROW MUST SURVIVE THE ROLLBACK ──────────────────────────────
 * A generation that failed is precisely the one somebody will ask about, and a
 * provider that returned an error still billed for the attempt. Losing that row
 * because the surrounding business transaction unwound would leave you unable to
 * explain either the failure or the invoice.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiInteractionRecorder {

    private final AiInteractionRepository interactionRepository;
    private final AiUsageService          usageService;

    /** Everything needed to write a failure row without dragging in AiCall. */
    public record FailureRecord(
            TaskType taskType, String pipelineName, Integer stepIndex, String stepName,
            String templateKey, Integer templateVersion, ProviderType provider, String providerKey, String model,
            String prompt, String inputVariables, String inputHash,
            String entityType, Long entityId, String correlationId, Long parentInteractionId,
            Long userId, Long tenantId, boolean evalRun, long latencyMs
    ) {}

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long recordFailure(FailureRecord r, RuntimeException e) {
        try {
            AiInteraction row = interactionRepository.save(AiInteraction.builder()
                    .taskType(r.taskType()).pipelineName(r.pipelineName())
                    .stepIndex(r.stepIndex()).stepName(r.stepName())
                    .promptTemplateKey(r.templateKey()).promptTemplateVersion(r.templateVersion())
                    .provider(r.provider()).providerKey(r.providerKey()).model(r.model())
                    .renderedPrompt(r.prompt()).inputVariables(r.inputVariables()).inputHash(r.inputHash())
                    .status(e instanceof GuardrailException ? InteractionStatus.BLOCKED : InteractionStatus.FAILED)
                    .errorCode(e instanceof BusinessException be ? be.getErrorCode() : "AI_ERROR")
                    .errorMessage(truncate(e.getMessage(), 2000))
                    .latencyMs(r.latencyMs())
                    .entityType(r.entityType()).entityId(r.entityId())
                    .correlationId(r.correlationId()).parentInteractionId(r.parentInteractionId())
                    .triggeredByUserId(r.userId()).evalRun(r.evalRun())
                    .tenantId(r.tenantId())
                    .build());
            usageService.record(r.tenantId(), 0, 0, 0, 0L, true, false);
            return row.getId();
        } catch (Exception ignored) {
            // Bookkeeping must never mask the real error the caller is about to throw.
            log.error("[AI-CHAT] could not persist failure row for {}", r.templateKey());
            return null;
        }
    }

    /** A guardrail refusal: the system working, not failing. Distinct status by design. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordBlocked(TaskType taskType, String templateKey, String errorCode,
                              String entityType, Long entityId, String correlationId,
                              Long userId, Long tenantId) {
        try {
            interactionRepository.save(AiInteraction.builder()
                    .taskType(taskType).promptTemplateKey(templateKey)
                    .status(InteractionStatus.BLOCKED).errorCode(errorCode)
                    .entityType(entityType).entityId(entityId)
                    .correlationId(correlationId).triggeredByUserId(userId)
                    .tenantId(tenantId).build());
            usageService.record(tenantId, 0, 0, 0, 0L, false, true);
        } catch (Exception ignored) {
            log.error("[AI-CHAT] could not persist blocked row for {}", templateKey);
        }
    }

    private static String truncate(String s, int n) {
        if (s == null) return null;
        return s.length() <= n ? s : s.substring(0, n);
    }
}