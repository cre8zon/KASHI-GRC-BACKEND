package com.kashi.grc.firmverification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Slf4j
@Component
@RequiredArgsConstructor
public class FirmVerificationScheduler {

    private final AuditFirmVerificationService service;

    /** 00:45 daily — expire lapsed verifications (flag off), remind 30 days ahead. */
    @Scheduled(cron = "${kashi.firm-verification.sweep-cron:0 45 0 * * *}")
    public void sweep() {
        try {
            log.info("[FIRM-VERIFY] Expiry sweep | {}", service.expirySweep(LocalDate.now()));
        } catch (Exception e) {
            log.error("[FIRM-VERIFY] Expiry sweep failed", e);
        }
    }
}
