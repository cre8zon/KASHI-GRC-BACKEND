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
    /** userId to assign as control auditor. Null = unassign. */
    private Long auditorUserId;

    // ── Auditee side ─────────────────────────────────────────────────────────
    /** userId to assign as control evidence owner. Null = unassign. */
    private Long auditeeUserId;

    /** Optional evidence due date for all assigned controls */
    private LocalDate evidenceDueDate;
}