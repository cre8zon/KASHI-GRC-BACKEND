package com.kashi.grc.usermanagement.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * READ-ONLY view of the user_roles join table.
 *
 * WHY IT EXISTS
 *   User.roles is a @ManyToMany whose @JoinTable names only user_id and role_id.
 *   Hibernate can therefore neither read nor write user_roles.membership_id, and
 *   membership_id is exactly what says "this role applies in that tenant". Any
 *   query that needs to know which roles a person holds *in a specific tenant*
 *   has no way to express it through the object model.
 *
 *   This entity makes the column reachable for queries. It deliberately does not
 *   replace the @ManyToMany.
 *
 * WHY READ-ONLY
 *   Two mappings over one table is a well-known way to get double inserts and
 *   stale first-level cache entries. @Immutable plus insertable/updatable=false
 *   on every column means Hibernate will never write through this mapping —
 *   role assignment continues to go through User.roles exactly as it does today.
 *
 *   The consequence is that new user_roles rows still land with membership_id
 *   NULL until the assignment path is updated. Until then, re-run the backfill
 *   in 12_user_tenant_memberships.sql after any bulk role assignment.
 */
@Entity
@Table(name = "user_roles")
@org.hibernate.annotations.Immutable
@Getter @Setter
public class UserRole {

    @Id
    @Column(name = "id", insertable = false, updatable = false)
    private Long id;

    @Column(name = "user_id", insertable = false, updatable = false)
    private Long userId;

    @Column(name = "role_id", insertable = false, updatable = false)
    private Long roleId;

    /** NULL for rows written before the membership migration, or by the not-yet-updated assignment path. */
    @Column(name = "membership_id", insertable = false, updatable = false)
    private Long membershipId;

    @Column(name = "assigned_at", insertable = false, updatable = false)
    private LocalDateTime assignedAt;

    @Column(name = "expires_at", insertable = false, updatable = false)
    private LocalDateTime expiresAt;
}