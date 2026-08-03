package com.kashi.grc.audit.dto.request;

import lombok.Data;
import java.util.List;

/**
 * Request body for bulk SECTION assignment — mirrors BulkControlAssignRequest.
 *
 * Assigns an auditor and/or auditee to multiple sections in one call, eliminating
 * N individual PUT calls. Each section assignment reuses the existing per-section
 * logic (assignSection / assignAuditeeToSection), so cascade-to-children behaves
 * exactly as it does for a single section.
 *
 * Examples:
 *   Assign 5 sections to a lead auditor:  sectionIds=[1,2,3,4,5], auditorUserId=X
 *   Assign 5 sections to an auditee:       sectionIds=[1,2,3,4,5], auditeeUserId=Y
 */
@Data
public class BulkSectionAssignRequest {

    /** Section instance IDs to assign. */
    private List<Long> sectionIds;

    /** userId to assign as section auditor. Null = skip auditor assignment. */
    private Long auditorUserId;

    /** userId to assign as section auditee (evidence owner). Null = skip auditee assignment. */
    private Long auditeeUserId;

    /**
     * Whether each section assignment cascades to its child sections/controls.
     * Defaults to true (same as the per-section endpoints), preserving current
     * cascade behaviour.
     */
    private Boolean cascadeToChildren;
}