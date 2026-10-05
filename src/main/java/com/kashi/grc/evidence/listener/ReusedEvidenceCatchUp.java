package com.kashi.grc.evidence.listener;

import com.kashi.grc.evidence.service.EvidenceReuseEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * On startup, accepts reused (KashiLink) links that were created while reuse
 * still needed a review (EvidenceReuseEngine.acceptPendingReuse), and retires
 * integration failures a later run of the same check has replaced
 * (supersedeStaleFailures). Both find nothing once caught up. Never stops the application starting.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReusedEvidenceCatchUp {

    private final EvidenceReuseEngine engine;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            engine.acceptPendingReuse();
        } catch (RuntimeException e) {
            log.warn("[EVIDENCE-REUSE] Startup catch-up of pending reused links failed (non-fatal): {}", e.getMessage());
        }
        try {
            engine.supersedeStaleFailures();
        } catch (RuntimeException e) {
            log.warn("[EVIDENCE-REUSE] Startup catch-up of superseded integration failures failed (non-fatal): {}", e.getMessage());
        }
    }
}
