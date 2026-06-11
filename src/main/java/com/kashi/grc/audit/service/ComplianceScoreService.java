package com.kashi.grc.audit.service;

import com.kashi.grc.audit.domain.AuditControlInstance;
import com.kashi.grc.audit.domain.AuditEngagement;
import com.kashi.grc.audit.domain.AuditFinding;
import com.kashi.grc.audit.repository.AuditControlInstanceRepository;
import com.kashi.grc.audit.repository.AuditEngagementRepository;
import com.kashi.grc.audit.repository.AuditFindingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ComplianceScoreService — full finding-aware compliance recalculation.
 *
 * Called after any finding status change (validate, acceptRisk, close, reopen).
 *
 * ── EXISTING FIELDS (always updated) ─────────────────────────────────────────
 *   passedControls      — controls with EFFECTIVE test result and no open findings
 *   failedControls      — controls with open/in-remediation findings
 *   testedControls      — controls where testResult != NOT_TESTED
 *   openFindingCount    — OPEN + IN_REMEDIATION + PENDING_VALIDATION findings only
 *   overallRating       — EFFECTIVE / PARTIALLY_EFFECTIVE / INEFFECTIVE / NOT_RATED
 *
 * ── NEW FIELDS (from V_audit_engagement_compliance_fields.sql) ────────────────
 *   accepted_risk_count      — findings in ACCEPTED_RISK status
 *   accepted_risk_controls   — controls where all findings are ACCEPTED_RISK
 *   closed_finding_count     — findings in CLOSED status
 *   compliance_pct           — Vanta-style: (passed + acceptedRisk) / total × 100
 *   strict_compliance_pct    — AuditBoard-style: passed / total × 100
 *
 * New fields are set via reflection so this service compiles and runs correctly
 * even before V_audit_engagement_compliance_fields.sql is applied — graceful degradation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ComplianceScoreService {

    private final AuditEngagementRepository      engagementRepository;
    private final AuditControlInstanceRepository  controlRepo;
    private final AuditFindingRepository          findingRepo;

    @Transactional
    public void syncEngagementScore(Long engagementId, Long tenantId) {
        AuditEngagement engagement = engagementRepository.findById(engagementId).orElse(null);
        if (engagement == null) return;

        List<AuditControlInstance> controls = controlRepo.findByEngagementId(engagementId);
        List<AuditFinding> allFindings = findingRepo.findByEngagementIdAndTenantId(engagementId, tenantId);

        int totalControls        = controls.size();
        int testedControls       = 0;
        int passedControls       = 0;
        int failedControls       = 0;
        int acceptedRiskControls = 0;
        int openFindingCount     = 0;
        int acceptedRiskCount    = 0;
        int closedFindingCount   = 0;

        // Count findings by status
        for (AuditFinding f : allFindings) {
            switch (f.getStatus()) {
                case OPEN, IN_REMEDIATION, PENDING_VALIDATION -> openFindingCount++;
                case ACCEPTED_RISK                            -> acceptedRiskCount++;
                case CLOSED                                   -> closedFindingCount++;
            }
        }

        // Derive per-control compliance status
        for (AuditControlInstance ctrl : controls) {
            boolean isTested = ctrl.getTestResult() != null
                    && ctrl.getTestResult() != AuditControlInstance.TestResult.NOT_TESTED;
            if (isTested) testedControls++;

            List<AuditFinding> ctrlFindings = allFindings.stream()
                    .filter(f -> ctrl.getId().equals(f.getControlInstanceId()))
                    .toList();

            boolean hasOpenFindings = ctrlFindings.stream().anyMatch(f ->
                    f.getStatus() == AuditFinding.Status.OPEN ||
                            f.getStatus() == AuditFinding.Status.IN_REMEDIATION ||
                            f.getStatus() == AuditFinding.Status.PENDING_VALIDATION);

            boolean hasOnlyAcceptedRisk = !ctrlFindings.isEmpty() && ctrlFindings.stream()
                    .allMatch(f -> f.getStatus() == AuditFinding.Status.ACCEPTED_RISK);

            boolean allFindingsResolved = !ctrlFindings.isEmpty() && ctrlFindings.stream()
                    .allMatch(f -> f.getStatus() == AuditFinding.Status.ACCEPTED_RISK
                            || f.getStatus() == AuditFinding.Status.CLOSED);

            AuditControlInstance.TestResult result = ctrl.getTestResult();

            if (hasOnlyAcceptedRisk) {
                acceptedRiskControls++;
            } else if (hasOpenFindings) {
                failedControls++;
            } else if (result == AuditControlInstance.TestResult.EFFECTIVE
                    || (result == AuditControlInstance.TestResult.PARTIALLY_EFFECTIVE && allFindingsResolved)
                    || (ctrlFindings.isEmpty() && result == AuditControlInstance.TestResult.EFFECTIVE)) {
                passedControls++;
            } else if (result == AuditControlInstance.TestResult.INEFFECTIVE) {
                failedControls++;
            }
        }

        // ── Update existing fields (always present on AuditEngagement) ────────
        engagement.setPassedControls(passedControls);
        engagement.setFailedControls(failedControls);
        engagement.setTestedControls(testedControls);
        engagement.setOpenFindingCount(openFindingCount);

        // overallRating — derived from pass/fail ratio
        if (totalControls == 0 || testedControls == 0) {
            engagement.setOverallRating("NOT_RATED");
        } else if (failedControls == 0) {
            engagement.setOverallRating("EFFECTIVE");
        } else if (passedControls > failedControls) {
            engagement.setOverallRating("PARTIALLY_EFFECTIVE");
        } else {
            engagement.setOverallRating("INEFFECTIVE");
        }

        // ── Update new fields via reflection (graceful if not yet in schema) ──
        int compliancePct  = totalControls > 0
                ? ((passedControls + acceptedRiskControls) * 100 / totalControls) : 0;
        int strictPct      = totalControls > 0 ? (passedControls * 100 / totalControls) : 0;

        trySetInt(engagement, "acceptedRiskCount",    acceptedRiskCount);
        trySetInt(engagement, "acceptedRiskControls", acceptedRiskControls);
        trySetInt(engagement, "closedFindingCount",   closedFindingCount);
        trySetInt(engagement, "compliancePct",        compliancePct);
        trySetInt(engagement, "strictCompliancePct",  strictPct);

        engagementRepository.save(engagement);

        log.info("[COMPLIANCE-SCORE] Synced | engagementId={} total={} tested={} " +
                        "passed={} failed={} acceptedRisk={} openFindings={} " +
                        "acceptedRiskFindings={} closedFindings={} compliance={}% strict={}%",
                engagementId, totalControls, testedControls,
                passedControls, failedControls, acceptedRiskControls,
                openFindingCount, acceptedRiskCount, closedFindingCount,
                compliancePct, strictPct);
    }

    private void trySetInt(Object obj, String fieldName, int value) {
        try {
            var field = obj.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(obj, value);
        } catch (NoSuchFieldException e) {
            // Field not yet added to entity — run V_audit_engagement_compliance_fields.sql
            log.debug("[COMPLIANCE-SCORE] Field '{}' not on AuditEngagement yet — run migration SQL", fieldName);
        } catch (Exception e) {
            log.warn("[COMPLIANCE-SCORE] Could not set field '{}': {}", fieldName, e.getMessage());
        }
    }
}