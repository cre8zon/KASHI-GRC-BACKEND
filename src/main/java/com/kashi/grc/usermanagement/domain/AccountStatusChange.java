package com.kashi.grc.usermanagement.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * One change of an account's status — a user, a tenant or a vendor being
 * suspended, reactivated, deactivated, or an organisation's ownership moving.
 *
 * Who did it, when, and why. A status column alone answers none of those, and
 * "who suspended this account and why" is the first question anyone asks
 * after one.
 *
 * Append-only: rows are written by UserLifecycleService, the tenant and vendor
 * status endpoints and OrgOwnerService, and never edited.
 */
@Entity
@Table(name = "account_status_changes", indexes = {
        @Index(name = "ix_asc_subject", columnList = "subject_type, subject_id"),
        @Index(name = "ix_asc_tenant", columnList = "tenant_id")
})
@Getter @Setter @SuperBuilder @NoArgsConstructor @AllArgsConstructor
public class AccountStatusChange extends BaseEntity {

    public static final String USER   = "USER";
    public static final String TENANT = "TENANT";
    public static final String VENDOR = "VENDOR";

    /** USER, TENANT or VENDOR. */
    @Column(name = "subject_type", nullable = false, length = 20)
    private String subjectType;

    @Column(name = "subject_id", nullable = false)
    private Long subjectId;

    /** The organisation the subject belongs to (for a tenant, itself). */
    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "from_status", length = 50)
    private String fromStatus;

    /** The new status, or OWNER_TRANSFERRED for an ownership change. */
    @Column(name = "to_status", nullable = false, length = 50)
    private String toStatus;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    /** users.id of whoever made the change; null for the system. */
    @Column(name = "actor_user_id")
    private Long actorUserId;

    /** Free-form detail, e.g. how many open tasks were returned to unassigned. */
    @Column(name = "detail", length = 1000)
    private String detail;
}
