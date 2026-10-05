package com.kashi.grc.exception_register.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * What an exception covers.
 *
 * A link table rather than columns on the exception, because one exception
 * routinely covers several assets or several controls — and because the
 * alternative is six nullable foreign keys of which five are always null.
 */
@Entity
@Table(
        name = "exception_links",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_exc_link", columnNames = {"exception_id", "entity_type", "entity_id"}),
        indexes = @Index(name = "idx_exc_link_entity", columnList = "tenant_id,entity_type,entity_id"))
@Getter @Setter
@SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class ExceptionLink extends TenantAwareEntity {

    @Column(name = "exception_id", nullable = false)
    private Long exceptionId;

    /** RISK | ASSET | VENDOR | CONTROL | POLICY | ISSUE | FINDING | PERSONNEL */
    @Column(name = "entity_type", nullable = false, length = 40)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    /** Denormalised: showing ten links should not be ten lookups across eight modules. */
    @Column(name = "entity_label", length = 250)
    private String entityLabel;
}
