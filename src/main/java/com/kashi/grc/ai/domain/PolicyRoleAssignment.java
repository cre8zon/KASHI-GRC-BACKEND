package com.kashi.grc.ai.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

/**
 * Who holds a policy role, and from when to when.
 *
 * ── WHY POLICIES NAME ROLES, NOT PEOPLE ──────────────────────────────────────
 * "The Engineering Lead reviews dependency alerts weekly" stays true when the
 * Engineering Lead changes; "Arjun reviews…" does not, and correcting it means a
 * new version and a second approval of every policy that names him. So the
 * generated text uses role titles, and the current holder's name is filled in
 * when the policy is read, through {{role_<key>}} (PolicyVariableResolver).
 *
 * ── HISTORY IS KEPT, NOTHING IS OVERWRITTEN ──────────────────────────────────
 * Assigning a new holder ENDS the current row (effective_to = today) and starts
 * a new one. So "who was the Engineering Lead on 3 March?" — a question auditors
 * ask — is always answerable, and a departed holder's tenure stays on record.
 *
 * ── WHAT THIS IS NOT ─────────────────────────────────────────────────────────
 * It is not the approval record. Who approved a version, and when, is stored on
 * that version (approved_by_id, approved_at) and never comes from here.
 * It is not RBAC either: a login role decides what someone may click; a policy
 * role decides who is accountable. One person may hold several, or none that
 * match their login role.
 */
@Entity
@Table(name = "org_policy_role_assignments",
        indexes = {
                @Index(name = "idx_opra_tenant_role", columnList = "tenant_id, role_key"),
                @Index(name = "idx_opra_user",        columnList = "user_id")
        })
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class PolicyRoleAssignment extends TenantAwareEntity {

    /** Stable slug used in {{role_<key>}}: "engineering_lead". Never changes once created. */
    @Column(name = "role_key", nullable = false, length = 60)
    private String roleKey;

    /** As written in policies: "Engineering Lead". */
    @Column(name = "role_title", nullable = false, length = 120)
    private String roleTitle;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    /** Null while current. */
    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "assigned_by")
    private Long assignedBy;

    @Column(name = "note", length = 300)
    private String note;
}
