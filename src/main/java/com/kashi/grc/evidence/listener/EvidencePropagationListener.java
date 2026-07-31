package com.kashi.grc.evidence.listener;

import com.kashi.grc.evidence.event.EvidenceRecordCreatedEvent;
import com.kashi.grc.evidence.service.EvidenceReuseEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * KashiLink — drives tag propagation once the evidence record is committed.
 *
 * @TransactionalEventListener(AFTER_COMMIT) — the record is guaranteed visible.
 * @Async                                    — never blocks the HTTP response.
 * @Transactional(REQUIRES_NEW)              — AFTER_COMMIT runs outside the
 *                                             original transaction, so the
 *                                             listener needs its own.
 *
 * Same shape as GuardEvaluationListener. If propagation throws, it is logged
 * and swallowed: a failed auto-link must never roll back or fail the upload
 * that produced the evidence.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvidencePropagationListener {

    private final EvidenceReuseEngine engine;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onEvidenceCreated(EvidenceRecordCreatedEvent event) {
        if (event.controlTag() == null || event.controlTag().isBlank()) {
            log.debug("[KASHILINK] recordId={} has no tag — nothing to propagate",
                    event.evidenceRecordId());
            return;
        }
        try {
            int links = engine.propagate(event.evidenceRecordId(),
                    event.automated() && event.automationPass());
            log.info("[KASHILINK] Propagated | recordId={} | tag={} | tenantId={} | newLinks={}",
                    event.evidenceRecordId(), event.controlTag(), event.tenantId(), links);

            if (links == 0) {
                // Not an error — but it is the signature of tag drift. Surfaced
                // so it is greppable and countable rather than invisible.
                log.warn("[KASHILINK] ZERO MATCHES | recordId={} | tag='{}' | tenantId={} — "
                                + "no instance carries this tag. Check for tag drift.",
                        event.evidenceRecordId(), event.controlTag(), event.tenantId());
            }
        } catch (Exception e) {
            log.error("[KASHILINK] Propagation failed | recordId={} | tag={}: {}",
                    event.evidenceRecordId(), event.controlTag(), e.getMessage(), e);
        }
    }
}