package com.kashi.grc.guard.listener;

import com.kashi.grc.guard.event.ModuleSubmitEvent;
import com.kashi.grc.guard.service.GuardEvaluator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * GuardEvaluationListener — generic bulk guard sweep for any module.
 *
 * Listens for ModuleSubmitEvent published by any module controller on
 * "submit" actions. Iterates every QuestionContext and calls GuardEvaluator
 * for each — creating action items + notifications for unanswered/flagged
 * questions automatically.
 *
 * ADDING A NEW MODULE:
 *   Zero changes here. Just publish a ModuleSubmitEvent from your controller.
 *
 * @Async — runs after the caller's transaction commits, never blocks HTTP response.
 * @TransactionalEventListener(AFTER_COMMIT) — guarantees the submit data is
 *   persisted before guard evaluation reads responses from DB.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GuardEvaluationListener {

    private final GuardEvaluator guardEvaluator;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onModuleSubmit(ModuleSubmitEvent event) {
        if (event.questions() == null || event.questions().isEmpty()) {
            log.debug("[GUARD-SWEEP] No questions in submit event for {}:{} — skipping",
                    event.entityType(), event.entityId());
            return;
        }

        log.info("[GUARD-SWEEP] Starting bulk sweep | entityType={} | entityId={} | questions={} | task={}",
                event.entityType(), event.entityId(),
                event.questions().size(), event.taskId());

        int evaluated = 0;
        for (ModuleSubmitEvent.QuestionContext ctx : event.questions()) {
            try {
                // Each evaluate() call is already @Async + @Transactional inside GuardEvaluator.
                // We call it synchronously here since we're already in an async context —
                // this lets us log a clean completion count at the end.
                guardEvaluator.evaluate(ctx, event.tenantId());
                evaluated++;
            } catch (Exception e) {
                log.warn("[GUARD-SWEEP] Failed for questionInstanceId={}: {}",
                        ctx.questionInstanceId(), e.getMessage());
            }
        }

        log.info("[GUARD-SWEEP] Complete | entityType={} | entityId={} | evaluated={}/{}",
                event.entityType(), event.entityId(), evaluated, event.questions().size());
    }
}