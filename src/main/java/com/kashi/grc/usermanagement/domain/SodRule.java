package com.kashi.grc.usermanagement.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "sod_rules")
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class SodRule extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "conflicting_role1_id", insertable = false, updatable = false)
    private Long conflictingRole1Id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "conflicting_role1_id", nullable = false)
    private Role conflictingRole1;

    @Column(name = "conflicting_role2_id", insertable = false, updatable = false)
    private Long conflictingRole2Id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "conflicting_role2_id", nullable = false)
    private Role conflictingRole2;

    @Column(name = "context_type")
    private String contextType;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "severity")
    @Builder.Default
    private String severity = "HIGH";

    @Column(name = "enforcement_mode")
    @Builder.Default
    private String enforcementMode = "HARD_BLOCK";
}
