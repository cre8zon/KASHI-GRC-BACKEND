package com.kashi.grc.audit.dto.request;

import lombok.Data;
import java.time.LocalDate;
import java.util.List;

/**
 * Request body for bulk control assignment.
 *
 * Assigns a specific user to multiple controls in one call — eliminates the need
 * for N individual PUT calls when a section owner has 50-100 controls to delegate.
 *
 * Either controlIds (explicit list) OR sectionInstanceId (all controls under a section)
 * must be provided. If both are provided, controlIds takes precedence.
 *
 * Examples:
 *   Rohit assigns all controls in Section A to himself: sectionInstanceId=42, auditorUserId=rohitId
 *   Anita assigns 20 specific controls to a colleague:  controlIds=[1,2,...,20], auditeeUserId=colleagueId
 */
@Data
public class BulkControlAssignRequest {

    /** Explicit list of control instance IDs to assign. Overrides sectionInstanceId. */
    private List<Long> controlIds;

    /**
     * Assign all controls under this section (and its descendants).
     * Ignored if controlIds is provided.
     */
    private Long sectionInstanceId;

    // ── Auditor side ─────────────────────────────────────────────────────────
    /** userId to assign as control auditor. Null = leave the auditor unchanged. */
    private Long auditorUserId;

    /**
     * true = CLEAR the auditor on every target control. Null was documented as
     * "unassign" above but the service always treated it as "leave unchanged",
     * so bulk unassign was impossible; it is an explicit flag instead, because
     * null cannot mean both. Refused together with auditorUserId.
     */
    private Boolean unassignAuditor;

    // ── Auditee side ─────────────────────────────────────────────────────────
    /** userId to assign as control evidence owner. Null = leave unchanged. */
    private Long auditeeUserId;

    /** true = CLEAR the evidence owner on every target control. Refused with auditeeUserId. */
    private Boolean unassignAuditee;

    /** Optional evidence due date for all assigned controls */
    private LocalDate evidenceDueDate;
}