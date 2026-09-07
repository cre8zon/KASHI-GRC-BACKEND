package com.kashi.grc.ai.chat;

import com.kashi.grc.ai.domain.AiInteraction;
import com.kashi.grc.ai.repository.AiInteractionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the ai_interactions audit row in its OWN transaction.
 *
 * Two problems this solves, both of which showed up the first time a policy was
 * drafted successfully:
 *
 * 1. READ-ONLY CALLERS. PolicyAiService.suggestMetadata is
 *    @Transactional(readOnly = true) — correct, since suggesting metadata
 *    changes nothing the caller owns. But AiChatService.execute joined that
 *    transaction to save its audit row, and MySQL refused:
 *
 *      Connection is read-only. Queries leading to data modification are not
 *      allowed [insert into ai_interactions ...]
 *
 *    The model call had already succeeded and been paid for; the request then
 *    failed with a 500 and the user saw "An unexpected error occurred".
 *
 * 2. ROLLBACK. Even with a writable caller, joining its transaction means the
 *    audit row disappears if the caller later rolls back — losing the record of
 *    a call that was really made and really billed. Token spend and guardrail
 *    triggers must be recorded whether or not the surrounding work commits.
 *
 * REQUIRES_NEW suspends the caller's transaction and runs on a separate
 * connection, so neither its read-only flag nor its outcome applies here.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiInteractionAuditWriter {

    private final AiInteractionRepository interactionRepository;

    /**
     * Never let an audit failure fail the user's request.
     *
     * The caller has already received a usable answer by this point. Losing one
     * log row is bad; turning a successful, already-paid-for model call into a
     * 500 is worse. The failure is logged loudly enough to notice.
     *
     * Returns null when the write fails, so callers must tolerate that.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AiInteraction save(AiInteraction interaction) {
        try {
            return interactionRepository.save(interaction);
        } catch (Exception e) {
            log.error("[AI-CHAT] audit row NOT saved — usage and cost for this call are unrecorded: {}",
                    e.getMessage(), e);
            return null;
        }
    }
}