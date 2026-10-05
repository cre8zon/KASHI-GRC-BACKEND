package com.kashi.grc.collab.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A person in a workspace.
 *
 * side = where the person comes from, read from their membership in the client
 * tenant — CLIENT for the client's own staff (HOME membership), FIRM for the
 * audit firm's people (GUEST membership under the workspace's firm). It is not
 * a role side and grants nothing by itself; what someone may do is decided by
 * permissions, and workspace_role only says who manages the workspace.
 */
@Entity
@Table(name = "collab_workspace_members",
        uniqueConstraints = @UniqueConstraint(name = "uk_collab_ws_member",
                columnNames = {"workspace_id", "user_id"}),
        indexes = {
                @Index(name = "idx_collab_wsm_user", columnList = "user_id"),
                @Index(name = "idx_collab_wsm_ws",   columnList = "workspace_id")
        })
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor
public class CollabWorkspaceMember extends TenantAwareEntity {

    public static final String SIDE_CLIENT = "CLIENT";
    public static final String SIDE_FIRM   = "FIRM";

    public static final String ROLE_OWNER  = "OWNER";
    public static final String ROLE_MEMBER = "MEMBER";
    public static final String ROLE_VIEWER = "VIEWER";

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** CLIENT | FIRM */
    @Column(name = "side", nullable = false, length = 10)
    private String side;

    /** OWNER | MEMBER | VIEWER */
    @Column(name = "workspace_role", nullable = false, length = 10)
    @lombok.Builder.Default
    private String workspaceRole = ROLE_MEMBER;

    @Column(name = "added_by")
    private Long addedBy;
}
