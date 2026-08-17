package com.kashi.grc.audit.automation;

import com.kashi.grc.audit.domain.AuditControlInstance;
import com.kashi.grc.audit.domain.AuditEngagement;
import com.kashi.grc.audit.domain.AuditFinding;
import com.kashi.grc.audit.repository.AuditControlInstanceRepository;
import com.kashi.grc.audit.repository.AuditEngagementRepository;
import com.kashi.grc.audit.repository.AuditFindingRepository;
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
 * GenerateAuditReportAction — automated workflow action for SOC 2 Step 8.
 *
 * Triggered when the workflow engine activates a SYSTEM step with
 * automated_action = GENERATE_AUDIT_REPORT.
 *
 * ── WHAT THIS DOES ───────────────────────────────────────────────────────────
 * 1. Resolves the AuditEngagement from workflowInstance.entityId
 * 2. Computes control effectiveness stats
 * 3. Snapshots finding counts by severity
 * 4. Builds generatedData JSON (stored on Document.generatedData)
 * 5. Creates Document + DocumentLink rows (same pattern as TPRM report)
 * 6. Marks engagement status = COMPLETED
 *
 * ── PDF GENERATION ────────────────────────────────────────────────────────────
 * Currently stores a placeholder — infrastructure is complete.
 * To generate a real PDF, inject ReportPdfGeneratorService and replace:
 *   byte[] pdfBytes = new byte[0];
 * with:
 *   byte[] pdfBytes = pdfGenerator.generateAuditReport(engagement, reportData);
 *
 * ── ACTION KEY ────────────────────────────────────────────────────────────────
 * workflow_steps.automated_action must be set to "GENERATE_AUDIT_REPORT".
 * Update SOC2 Step 8 (id=200 in your DB):
 *   UPDATE workflow_steps SET automated_action = 'GENERATE_AUDIT_REPORT' WHERE id = 200;
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GenerateAuditReportAction implements AutomatedActionHandler {

    private final AuditEngagementRepository      engagementRepository;
    private final AuditControlInstanceRepository controlInstanceRepository;
    private final AuditFindingRepository         findingRepository;
    private final DocumentRepository             documentRepository;
    private final DocumentLinkRepository         documentLinkRepository;
    private final StorageService                 storageService;
    private final com.kashi.grc.usermanagement.repository.UserRepository userRepository;
    private final com.kashi.grc.usermanagement.repository.UserTenantMembershipRepository membershipRepository;
    private final com.kashi.grc.tenant.repository.TenantRepository tenantRepository;

    @Override
    public String actionKey() {
        return "GENERATE_AUDIT_REPORT";
    }

    @Override
    @Transactional
    public boolean execute(AutomatedActionContext ctx) {
        WorkflowInstance wi = ctx.getWorkflowInstance();

        log.info("[GENERATE_AUDIT_REPORT] Starting | workflowInstanceId={} | entityId={}",
                wi.getId(), wi.getEntityId());

        // ── 1. Resolve engagement ──────────────────────────────────────────
        AuditEngagement engagement = engagementRepository
                .findById(wi.getEntityId()).orElse(null);

        if (engagement == null) {
            log.error("[GENERATE_AUDIT_REPORT] No engagement found for entityId={}", wi.getEntityId());
            return false;
        }

        Long engagementId = engagement.getId();
        Long tenantId     = engagement.getTenantId();
        Long actorId      = wi.getInitiatedBy() != null ? wi.getInitiatedBy() : 0L;

        // ── 2. Idempotency check ───────────────────────────────────────────
        boolean alreadyGenerated = !documentLinkRepository
                .findActiveByEntity("AUDIT_ENGAGEMENT", engagementId, "REPORT")
                .isEmpty();
        if (alreadyGenerated) {
            log.warn("[GENERATE_AUDIT_REPORT] Already generated — skipping | engagementId={}", engagementId);
            return true;
        }

        // ── 3. Compute control effectiveness stats ─────────────────────────
        List<AuditControlInstance> controls =
                controlInstanceRepository.findByEngagementId(engagementId);

        long totalControls   = controls.size();
        long effective       = controls.stream().filter(c -> AuditControlInstance.TestResult.EFFECTIVE        == c.getTestResult()).count();
        long partiallyEff    = controls.stream().filter(c -> AuditControlInstance.TestResult.PARTIALLY_EFFECTIVE == c.getTestResult()).count();
        long ineffective     = controls.stream().filter(c -> AuditControlInstance.TestResult.INEFFECTIVE      == c.getTestResult()).count();
        long notTested       = controls.stream().filter(c -> c.getTestResult() == null
                || AuditControlInstance.TestResult.NOT_TESTED == c.getTestResult()).count();
        double passRate      = totalControls > 0
                ? Math.round((effective * 10000.0) / totalControls) / 100.0
                : 0.0;

        // ── 4. Snapshot finding counts by severity ─────────────────────────
        List<AuditFinding> findings = findingRepository
                .findByEngagementIdAndTenantId(engagementId, tenantId);
        long criticalFindings = findings.stream().filter(f -> AuditFinding.Severity.CRITICAL == f.getSeverity()).count();
        long highFindings     = findings.stream().filter(f -> AuditFinding.Severity.HIGH     == f.getSeverity()).count();
        long mediumFindings   = findings.stream().filter(f -> AuditFinding.Severity.MEDIUM   == f.getSeverity()).count();
        long lowFindings      = findings.stream().filter(f -> AuditFinding.Severity.LOW      == f.getSeverity()).count();
        long openFindings     = findings.stream().filter(f -> AuditFinding.Status.OPEN       == f.getStatus()
                || AuditFinding.Status.IN_REMEDIATION == f.getStatus()).count();

        // ── 5. Build generatedData ─────────────────────────────────────────
        Map<String, Object> reportData = new HashMap<>();
        reportData.put("reportVersion",       1);
        reportData.put("engagementId",        engagementId);
        reportData.put("engagementName",      engagement.getName());
        reportData.put("auditType",           engagement.getAuditType() != null ? engagement.getAuditType().name() : "");
        reportData.put("frameworkRef",        engagement.getFrameworkRef() != null ? engagement.getFrameworkRef() : "");

        // ── Who performed this audit ──────────────────────────────────────
        // An audit report whose defining claim is independence has to say who
        // did the work. The distinction between an internal audit and a
        // third-party attestation is the whole point of the document, and until
        // now the report recorded neither.
        //
        // KashiGRC never appears here. The tool is not the auditor, any more
        // than a word processor signs a letter.
        reportData.putAll(attribution(engagement.getLeadAuditorId(), engagement.getTenantId()));
        reportData.put("totalControls",       totalControls);
        reportData.put("effectiveControls",   effective);
        reportData.put("partiallyEffective",  partiallyEff);
        reportData.put("ineffectiveControls", ineffective);
        reportData.put("notTestedControls",   notTested);
        reportData.put("passRatePct",         passRate);
        reportData.put("totalFindings",       findings.size());
        reportData.put("criticalFindings",    criticalFindings);
        reportData.put("highFindings",        highFindings);
        reportData.put("mediumFindings",      mediumFindings);
        reportData.put("lowFindings",         lowFindings);
        reportData.put("openFindings",        openFindings);
        reportData.put("generatedAt",         LocalDateTime.now().toString());
        reportData.put("triggerEvent",        "WORKFLOW_COMPLETION");

        log.info("[GENERATE_AUDIT_REPORT] Stats | engagementId={} | totalControls={} | " +
                        "passRate={}% | findings={} | open={}",
                engagementId, totalControls, passRate, findings.size(), openFindings);

        // ── 6. PDF stub — replace with real generator when ready ──────────
        byte[] pdfBytes = new byte[0]; // placeholder
        String reportFilename = String.format("soc2-audit-report-v1-engagement-%d.pdf", engagementId);

        if (pdfBytes.length > 0) {
            try {
                StorageService.ServerUploadResult uploadResult = storageService.uploadSystemDocument(
                        tenantId, actorId, pdfBytes, reportFilename,
                        "application/pdf", "AUDIT_ENGAGEMENT");

                Document reportDoc = Document.builder()
                        .tenantId(tenantId)
                        .uploadedBy(actorId)
                        .fileName(reportFilename)
                        .title(String.format("SOC 2 Audit Report v1 — %s", engagement.getName()))
                        .mimeType("application/pdf")
                        .documentType("GENERATED_REPORT")
                        .sourceModule("AUDIT_ENGAGEMENT")
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
                        .entityType("AUDIT_ENGAGEMENT")
                        .entityId(engagementId)
                        .linkType("REPORT")
                        .createdBy(actorId)
                        .createdAt(LocalDateTime.now())
                        .notes("Auto-generated at workflow completion")
                        .build());

                log.info("[GENERATE_AUDIT_REPORT] Report uploaded | engagementId={} | docId={} | s3Key={}",
                        engagementId, reportDoc.getId(), uploadResult.getS3Key());

            } catch (Exception e) {
                log.error("[GENERATE_AUDIT_REPORT] Upload failed: {}", e.getMessage(), e);
            }
        } else {
            log.info("[GENERATE_AUDIT_REPORT] PDF stub — skipping upload. " +
                    "Wire in a PDF generator to produce a real report | engagementId={}", engagementId);
        }

        // ── 7. Mark engagement COMPLETED ──────────────────────────────────
        engagement.setStatus(AuditEngagement.Status.FINAL_REPORT);
        engagement.setCompletedAt(LocalDateTime.now());
        engagementRepository.save(engagement);

        log.info("[GENERATE_AUDIT_REPORT] Complete | engagementId={} | passRate={}% | openFindings={}",
                engagementId, passRate, openFindings);
        return true;
    }

    /**
     * Resolves the performing party from the engagement's lead auditor.
     *
     * A lead auditor holding a GUEST membership in this tenant is an external
     * auditor, and firm_tenant_id names the firm that placed them. A HOME
     * membership means the audit was performed by the organisation's own staff,
     * which is an internal audit and must not be presented as anything else.
     */
    private Map<String, Object> attribution(Long leadAuditorId, Long tenantId) {
        Map<String, Object> out = new HashMap<>();
        out.put("performedBy",     "Internal Audit");
        out.put("performedByType", "INTERNAL");
        out.put("leadAuditorName", "");
        out.put("auditFirmName",   "");

        if (leadAuditorId == null) return out;

        userRepository.findById(leadAuditorId).ifPresent(u ->
                out.put("leadAuditorName", u.getFullName() != null ? u.getFullName().trim() : u.getEmail()));

        membershipRepository.findByUserIdAndTenantId(leadAuditorId, tenantId).ifPresent(m -> {
            if ("GUEST".equalsIgnoreCase(m.getMembershipType()) && m.getFirmTenantId() != null) {
                String firm = tenantRepository.findById(m.getFirmTenantId())
                        .map(t -> t.getName()).orElse(null);
                if (firm != null) {
                    out.put("performedBy",     firm);
                    out.put("performedByType", "EXTERNAL_FIRM");
                    out.put("auditFirmName",   firm);
                }
            }
        });
        return out;
    }

}