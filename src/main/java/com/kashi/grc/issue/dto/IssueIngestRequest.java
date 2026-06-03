package com.kashi.grc.issue.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

/**
 * Ingest request for automated issue creation via POST /v1/issues/ingest.
 *
 * Designed to accept payloads from:
 *   - Qualys / Tenable (vulnerability scanners)
 *   - AWS Security Hub / GCP Security Command Center
 *   - Splunk / IBM QRadar (SIEM)
 *   - CrowdStrike / SentinelOne (EDR)
 *   - Custom internal monitoring systems
 *
 * Authentication:
 *   No JWT required. Authenticated via X-Ingest-Token header.
 *   Token is tenant-scoped — the service resolves tenantId from it.
 *   Configure tokens in Integrations page (Settings → Integrations → Issue ingestion).
 *
 * Deduplication:
 *   source + externalId uniquely identify an issue per tenant.
 *   Re-posting the same source + externalId updates the existing issue
 *   if the severity or status has changed — it does NOT create a duplicate.
 *
 * CVSS → Severity mapping (automatic):
 *   9.0–10.0  → CRITICAL
 *   7.0–8.9   → HIGH
 *   4.0–6.9   → MEDIUM
 *   0.0–3.9   → LOW
 *   null      → use explicit severity field or default MEDIUM
 */
@Data
public class IssueIngestRequest {

    // ── Required ──────────────────────────────────────────────────────────────

    /**
     * External system identifier — used for deduplication.
     * Examples: "CVE-2024-1234", "QID-918870", "alert-abc123"
     */
    @NotBlank
    private String externalId;

    /**
     * Source system name — used for deduplication + display.
     * Examples: "QUALYS", "TENABLE", "AWS_SECURITY_HUB", "SPLUNK", "CROWDSTRIKE"
     */
    @NotBlank
    private String source;

    @NotBlank
    private String title;

    // ── Severity (one of these should be set) ─────────────────────────────────

    /**
     * CVSS score (0.0–10.0). Automatically mapped to Severity enum.
     * Takes priority over severity field when set.
     */
    private Double cvssScore;

    /**
     * Explicit severity. Used when cvssScore is not available.
     * Accepted values: CRITICAL, HIGH, MEDIUM, LOW (case-insensitive)
     */
    private String severity;

    // ── Optional enrichment ────────────────────────────────────────────────────

    private String description;
    private String category;          // INFORMATION_SECURITY, VULNERABILITY, etc.
    private String status;            // current status in source system (informational)
    private String frameworkRef;      // e.g. "CIS 6.2", "NIST CSF ID.AM-2"

    /**
     * Asset context — what was affected.
     * Examples: "prod-api-server-01", "10.0.1.45", "s3://my-bucket"
     */
    private String affectedAsset;
    private String affectedAssetType; // SERVER, ENDPOINT, CLOUD_RESOURCE, APPLICATION

    /**
     * Full raw payload from the source system.
     * Stored as-is on the Issue for audit and reprocessing.
     * May contain additional fields specific to the source.
     */
    private Map<String, Object> rawPayload;

    // ── Workflow override ─────────────────────────────────────────────────────

    /**
     * Override the default AUTOMATED workflow blueprint ID.
     * If null, service uses the platform-configured default for AUTOMATED issues.
     */
    private Long workflowId;
}