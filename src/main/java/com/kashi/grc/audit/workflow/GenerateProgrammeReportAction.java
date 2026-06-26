package com.kashi.grc.audit.workflow;

import com.kashi.grc.audit.domain.AuditControlInstance;
import com.kashi.grc.audit.domain.AuditEngagement;
import com.kashi.grc.audit.domain.AuditFinding;
import com.kashi.grc.audit.domain.AuditProjectInstance;
import com.kashi.grc.audit.repository.AuditControlInstanceRepository;
import com.kashi.grc.audit.repository.AuditEngagementRepository;
import com.kashi.grc.audit.repository.AuditFindingRepository;
import com.kashi.grc.audit.repository.AuditProjectInstanceRepository;
import com.kashi.grc.document.domain.Document;
import com.kashi.grc.document.domain.DocumentLink;
import com.kashi.grc.document.repository.DocumentLinkRepository;
import com.kashi.grc.document.repository.DocumentRepository;
import com.kashi.grc.document.service.StorageService;
import com.kashi.grc.workflow.automation.AutomatedActionContext;
import com.kashi.grc.workflow.automation.AutomatedActionHandler;
import com.kashi.grc.workflow.domain.WorkflowInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GenerateProgrammeReportAction — automated workflow action for
 * "Audit Project Lifecycle" (workflow 16) Step 8 ("Generate Programme Report").
 *
 * Mirrors GenerateAuditReportAction (SOC2 Step 8), but aggregates across ALL
 * AuditEngagement rows under a project rather than a single engagement —
 * one programme-level report covering every framework/engagement in the
 * project.
 *
 * ── WHAT THIS DOES ───────────────────────────────────────────────────────────
 * 1. Resolves the AuditProject from workflowInstance.entityId
 * 2. Loads all AuditEngagement rows WHERE projectId = project.id
 * 3. Aggregates control effectiveness + finding counts across all engagements
 * 4. Builds generatedData JSON (per-engagement breakdown + programme totals)
 * 5. Creates Document + DocumentLink (entityType=AUDIT_PROJECT, linkType=REPORT)
 * 6. PDF generation is a stub (consistent with GenerateAuditReportAction) —
 *    infrastructure is in place; wire a real PDF generator when ready.
 *
 * ── IDEMPOTENCY ──────────────────────────────────────────────────────────────
 * If an active REPORT document already exists for this project, skip
 * regeneration and return true (step advances normally).
 *
 * ── ACTION KEY ────────────────────────────────────────────────────────────────
 * workflow_steps.automated_action = 'GENERATE_PROGRAMME_REPORT' (already set
 * on workflow_steps id=227, step_order=8, side=SYSTEM).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GenerateProgrammeReportAction implements AutomatedActionHandler {

    private final AuditProjectInstanceRepository projectInstanceRepository;
    private final AuditEngagementRepository      engagementRepository;
    private final AuditControlInstanceRepository controlInstanceRepository;
    private final AuditFindingRepository         findingRepository;
    private final DocumentRepository             documentRepository;
    private final DocumentLinkRepository         documentLinkRepository;
    private final StorageService                 storageService;

    @Override
    public String actionKey() {
        return "GENERATE_PROGRAMME_REPORT";
    }

    @Override
    @Transactional
    public boolean execute(AutomatedActionContext ctx) {
        WorkflowInstance wi = ctx.getWorkflowInstance();
        Long projectId       = wi.getEntityId();

        log.info("[GENERATE_PROGRAMME_REPORT] Starting | workflowInstanceId={} | projectId={}",
                wi.getId(), projectId);

        // ── 1. Resolve project ──────────────────────────────────────────────
        AuditProjectInstance project = projectInstanceRepository.findById(projectId).orElse(null);
        if (project == null) {
            log.error("[GENERATE_PROGRAMME_REPORT] No project instance found for entityId={}", projectId);
            return false;
        }

        Long tenantId = project.getTenantId();
        Long actorId  = wi.getInitiatedBy() != null ? wi.getInitiatedBy() : 0L;

        // ── 2. Idempotency check ─────────────────────────────────────────────
        boolean alreadyGenerated = !documentLinkRepository
                .findActiveByEntity("AUDIT_PROJECT", projectId, "REPORT")
                .isEmpty();
        if (alreadyGenerated) {
            log.warn("[GENERATE_PROGRAMME_REPORT] Already generated — skipping | projectId={}", projectId);
            return true;
        }

        // ── 3. Load all engagements under this project ───────────────────────
        List<AuditEngagement> engagements = engagementRepository.findByProjectInstanceId(projectId);

        if (engagements.isEmpty()) {
            log.warn("[GENERATE_PROGRAMME_REPORT] No engagements for projectId={} — " +
                            "POST /projects/{}/start may not have run. Auto-approving with empty report.",
                    projectId, projectId);
            return true;
        }

        // ── 4. Aggregate stats across all engagements ─────────────────────────
        long programTotalControls   = 0;
        long programEffective       = 0;
        long programPartiallyEff    = 0;
        long programIneffective     = 0;
        long programNotTested       = 0;
        long programTotalFindings   = 0;
        long programCriticalFind    = 0;
        long programHighFind        = 0;
        long programMediumFind      = 0;
        long programLowFind         = 0;
        long programOpenFind        = 0;

        List<Map<String, Object>> engagementBreakdown = new java.util.ArrayList<>();

        for (AuditEngagement engagement : engagements) {
            Long engagementId = engagement.getId();

            List<AuditControlInstance> controls =
                    controlInstanceRepository.findByEngagementId(engagementId);

            long totalControls = controls.size();
            long effective     = controls.stream().filter(c -> AuditControlInstance.TestResult.EFFECTIVE           == c.getTestResult()).count();
            long partiallyEff  = controls.stream().filter(c -> AuditControlInstance.TestResult.PARTIALLY_EFFECTIVE == c.getTestResult()).count();
            long ineffective   = controls.stream().filter(c -> AuditControlInstance.TestResult.INEFFECTIVE         == c.getTestResult()).count();
            long notTested     = controls.stream().filter(c -> c.getTestResult() == null
                    || AuditControlInstance.TestResult.NOT_TESTED == c.getTestResult()).count();
            double passRate    = totalControls > 0
                    ? Math.round((effective * 10000.0) / totalControls) / 100.0
                    : 0.0;

            List<AuditFinding> findings = findingRepository
                    .findByEngagementIdAndTenantId(engagementId, tenantId);
            long critical = findings.stream().filter(f -> AuditFinding.Severity.CRITICAL == f.getSeverity()).count();
            long high     = findings.stream().filter(f -> AuditFinding.Severity.HIGH     == f.getSeverity()).count();
            long medium   = findings.stream().filter(f -> AuditFinding.Severity.MEDIUM   == f.getSeverity()).count();
            long low      = findings.stream().filter(f -> AuditFinding.Severity.LOW      == f.getSeverity()).count();
            long open     = findings.stream().filter(f -> AuditFinding.Status.OPEN       == f.getStatus()
                    || AuditFinding.Status.IN_REMEDIATION == f.getStatus()).count();

            programTotalControls += totalControls;
            programEffective     += effective;
            programPartiallyEff  += partiallyEff;
            programIneffective   += ineffective;
            programNotTested     += notTested;
            programTotalFindings += findings.size();
            programCriticalFind  += critical;
            programHighFind      += high;
            programMediumFind    += medium;
            programLowFind       += low;
            programOpenFind      += open;

            Map<String, Object> engRow = new HashMap<>();
            engRow.put("engagementId",   engagementId);
            engRow.put("engagementRef",  engagement.getEngagementRef());
            engRow.put("name",           engagement.getName());
            engRow.put("frameworkRef",   engagement.getFrameworkRef() != null ? engagement.getFrameworkRef() : "");
            engRow.put("totalControls",  totalControls);
            engRow.put("effective",      effective);
            engRow.put("partiallyEffective", partiallyEff);
            engRow.put("ineffective",    ineffective);
            engRow.put("notTested",      notTested);
            engRow.put("passRatePct",    passRate);
            engRow.put("totalFindings",  findings.size());
            engRow.put("openFindings",   open);
            engagementBreakdown.add(engRow);

            // Mark each engagement FINAL_REPORT, same as single-engagement GENERATE_AUDIT_REPORT
            if (engagement.getStatus() != AuditEngagement.Status.CLOSED
                    && engagement.getStatus() != AuditEngagement.Status.CANCELLED) {
                engagement.setStatus(AuditEngagement.Status.FINAL_REPORT);
                engagement.setCompletedAt(LocalDateTime.now());
                engagementRepository.save(engagement);
            }
        }

        double programPassRate = programTotalControls > 0
                ? Math.round((programEffective * 10000.0) / programTotalControls) / 100.0
                : 0.0;

        // ── 5. Build generatedData ────────────────────────────────────────────
        Map<String, Object> reportData = new HashMap<>();
        reportData.put("reportVersion",        1);
        reportData.put("projectId",            projectId);
        reportData.put("projectRef",           project.getProjectRefSnapshot());
        reportData.put("projectName",          project.getProjectNameSnapshot());
        reportData.put("engagementCount",      engagements.size());
        reportData.put("totalControls",        programTotalControls);
        reportData.put("effectiveControls",    programEffective);
        reportData.put("partiallyEffective",   programPartiallyEff);
        reportData.put("ineffectiveControls",  programIneffective);
        reportData.put("notTestedControls",    programNotTested);
        reportData.put("passRatePct",          programPassRate);
        reportData.put("totalFindings",        programTotalFindings);
        reportData.put("criticalFindings",     programCriticalFind);
        reportData.put("highFindings",         programHighFind);
        reportData.put("mediumFindings",       programMediumFind);
        reportData.put("lowFindings",          programLowFind);
        reportData.put("openFindings",         programOpenFind);
        reportData.put("engagements",          engagementBreakdown);
        reportData.put("generatedAt",          LocalDateTime.now().toString());
        reportData.put("triggerEvent",         "WORKFLOW_STEP_GENERATE_PROGRAMME_REPORT");

        // ── Step 9: Audit opinion + review narrative (per engagement) ─────────
        // Derive the overall programme opinion from the engagement reviews
        List<Map<String, Object>> engagementReviews = engagements.stream().map(eng -> {
            Map<String, Object> rev = new HashMap<>();
            rev.put("engagementId",      eng.getId());
            rev.put("engagementRef",     eng.getEngagementRef());
            rev.put("name",              eng.getName());
            rev.put("auditOpinion",      eng.getAuditOpinion());
            rev.put("overallRating",     eng.getOverallRating());
            rev.put("executiveSummary",  eng.getExecutiveSummary());
            rev.put("reviewComments",    eng.getReviewComments());
            rev.put("scopeLimitations",  eng.getScopeLimitations());
            rev.put("reviewedAt",        eng.getReviewedAt() != null ? eng.getReviewedAt().toString() : null);
            return rev;
        }).toList();
        reportData.put("engagementReviews", engagementReviews);

        // ── Step 11: Cross-framework consolidation ────────────────────────────
        reportData.put("crossFrameworkNotes", project.getCrossFrameworkNotes());
        reportData.put("programmeRisk",       project.getProgrammeRisk());

        // ── Step 12: Management response ──────────────────────────────────────
        reportData.put("managementResponse",   project.getManagementResponse());
        reportData.put("acceptanceOfFindings", project.getAcceptanceOfFindings());
        reportData.put("correctiveActions",    project.getCorrectiveActions());
        reportData.put("committedClosureDate", project.getCommittedClosureDate() != null
                ? project.getCommittedClosureDate().toString() : null);

        // ── Step 13: Executive sign-off ───────────────────────────────────────
        reportData.put("executiveSignOff",   project.getExecutiveSignOff());
        reportData.put("programmeOutcome",   project.getProgrammeOutcome());
        reportData.put("closureStatement",   project.getClosureStatement());
        reportData.put("nextAuditDue",       project.getNextAuditDue() != null
                ? project.getNextAuditDue().toString() : null);
        reportData.put("signedOffBy",        project.getSignedOffBy());
        reportData.put("signedOffAt",        project.getSignedOffAt() != null
                ? project.getSignedOffAt().toString() : null);

        log.info("[GENERATE_PROGRAMME_REPORT] Stats | projectId={} | engagements={} | " +
                        "totalControls={} | passRate={}% | findings={} | open={}",
                projectId, engagements.size(), programTotalControls, programPassRate,
                programTotalFindings, programOpenFind);

        // ── 6. PDF stub — replace with real generator when ready ──────────────
        byte[] pdfBytes = new byte[0]; // placeholder, mirrors GenerateAuditReportAction
        String reportFilename = String.format("project-programme-report-v1-project-%d.pdf", projectId);

        if (pdfBytes.length > 0) {
            try {
                StorageService.ServerUploadResult uploadResult = storageService.uploadSystemDocument(
                        tenantId, actorId, pdfBytes, reportFilename,
                        "application/pdf", "AUDIT_PROJECT");

                Document reportDoc = Document.builder()
                        .tenantId(tenantId)
                        .uploadedBy(actorId)
                        .fileName(reportFilename)
                        .title(String.format("Programme Report v1 — %s", project.getProjectNameSnapshot()))
                        .mimeType("application/pdf")
                        .documentType("GENERATED_REPORT")
                        .sourceModule("AUDIT_PROJECT")
                        .generatedData(reportData)
                        .s3Key(uploadResult.getS3Key())
                        .s3Bucket(storageService.getBucket())
                        .storagePath(uploadResult.getS3Key())
                        .status("ACTIVE")
                        .version(1)
                        .fileSize(uploadResult.getContentLength())
                        .contentLength(uploadResult.getContentLength())
                        .checksumSha256(uploadResult.getChecksumSha256())
                        .build();
                documentRepository.save(reportDoc);

                documentLinkRepository.save(DocumentLink.builder()
                        .tenantId(tenantId)
                        .documentId(reportDoc.getId())
                        .entityType("AUDIT_PROJECT")
                        .entityId(projectId)
                        .linkType("REPORT")
                        .createdBy(actorId)
                        .createdAt(LocalDateTime.now())
                        .notes("Auto-generated at workflow step 8 (Generate Programme Report)")
                        .build());

                log.info("[GENERATE_PROGRAMME_REPORT] Report uploaded | projectId={} | docId={} | s3Key={}",
                        projectId, reportDoc.getId(), uploadResult.getS3Key());

            } catch (Exception e) {
                log.error("[GENERATE_PROGRAMME_REPORT] Upload failed: {}", e.getMessage(), e);
            }
        } else {
            log.info("[GENERATE_PROGRAMME_REPORT] PDF stub — skipping upload. " +
                    "Wire in a PDF generator to produce a real report | projectId={}", projectId);
        }

        log.info("[GENERATE_PROGRAMME_REPORT] Complete | projectId={} | passRate={}% | openFindings={}",
                projectId, programPassRate, programOpenFind);
        return true;
    }
}