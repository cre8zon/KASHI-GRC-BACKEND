package com.kashi.grc.audit.scheduler;

import com.kashi.grc.audit.domain.AuditEngagement;
import com.kashi.grc.audit.repository.AuditEngagementRepository;
import com.kashi.grc.audit.repository.AuditProjectInstanceRepository;
import com.kashi.grc.audit.service.AuditEngagementService;
import com.kashi.grc.common.config.multitenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Finishes engagements whose template snapshot never ran.
 *
 * ── WHY THIS EXISTS ────────────────────────────────────────────────────────
 * Creating an engagement with a template saves the row as PROVISIONING and
 * hands the snapshot (sections, controls, tests, policies) to Kafka. If that
 * message is lost — the broker was down when it was sent, the consumer gave
 * up and dead-lettered it, or (before the after-commit fix) the consumer ran
 * before the row was committed — nothing ever retried it. The engagement sat
 * at PROVISIONING with no controls, forever, and no user action could fix it.
 *
 * ── WHAT IT DOES ───────────────────────────────────────────────────────────
 * Every 10 minutes, engagements still PROVISIONING more than 30 minutes after
 * creation are provisioned inline through the same method the Kafka consumer
 * calls (AuditEngagementService.completeEngagementProvisioning). Success sets
 * READY; a bad template sets FAILED. Either way the engagement leaves this
 * sweep's input, so it is never processed twice.
 *
 * 30 minutes is far beyond any real snapshot (seconds), so the sweep never
 * races a consumer that is still working. completeEngagementProvisioning also
 * skips a snapshot or workflow that already exists, so even an overlap cannot
 * build a second tree.
 *
 * ── WHAT IT RECONSTRUCTS ───────────────────────────────────────────────────
 * The lost message carried templateId, createdBy, startWorkflow and an
 * optional workflowId. The first two are on the engagement. startWorkflow was
 * false only for engagements cascaded from a programme, whose workflow runs on
 * the programme instance; so an engagement whose project instance has its own
 * workflow gets none, and every other engagement gets its own (a standalone
 * engagement with a projectId also gets a project instance, so the instance
 * alone does not decide it). A workflow chosen explicitly at
 * creation is not stored, so recovery starts the default workflow for the
 * engagement's audit type — the same default creation uses when none is picked.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EngagementProvisioningRecoveryScheduler {

    private static final String PROVISIONING = "PROVISIONING";

    private final AuditEngagementRepository engagementRepository;
    private final AuditEngagementService    engagementService;
    private final AuditProjectInstanceRepository projectInstanceRepository;

    /** The engagement's workflow belongs to its programme when the programme instance runs one. */
    private boolean programmeGoverned(AuditEngagement e) {
        if (e.getProjectInstanceId() == null) return false;
        return projectInstanceRepository.findById(e.getProjectInstanceId())
                .map(pi -> pi.getWorkflowInstanceId() != null)
                .orElse(false);
    }

    @Value("${kashi.audit.provisioning-recovery.min-age-minutes:30}")
    private long minAgeMinutes;

    @Scheduled(initialDelayString = "${kashi.audit.provisioning-recovery.initial-delay-ms:120000}",
            fixedDelayString   = "${kashi.audit.provisioning-recovery.interval-ms:600000}")
    public void recoverStuckEngagements() {
        List<AuditEngagement> stuck;
        try {
            stuck = engagementRepository.findBySnapshotStatusAndCreatedAtBefore(
                    PROVISIONING, LocalDateTime.now().minusMinutes(minAgeMinutes));
        } catch (Exception e) {
            log.error("[AUDIT-PROVISIONING-RECOVERY] Could not load stuck engagements — {}", e.toString());
            return;
        }

        int recovered = 0, failed = 0;
        for (AuditEngagement e : stuck) {
            if (e.getTemplateId() == null) continue;   // nothing to snapshot
            try {
                TenantContext.setCurrentTenant(e.getTenantId());
                boolean startWorkflow = !programmeGoverned(e);
                engagementService.completeEngagementProvisioning(
                        e, e.getTemplateId(), e.getTenantId(), startWorkflow, null, e.getCreatedBy());
                recovered++;
                log.info("[AUDIT-PROVISIONING-RECOVERY] Provisioned engagement {} (tenant {}) | startWorkflow={}",
                        e.getId(), e.getTenantId(), startWorkflow);
            } catch (Exception ex) {
                // A bad template is marked FAILED inside completeEngagementProvisioning;
                // anything else stays PROVISIONING and is tried again next run.
                failed++;
                log.error("[AUDIT-PROVISIONING-RECOVERY] Engagement {} (tenant {}) failed — {}",
                        e.getId(), e.getTenantId(), ex.toString());
            } finally {
                TenantContext.clear();
            }
        }

        // Logged every run, including empty ones: a job that logs only on success
        // cannot be told apart from a job that is not running.
        log.info("[AUDIT-PROVISIONING-RECOVERY] Sweep complete | stuck={} | recovered={} | failed={}",
                stuck.size(), recovered, failed);
    }
}