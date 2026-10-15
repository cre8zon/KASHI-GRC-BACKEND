package com.kashi.grc.billing.scheduler;

import com.kashi.grc.billing.service.InvoiceService;
import com.kashi.grc.billing.service.MrrSnapshotService;
import com.kashi.grc.billing.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * The two jobs billing cannot do without.
 *
 *   00:30 daily       lapsed-term sweep (replace / auto-renew / churn) and
 *                     PAST_DUE flagging from overdue invoices
 *   01:15 on the 1st  month-end MRR snapshot for the month that just closed
 *
 * The snapshot is the one that cannot be back-filled: a month it misses is a
 * month of retention history that does not exist. It is idempotent per tenant,
 * so the platform admin can re-run it from Billing → Snapshots after an outage.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BillingScheduler {

    private final SubscriptionService subscriptionService;
    private final InvoiceService      invoiceService;
    private final MrrSnapshotService  snapshotService;

    @Scheduled(cron = "${kashi.billing.sweep-cron:0 30 0 * * *}")
    public void dailySweep() {
        try {
            var counts = subscriptionService.sweepLapsed(LocalDate.now());
            int pastDue = invoiceService.flagPastDue();
            log.info("[BILLING] Daily sweep | {} | pastDueFlagged={}", counts, pastDue);
        } catch (Exception e) {
            log.error("[BILLING] Daily sweep failed", e);
        }
    }

    @Scheduled(cron = "${kashi.billing.snapshot-cron:0 15 1 1 * *}")
    public void monthEndSnapshot() {
        YearMonth closed = YearMonth.now().minusMonths(1);
        try {
            log.info("[BILLING] Month-end snapshot | {}", snapshotService.snapshot(closed));
        } catch (Exception e) {
            log.error("[BILLING] Month-end snapshot FAILED for {} — re-run it from Billing → Snapshots", closed, e);
        }
    }
}
