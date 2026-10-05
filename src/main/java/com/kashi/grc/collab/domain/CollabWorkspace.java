package com.kashi.grc.collab.domain;

import com.kashi.grc.common.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A Collaboration workspace — the working relationship between a client
 * organisation and one audit firm (or, with no firm, between the internal audit
 * team and the rest of the organisation).
 *
 * It exists before any engagement does: scoping, the plan, meetings, requests
 * and documents live here, and engagements are created inside it later.
 *
 * tenant_id is always the CLIENT tenant, so every row of the relationship sits
 * inside the client's data and the client keeps its veto: revoking the firm's
 * grant (firm_access_grants) ends the firm's access to the workspace at once,
 * because access is checked against that grant and the firm members' guest
 * memberships on every request (CollabAccessService).
 *
 * One ACTIVE workspace per client–firm pair (enforced in CollabWorkspaceService).
 * Several timelines with the same firm are programmes inside it.
 */
@Entity
@Table(name = "collab_workspaces",
        indexes = {
                @Index(name = "idx_collab_ws_tenant", columnList = "tenant_id"),
                @Index(name = "idx_collab_ws_firm",   columnList = "tenant_id, firm_tenant_id")
        })
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor
public class CollabWorkspace extends AuditableEntity {

    public static final String ACTIVE   = "ACTIVE";
    public static final String ARCHIVED = "ARCHIVED";

    /** The audit firm's tenant; null = internal workspace (no firm). */
    @Column(name = "firm_tenant_id")
    private Long firmTenantId;

    /** The firm_access_grants row this workspace depends on; null when internal. */
    @Column(name = "firm_grant_id")
    private Long firmGrantId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** ACTIVE | ARCHIVED */
    @Column(name = "status", nullable = false, length = 20)
    @lombok.Builder.Default
    private String status = ACTIVE;
}
