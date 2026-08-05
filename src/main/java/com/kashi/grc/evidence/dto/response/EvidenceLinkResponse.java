package com.kashi.grc.evidence.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kashi.grc.evidence.domain.EvidenceLink;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * Response for a single EvidenceLink.
 *
 * Shown on:
 *   - EvidenceRecord detail page → "this evidence has been linked to X controls"
 *   - AuditControlInstance detail → "these evidence records satisfy this control"
 *   - Issue detail → "evidence attached to this issue"
 *
 * autoLinked=true means the engine created this link via tag matching.
 * autoLinked=false means a human manually created it.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EvidenceLinkResponse {

    private Long   id;
    private Long   evidenceRecordId;
    private String targetEntityType;
    private Long   targetEntityId;
    private EvidenceLink.Status status;
    private boolean autoLinked;
    private String matchedTagSnapshot;
    private Long   reviewedBy;
    private LocalDateTime reviewedAt;
    private String reviewerNote;
    private LocalDateTime linkedAt;

    /** Populated when fetching links for an entity — shows the evidence details inline */
    private String evidenceTitle;
    private String evidenceFileUrl;
    private String evidenceControlTag;
    private LocalDateTime evidenceValidUntil;
    private boolean evidenceExpired;

    // ── KashiLink: parent-record fields the UI filters and renders on ────────
    // The evidence tabs split links into "manual", "reused" and "integration
    // checks". Without collectionType on the link the automated filter can
    // never match, which is why that section always rendered empty.

    private String evidenceFileName;

    /** Needed by the frontend preview drawer to decide how to render this
     *  file (PDF viewer, image, converted-HTML, or download-only fallback).
     *  Stored on EvidenceRecord already — just wasn't mapped through here. */
    private String evidenceMimeType;

    /** MANUAL | AUTOMATED | HYBRID */
    private String collectionType;

    /** PASS | FAIL | ERROR | NOT_RUN — null for manual evidence */
    private String automationResult;

    /** Human-readable integration result, e.g. "All 47 admin users have MFA enabled" */
    private String automationMessage;

    /** Integration run time — distinct from linkedAt */
    private LocalDateTime collectedAt;
}